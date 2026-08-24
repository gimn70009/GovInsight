import json
from dataclasses import dataclass
from difflib import unified_diff

from langchain.tools import ToolRuntime, tool

from app.domains.analysis.company_profile import BISTELLIGENCE_PROFILE, CompanyProfile
from app.domains.analysis.context_tools import (
    read_company_profile,
    read_previous_analysis,
)
from app.domains.analysis.schemas.request import AnalysisDocumentRequest


@dataclass(frozen=True)
class AnalysisToolContext:
    document: AnalysisDocumentRequest
    max_text_chars: int
    company_profile: CompanyProfile = BISTELLIGENCE_PROFILE


def read_document_content(context: AnalysisToolContext) -> str:
    document = context.document
    payload = {
        "organizationName": document.organization_name,
        "boardName": document.board_name,
        "title": document.title,
        "publishedAt": document.published_at.isoformat() if document.published_at else None,
        "originalUrl": document.original_url,
        "contentText": _truncate(document.content_text, context.max_text_chars),
    }
    return json.dumps(payload, ensure_ascii=False)


def read_attachment_texts(context: AnalysisToolContext) -> str:
    remaining = context.max_text_chars
    attachments: list[dict[str, object]] = []

    for attachment in context.document.attachments:
        if not attachment.extracted_text or not attachment.extracted_text.strip():
            continue
        extracted_text = _truncate(attachment.extracted_text, remaining)
        attachments.append(
            {
                "attachmentId": attachment.attachment_id,
                "fileName": attachment.file_name,
                "extractedText": extracted_text,
            }
        )
        remaining -= len(extracted_text or "")
        if remaining <= 0:
            break

    return json.dumps(attachments, ensure_ascii=False)


def compare_with_previous_version(context: AnalysisToolContext) -> str:
    document = context.document
    previous = document.previous_version
    if previous is None:
        return json.dumps({"available": False}, ensure_ascii=False)

    current_content = _truncate(document.content_text, context.max_text_chars // 2) or ""
    previous_content = _truncate(previous.content_text, context.max_text_chars // 2) or ""
    diff = "\n".join(
        unified_diff(
            previous_content.splitlines(),
            current_content.splitlines(),
            fromfile="previous",
            tofile="current",
            lineterm="",
            n=2,
        )
    )
    payload = {
        "available": True,
        "previousVersionId": previous.version_id,
        "titleChanged": previous.title.strip() != document.title.strip(),
        "previousTitle": previous.title,
        "currentTitle": document.title,
        "contentDiff": _truncate(diff, context.max_text_chars),
    }
    return json.dumps(payload, ensure_ascii=False)


@tool
def get_document_content(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """현재 게시글의 제목, 본문, 게시일, 기관, 게시판과 원문 URL을 조회한다."""
    return read_document_content(runtime.context)


@tool
def get_attachment_texts(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """현재 문서 버전에 속한 첨부파일의 이름과 파싱 완료된 추출 텍스트를 조회한다."""
    return read_attachment_texts(runtime.context)


@tool
def compare_previous_version(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """수정 문서의 바로 이전 버전과 현재 버전의 제목·본문 차이를 조회한다."""
    return compare_with_previous_version(runtime.context)

@tool
def get_company_profile(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """회사 사업 분야, 서비스, 기술, 대상 산업과 확인되지 않은 정보를 조회한다."""
    return read_company_profile(runtime.context)


@tool
def get_previous_analysis(runtime: ToolRuntime[AnalysisToolContext]) -> str:
    """수정 문서의 직전 버전에 저장된 AI 분석과 제안 방향을 조회한다."""
    return read_previous_analysis(runtime.context)



ANALYSIS_TOOLS = [
    get_document_content,
    get_attachment_texts,
    compare_previous_version,
    get_company_profile,
    get_previous_analysis,
]


def _truncate(value: str | None, limit: int) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    if len(normalized) <= limit:
        return normalized
    return f"{normalized[:limit]}\n...[길이 제한으로 일부 생략]"
