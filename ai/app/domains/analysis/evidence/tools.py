import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Annotated

from langchain.tools import ToolRuntime, tool
from pydantic import Field

from app.domains.analysis.context.company_profile import (
    BISTELLIGENCE_PROFILE,
    CompanyProfile,
)
from app.domains.analysis.context.evaluation_settings import profile_for_settings
from app.domains.analysis.context.tools import (
    read_company_profile,
    read_previous_analysis,
)
from app.domains.analysis.evidence.coverage import Gap, confirmed_ranges
from app.domains.analysis.evidence.selection import allocate_budgets, select_evidence
from app.domains.analysis.evidence.targeted import (
    SEARCH_TOOLS,
    TargetedEvidence,
    read_source,
    search_sources,
)
from app.domains.analysis.evidence.version_comparison import compare_versions
from app.domains.analysis.schemas.request import AnalysisDocumentRequest


@dataclass(frozen=True)
class AnalysisToolContext:
    document: AnalysisDocumentRequest
    max_text_chars: int
    company_profile: CompanyProfile = BISTELLIGENCE_PROFILE
    result_cache: dict[str, str] = field(
        default_factory=dict,
        compare=False,
        repr=False,
    )

    targeted: TargetedEvidence = field(default_factory=TargetedEvidence, compare=False, repr=False)
    recovery_candidates: list[Gap] = field(default_factory=list, compare=False, repr=False)

    def __post_init__(self) -> None:
        if self.document.evaluation_settings is not None:
            object.__setattr__(
                self, "company_profile", profile_for_settings(self.document.evaluation_settings)
            )


def read_document_content(context: AnalysisToolContext) -> str:
    document = context.document
    excerpt = select_evidence(document.content_text, context.max_text_chars)
    payload = {
        "sourceId": "body",
        "organizationName": document.organization_name,
        "boardName": document.board_name,
        "title": document.title,
        "publishedAt": document.published_at.isoformat() if document.published_at else None,
        "originalUrl": document.original_url,
        "contentText": excerpt.text,
        "coverage": {**excerpt.metadata(), "budgetChars": context.max_text_chars},
    }
    return json.dumps(payload, ensure_ascii=False)


def read_attachment_texts(context: AnalysisToolContext) -> str:
    inventory = context.document.attachments
    unique = []
    fingerprints: dict[str, int] = {}
    duplicates: dict[int, int] = {}
    for i, attachment in enumerate(inventory):
        text = attachment.extracted_text or ""
        fingerprint = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if text.strip() and fingerprint in fingerprints:
            duplicates[i] = fingerprints[fingerprint]
        elif text.strip():
            fingerprints[fingerprint] = i
            unique.append(i)
    budgets = dict(
        zip(
            unique,
            allocate_budgets(
                [len(inventory[i].extracted_text or "") for i in unique], context.max_text_chars
            ),
        )
    )
    attachments = []
    for i, attachment in enumerate(inventory):
        excerpt = select_evidence(attachment.extracted_text, budgets.get(i, 0))
        item = {
            "sourceId": f"attachment:{attachment.attachment_id}",
            "attachmentId": attachment.attachment_id,
            "fileName": attachment.file_name,
            "extractedText": excerpt.text,
            "coverage": excerpt.metadata(),
        }
        if i in duplicates:
            item["duplicateOfAttachmentId"] = inventory[duplicates[i]].attachment_id
        if not attachment.extracted_text or not attachment.extracted_text.strip():
            item["unavailableReason"] = "파싱된 원문 없음"
        attachments.append(item)
    return json.dumps(attachments, ensure_ascii=False)


def compare_with_previous_version(context: AnalysisToolContext) -> str:
    return json.dumps(
        compare_versions(context.document, context.max_text_chars), ensure_ascii=False
    )


@tool
def get_document_content(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """현재 게시글의 제목, 본문, 게시일, 기관, 게시판과 원문 URL을 조회한다."""
    return _cached_result(
        runtime.context,
        "get_document_content",
        lambda: read_document_content(runtime.context),
    )


@tool
def get_attachment_texts(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """현재 문서 버전에 속한 첨부파일의 이름과 파싱 완료된 추출 텍스트를 조회한다."""
    return _cached_result(
        runtime.context,
        "get_attachment_texts",
        lambda: read_attachment_texts(runtime.context),
    )


@tool
def compare_previous_version(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """직전 버전과 현재 버전의 제목·본문·첨부 차이와 비교 한계를 조회한다."""
    return _cached_result(
        runtime.context,
        "compare_previous_version",
        lambda: compare_with_previous_version(runtime.context),
    )


@tool
def get_company_profile(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """회사 사업 분야, 서비스, 기술, 대상 산업과 확인되지 않은 정보를 조회한다."""
    return _cached_result(
        runtime.context,
        "get_company_profile",
        lambda: read_company_profile(runtime.context),
    )


@tool
def get_previous_analysis(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """수정 문서의 직전 버전에 저장된 AI 분석과 제안 방향을 조회한다."""
    return _cached_result(
        runtime.context,
        "get_previous_analysis",
        lambda: read_previous_analysis(runtime.context),
    )


@tool
def search_document_evidence(
    queries: Annotated[
        list[Annotated[str, Field(min_length=2, max_length=80)]],
        Field(min_length=1, max_length=5),
    ],
    runtime: ToolRuntime[AnalysisToolContext],
    source_id: Annotated[
        str, Field(pattern=r"^(all|body|attachment:[1-9][0-9]*)$")
    ] = "all",
    offset: Annotated[int, Field(ge=0, le=200, strict=True)] = 0,
    unread_only: bool = False,
) -> str:
    """생략된 현재 본문·첨부에서 2~80자 원문 표현을 최대 5개 OR 검색한다.

    queries 예: ["지원 제외", "신청 제한"]. 정규식·의미 검색이 아니며 공백 차이를 허용한다.
    source_id는 all, body 또는 첨부 조회의 attachment:N이다. 파일명·URL을 받지 않는다.
    최대 5개 구간과 원문 위치를 반환한다. 다음 페이지는 nextOffset을 offset에 넣는다.
    READ_REQUIRED이면 해당 matchStart/matchEnd로 read_document_evidence를 호출한다.
    unread_only=true이면 기본 조회에서 이미 읽은 완전한 구간을 제외한다.
    로컬 검사가 추가 검색을 요구한 경우 미확인 핵심 후보 구간에서만 일치 표현을 찾는다.
    동일 검색은 반복하지 않는다. 검색·문맥 조회의 새 요청은 합계 2회 이내이다.
    """
    context = runtime.context
    focused = bool(context.recovery_candidates)
    candidates: dict[str, list[tuple[int, int]]] = {}
    for gap in context.recovery_candidates:
        candidates.setdefault(gap.source_id, []).append((gap.start, gap.end))
    arguments = {"queries": queries, "source_id": source_id, "offset": offset,
                 "unread_only": unread_only, "candidate_only": focused}
    return context.targeted.run(
        "search_document_evidence", runtime.tool_call_id, arguments, context.max_text_chars,
        lambda budget: search_sources(
            context.document, queries, source_id, offset, budget,
            # Keep pagination stable: exclude base coverage, not earlier search pages.
            confirmed_ranges(context.document, context.result_cache)
            if unread_only or focused else None,
            candidates if focused else None,
        ),
    )


@tool
def read_document_evidence(
    source_id: Annotated[str, Field(pattern=r"^(body|attachment:[1-9][0-9]*)$")],
    start: Annotated[int, Field(ge=0, strict=True)],
    end: Annotated[int, Field(gt=0, strict=True)],
    runtime: ToolRuntime[AnalysisToolContext],
) -> str:
    """검색한 원문 위치를 포함하는 완전한 문단·문장과 인접 문맥을 최대 2000자 읽는다.

    source_id는 body 또는 attachment:N이다. start/end는 검색 결과의 matchStart/matchEnd
    또는 로컬 미확인 검사에서 받은 원문 범위를 사용한다(0부터 시작, end 제외).
    임의 위치를 추측하지 않는다.
    반환 coverage가 실제 읽은 범위이다. 제한보다 긴 분할 불가 조건은 자르지 않고
    CONTEXT_TOO_LARGE를 반환한다. 검색·문맥 조회의 새 요청은 합계 2회 이내이다.
    """
    context = runtime.context
    arguments = {"source_id": source_id, "start": start, "end": end}
    return context.targeted.run(
        "read_document_evidence", runtime.tool_call_id, arguments, context.max_text_chars,
        lambda budget: read_source(context.document, source_id, start, end, budget),
    )


def optional_evidence_tools(context: AnalysisToolContext) -> frozenset[str]:
    """Only expose extra tools when the initial source budget can omit text."""
    document = context.document
    attachment_texts = {item.extracted_text for item in document.attachments if item.extracted_text}
    if (
        len(document.content_text or "") > context.max_text_chars
        or sum(len(text) for text in attachment_texts) > context.max_text_chars
    ):
        return SEARCH_TOOLS
    return frozenset()


def _cached_result(
    context: AnalysisToolContext,
    key: str,
    producer: Callable[[], str],
) -> str:
    cached = context.result_cache.get(key)
    if cached is not None:
        return cached
    result = producer()
    context.result_cache[key] = result
    return result


ANALYSIS_TOOLS = [
    get_document_content,
    get_attachment_texts,
    compare_previous_version,
    get_company_profile,
    get_previous_analysis,
    search_document_evidence,
    read_document_evidence,
]
