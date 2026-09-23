import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass, field

from langchain.tools import ToolRuntime, tool

from app.domains.analysis.context.company_profile import (
    BISTELLIGENCE_PROFILE,
    CompanyProfile,
)
from app.domains.analysis.context.tools import (
    read_company_profile,
    read_previous_analysis,
)
from app.domains.analysis.evidence.selection import allocate_budgets, select_evidence
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


def read_document_content(context: AnalysisToolContext) -> str:
    document = context.document
    excerpt = select_evidence(document.content_text, context.max_text_chars)
    payload = {
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
]
