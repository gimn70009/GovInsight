"""Opt-in, bounded live evaluation of evidence tools; default is API-free.

Run from ai: python scripts/evaluate_targeted_evidence.py [--live] [--output PATH]
Uses synthetic notices/company facts, the unchanged production evidence prompt,
and the configured model. No final analysis, embeddings, DB or report requests.
The reduced excerpt budget deliberately reproduces omissions at low API cost.
Results are recovery checks, not a statistical accuracy benchmark.
"""

import argparse
import asyncio
import json
import logging
import sys
import time
from dataclasses import dataclass, fields
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from langchain_core.messages import convert_to_openai_messages

from app.core.openai_models import ChatOpenAI
from app.domains.analysis.config import AnalysisSettings
from app.domains.analysis.context.company_profile import CompanyProfile
from app.domains.analysis.evidence.agent import AnalysisEvidenceAgent
from app.domains.analysis.evidence.targeted import (
    SEARCH_TOOLS,
    read_source,
    search_sources,
)
from app.domains.analysis.evidence.tools import (
    AnalysisToolContext,
    optional_evidence_tools,
    read_attachment_texts,
    read_document_content,
)
from app.domains.analysis.schemas.request import (
    AnalysisAttachmentRequest,
    AnalysisDocumentRequest,
)

SOURCE_BUDGET = 2500
MAX_CALLS_PER_CASE = 5
MAX_TOTAL_CALLS = 16
MAX_TOTAL_REQUEST_BYTES = 400_000
STOP_AFTER_REPORTED_TOKENS = 50_000


@dataclass(frozen=True)
class Case:
    name: str
    document: AnalysisDocumentRequest
    expected: tuple[str, ...]
    queries: tuple[str, ...]
    needs_extra: bool
    needs_read: bool = False


def cases() -> list[Case]:
    filler = "\n\n".join(
        f"사업 운영 안내 {i}: 지원 대상 사업의 수행 일정과 접수 기한은 운영계획을 따릅니다. "
        "사업비 정산과 과업별 성과 점검은 협약에 따라 진행합니다. 문의는 담당 기관에 "
        "접수하며, 각 단계별 처리 절차와 운영 현황을 관리합니다."
        for i in range(420)
    )
    common = (
        "사업 개요: 제조 현장의 공정 개선을 지원합니다. 지원 대상은 국내 중소기업입니다. "
        "접수 마감은 2026년 11월 30일 18시이며, 사업비는 기업당 1천만원입니다. "
        "문의는 전담기관 사업 담당자에게 접수합니다."
    )
    docs = "제출서류 목록: 사업자등록증 사본, 국세 납세증명서, 제조공정 개선계획서."
    exclusion = "신청 제한: 공고일 현재 휴업 중인 기업은 신청할 수 없습니다."
    detailed = (
        "제출서류 목록: " + "각 서류의 발급일과 신청 기업의 명칭을 대조하고 "
        "서명과 날인 여부를 확인합니다. " * 20
        + "최종 필수 항목은 공장등록증과 지방세 납세증명서입니다."
    )

    def document(index, body, attachment=None):
        return AnalysisDocumentRequest(
            detection_id=index, document_id=index, version_id=index,
            change_type="NEW_DOCUMENT", organization_name="합성 검증 기관",
            board_name="합성 검증 공고", title="제조공정 개선 지원사업",
            original_url="https://example.org/synthetic-evaluation",
            content_text=body,
            attachments=[] if attachment is None else [
                AnalysisAttachmentRequest(
                    attachment_id=1, file_name="합성_공고문.txt", extracted_text=attachment,
                )
            ],
        )

    return [
        Case("short_complete", document(1, common + "\n\n" + docs + "\n\n" + exclusion),
             ("국세 납세증명서", "휴업 중인 기업은 신청할 수 없습니다"), (), False),
        Case("long_missing_documents", document(
            2, common + " 제출서류는 첨부 공고문의 별도 목록에 기재되어 있습니다.",
            common + "\n\n" + filler + "\n\n" + docs + "\n\n공고 끝.",
        ), ("국세 납세증명서", "제조공정 개선계획서"), ("제출서류",), True),
        Case("long_hidden_exclusion", document(
            3, common + "\n\n" + docs + "\n\n" + filler
            + "\n\n" + exclusion + "\n\n공고 끝.",
        ), ("휴업 중인 기업은 신청할 수 없습니다",), ("신청 제한",), True),
        Case("long_documents_need_context", document(
            4, common + " 제출서류는 첨부 공고문의 별도 목록에 기재되어 있습니다.",
            common + "\n\n" + filler + "\n\n" + detailed + "\n\n공고 끝.",
        ), ("공장등록증", "지방세 납세증명서"), ("제출서류",), True, True),
    ]


def context_for(case):
    values = {item.name: () for item in fields(CompanyProfile)}
    values.update(company_name="합성 제조기업", description="합성 중소기업",
                  location="미확인", unknown_fields=("공고별 자격 충족 여부",))
    return AnalysisToolContext(case.document, SOURCE_BUDGET, CompanyProfile(**values))


def initial_text(context):
    return "\n".join([
        json.loads(read_document_content(context))["contentText"],
        *[item["extractedText"] for item in json.loads(read_attachment_texts(context))],
    ])


def has_expected(text, case):
    return all(marker in text for marker in case.expected)


def preflight(case):
    context = context_for(case)
    initial = initial_text(context)
    present = has_expected(initial, case)
    available = bool(optional_evidence_tools(context))
    if available != case.needs_extra or present == case.needs_extra:
        raise AssertionError("Case does not reproduce the intended initial coverage")
    row = dict(
        case=case.name, source_budget=SOURCE_BUDGET,
        source_chars=len(case.document.content_text or "") + sum(
            len(item.extracted_text or "") for item in case.document.attachments
        ),
        initial_selected_chars=len(initial), initial_expected_present=present,
        optional_available=available,
    )
    if not case.needs_extra:
        return row
    source_id = "attachment:1" if case.document.attachments else "body"
    search = search_sources(case.document, list(case.queries), source_id, 0, SOURCE_BUDGET)
    recovered = "\n".join(hit["text"] for hit in search["matches"])
    needed_read = False
    for hit in search["matches"]:
        if hit["contextStatus"] == "READ_REQUIRED":
            needed_read = True
            result = read_source(
                case.document, hit["sourceId"], hit["matchStart"], hit["matchEnd"],
                SOURCE_BUDGET - search["selectedChars"],
            )
            recovered += "\n" + result["text"]
    if not has_expected(recovered, case) or needed_read != case.needs_read:
        raise AssertionError("Direct tool recovery did not match the intended case")
    row.update(direct_recovery=True, direct_read_required=needed_read)
    return row


def collected_text(sections):
    # Only actual returned text counts. Query strings and metadata cannot pass a case.
    result = []
    for section in sections:
        tag = section[:section.index(">") + 1]
        payload = section[section.index(">") + 1:section.rindex("</")].strip()
        data = json.loads(payload)
        if tag == "<current_document>":
            result.append(data["contentText"])
        elif tag == "<attachments>":
            result.extend(item["extractedText"] for item in data)
        elif tag == "<targeted_search>":
            result.extend(item["text"] for item in data.get("matches", []))
        elif tag == "<targeted_context>":
            result.append(data.get("text", ""))
    return "\n".join(result)


async def live(selected, report):
    settings = AnalysisSettings.from_env()
    report["model"] = settings.model_name
    report["production_source_budget"] = settings.max_text_chars
    report["limits"] = dict(
        calls_per_case=MAX_CALLS_PER_CASE, total_calls=MAX_TOTAL_CALLS,
        max_completion_tokens_per_call=1200,
        total_request_bytes=MAX_TOTAL_REQUEST_BYTES,
        stop_after_reported_tokens=STOP_AFTER_REPORTED_TOKENS,
    )
    events = report["api_calls"] = []
    original = ChatOpenAI._agenerate
    current = None

    async def measured(model, messages, stop=None, run_manager=None, **kwargs):
        request_bytes = len(json.dumps(
            {"messages": convert_to_openai_messages(messages), **kwargs},
            ensure_ascii=False,
        ).encode("utf-8"))
        if (
            len(events) >= MAX_TOTAL_CALLS
            or sum(event["case"] == current for event in events) >= MAX_CALLS_PER_CASE
            or sum(event["request_bytes"] for event in events) + request_bytes
            > MAX_TOTAL_REQUEST_BYTES
            or sum(event.get("total_tokens", 0) for event in events)
            >= STOP_AFTER_REPORTED_TOKENS
        ):
            raise RuntimeError("Evaluation budget reached")
        event = dict(case=current, request_bytes=request_bytes, status="started")
        events.append(event)
        started = time.perf_counter()
        try:
            response = await original(
                model, messages, stop=stop, run_manager=run_manager, **kwargs,
            )
            message = response.generations[0].message
            event.update(
                status="completed", **(message.usage_metadata or {}),
                requested_tools=[call["name"] for call in message.tool_calls],
                finish_reason=message.response_metadata.get("finish_reason"),
                forced_tool=(
                    kwargs["tool_choice"]["function"]["name"]
                    if isinstance(kwargs.get("tool_choice"), dict) else None
                ),
            )
            return response
        except BaseException as error:
            event["status"] = type(error).__name__
            raise
        finally:
            event["seconds"] = round(time.perf_counter() - started, 3)
            print(json.dumps(event, ensure_ascii=False), flush=True)

    model = ChatOpenAI(
        model=settings.model_name, api_key=settings.api_key,
        timeout=min(settings.timeout_seconds, 40), max_retries=0,
        reasoning_effort="minimal", max_tokens=1200,
    )
    with patch.object(ChatOpenAI, "_agenerate", measured):
        for case, row in zip(selected, report["cases"]):
            current = case.name
            context = context_for(case)
            started = time.perf_counter()
            try:
                sections, used = await AnalysisEvidenceAgent(model).collect(context)
                recovered = has_expected(collected_text(sections), case)
                checks = [
                    json.loads(section[section.index(">") + 1:section.rindex("</")])
                    for section in sections if section.startswith("<evidence_check>")
                ]
                row["evidence_check"] = checks[0] if checks else None
                row.update(
                    status="completed", used_tools=used, expected_recovered=recovered,
                    extra_called=bool(set(used) & SEARCH_TOOLS),
                    read_called="read_document_evidence" in used,
                    passed=recovered and (
                        bool(set(used) & SEARCH_TOOLS) == case.needs_extra
                    ) and (not case.needs_read or "read_document_evidence" in used)
                    and all(check["status"] == "CANDIDATES_RECOVERED" for check in checks),
                )
            except Exception as error:
                row.update(status=type(error).__name__, passed=False)
            row.update(
                seconds=round(time.perf_counter() - started, 3),
                additional_source_chars=context.targeted.selected_chars,
                extra_results=[
                    dict(tool=json.loads(key)[0], status=json.loads(value)["status"],
                         selected_chars=json.loads(value)["selectedChars"],
                         read_required=sum(
                             hit["contextStatus"] == "READ_REQUIRED"
                             for hit in json.loads(value).get("matches", [])
                         ))
                    for key, value in context.targeted.cache.items()
                ],
            )
            print(json.dumps(row, ensure_ascii=False), flush=True)
    report["usage"] = {
        key: sum(event.get(key, 0) for event in events)
        for key in ("input_tokens", "output_tokens", "total_tokens")
    }
    report["usage"]["calls_without_reported_usage"] = sum(
        "total_tokens" not in event for event in events
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Explicitly enable paid API calls")
    parser.add_argument("--case", action="append", choices=[case.name for case in cases()])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    selected = [case for case in cases() if not args.case or case.name in args.case]
    report = dict(mode="live" if args.live else "offline", cases=[
        preflight(case) for case in selected
    ])
    # Suppress SDK/agent payload logging. Output contains metadata only.
    logging.basicConfig(level=logging.CRITICAL)
    if args.live:
        asyncio.run(live(selected, report))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if args.live and any(not row["passed"] for row in report["cases"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
