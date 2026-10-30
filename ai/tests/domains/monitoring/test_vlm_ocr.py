import asyncio
import base64
import io
import json
import threading
import zipfile
from dataclasses import replace

import httpx
import pytest
from pypdf import PdfReader, PdfWriter
from pypdf.generic import (
    DecodedStreamObject,
    DictionaryObject,
    NameObject,
    NumberObject,
)

from app.domains.monitoring.parsers import vlm_ocr
from app.domains.monitoring.parsers.pdf_parser import PdfParseError, PdfParser
from app.domains.monitoring.parsers.registry import AttachmentParserRegistry
from app.domains.monitoring.parsers.vlm_config import VlmOcrSettings
from app.domains.monitoring.parsers.vlm_ocr import VlmOcr, _candidate
from app.domains.monitoring.parsers.vlm_schema import OcrPage
from tests.domains.monitoring.test_pdf_parser_real_file import _minimal_text_pdf


@pytest.fixture(autouse=True)
def empty_ocr_cache():
    vlm_ocr._CACHE.clear()
    yield
    vlm_ocr._CACHE.clear()


def scan_pdf(pages=1, shade=0):
    writer = PdfWriter()
    for _ in range(pages):
        page = writer.add_blank_page(width=595, height=842)
        image = DecodedStreamObject()
        image.set_data(bytes([shade, shade, shade]))
        image.update({
            NameObject("/Type"): NameObject("/XObject"),
            NameObject("/Subtype"): NameObject("/Image"),
            NameObject("/Width"): NumberObject(1),
            NameObject("/Height"): NumberObject(1),
            NameObject("/ColorSpace"): NameObject("/DeviceRGB"),
            NameObject("/BitsPerComponent"): NumberObject(8),
        })
        page[NameObject("/Resources")] = DictionaryObject({
            NameObject("/XObject"): DictionaryObject({
                NameObject("/Im0"): writer._add_object(image),
            }),
        })
        content = DecodedStreamObject()
        content.set_data(b"q 595 0 0 842 0 0 cm /Im0 Do Q")
        page[NameObject("/Contents")] = writer._add_object(content)
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def cell(row, column, text, row_span=1, column_span=1):
    return dict(row=row, column=column, text=text, row_span=row_span, column_span=column_span)


def transcription():
    return {
        "complete": True,
        "blocks": [
            {"kind": "text", "text": "제출서류", "cells": []},
            {"kind": "table", "text": "", "cells": [
                cell(0, 0, "제출 대상", row_span=2),
                cell(0, 1, "제출서류", column_span=2),
                cell(1, 1, "서류명"), cell(1, 2, "비고"),
                cell(2, 0, "공통"), cell(2, 1, "신청서\n사업계획서"), cell(2, 2, "필수"),
                cell(3, 0, ""), cell(3, 1, "확인서"), cell(3, 2, "해당 시"),
            ]},
            {"kind": "text", "text": "※ 확인서는 선정 후 제출. 마감 2026.10.30", "cells": []},
        ],
    }


def response(page=None, **kwargs):
    result = {
        "status": "completed",
        "output": [{"type": "message", "status": "completed", "role": "assistant",
                    "content": [{"type": "output_text",
                                 "text": json.dumps(page or transcription(),
                                                    ensure_ascii=False)}]}],
    }
    result.update(kwargs)
    return httpx.Response(200, json=result)


def ocr(handler, **settings):
    return VlmOcr(VlmOcrSettings(api_key="unit-test-secret", **settings),
                  transport=httpx.MockTransport(handler))


def test_real_pdf_request_and_shared_table_contract(tmp_path):
    path = tmp_path / "source.pdf"
    path.write_bytes(scan_pdf())
    requests = []

    def handle(request):
        payload = json.loads(request.content)
        requests.append(payload)
        assert str(request.url) == "https://api.openai.com/v1/responses"
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        assert payload["store"] is False
        assert payload["max_output_tokens"] == 4096
        assert payload["reasoning"] == {"effort": "minimal"}
        assert payload["text"]["format"]["strict"] is True
        assert payload["text"]["format"]["schema"]["additionalProperties"] is False
        file = payload["input"][0]["content"][0]
        assert file["filename"] == "page.pdf" and file["detail"] == "low"
        data = base64.b64decode(file["file_data"].split(",", 1)[1])
        assert len(PdfReader(io.BytesIO(data)).pages) == 1
        return response()

    result = PdfParser(ocr=ocr(handle)).parse(path)
    assert len(requests) == 1
    assert "[표]\n제출 대상\t제출서류\t제출서류" in result.text
    assert "신청서 ⏎ 사업계획서\t필수" in result.text
    assert "\n\t확인서\t해당 시\n[/표]" in result.text
    assert result.text.endswith("※ 확인서는 선정 후 제출. 마감 2026.10.30")
    assert result.warning is None


def test_per_file_and_document_limits():
    calls = []
    service = ocr(lambda req: calls.append(req) or response(),
                  max_pages_per_file=1, max_calls_per_document=2)
    first = PdfReader(io.BytesIO(scan_pdf(pages=3)))
    pages, warning = service.enhance(first, ["", "", ""])
    assert len(calls) == 1 and pages[0] and not pages[1]
    assert warning is None
    second = PdfReader(io.BytesIO(scan_pdf(shade=30)))
    assert service.enhance(second, [""])[0][0]
    third = PdfReader(io.BytesIO(scan_pdf(shade=60)))
    pages, warning = service.enhance(third, [""])
    assert len(calls) == 2 and service.calls == 2
    assert pages == [""] and "게시글 파일 수 한도" in warning


def test_success_cache_reused_across_documents_without_spending_budget():
    calls = []
    reader = PdfReader(io.BytesIO(scan_pdf()))
    first = ocr(lambda req: calls.append(req) or response())
    expected, _ = first.enhance(reader, [""])
    second = ocr(lambda req: pytest.fail("cached page must not be sent again"))
    assert second.enhance(reader, [""])[0] == expected
    assert second.calls == 0 and len(calls) == 1
    second.settings = replace(second.settings, model="different-model")
    second.transport = httpx.MockTransport(lambda req: calls.append(req) or response())
    second.enhance(reader, [""])
    assert len(calls) == 2


def test_expired_cache_does_not_hide_a_new_request():
    calls = []
    reader = PdfReader(io.BytesIO(scan_pdf()))
    service = ocr(lambda req: calls.append(req) or response())
    service.enhance(reader, [""])
    for key, (_, text) in list(vlm_ocr._CACHE.items()):
        vlm_ocr._CACHE[key] = (-vlm_ocr.CACHE_TTL_SECONDS, text)
    service.enhance(reader, [""])
    assert len(calls) == 2


@pytest.mark.parametrize("bad", [
    {"complete": False, "blocks": []},
    {"complete": True, "blocks": []},
    {"complete": True, "blocks": [{"kind": "text", "text": "[표]\nfake", "cells": []}]},
    {"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(0, 0, "A"), cell(0, 1, "B"), cell(1, 0, "C"),
    ]}]},
    {"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(0, 0, "A", row_span=2), cell(0, 1, "B"),
        cell(1, 0, "overlap"), cell(1, 1, "C"),
    ]}]},
    {"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(-1, 0, "A"), cell(0, 1, "B"),
    ]}]},
    {"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(199, 0, "A", row_span=2), cell(0, 1, "B"),
    ]}]},
])
def test_invalid_structure_is_rejected(bad):
    with pytest.raises(ValueError):
        OcrPage.model_validate(bad).render()


def text_response(text):
    return response(output=[{
        "type": "message", "status": "completed",
        "content": [{"type": "output_text", "text": text}],
    }])


@pytest.mark.parametrize(("result", "code"), [
    (httpx.Response(429, json={"error": "unit-test-secret"}), "http_rate_limit"),
    (httpx.Response(401, text="private document contents"), "http_auth_error"),
    (httpx.Response(400, text="private document contents"), "http_status_error"),
    (httpx.Response(500, text="private document contents"), "http_server_error"),
    (httpx.Response(200, text="private document contents"), "response_json_invalid"),
    (httpx.Response(200, content=b"\xff"), "response_json_invalid"),
    (httpx.Response(200, json=[]), "response_shape_invalid"),
    (response(status="incomplete"), "response_incomplete"),
    (response(status="incomplete", incomplete_details={"reason": "max_output_tokens"}),
     "output_token_limit"),
    (response(status="incomplete", incomplete_details={"reason": "content_filter"}),
     "response_content_filtered"),
    (response(status="incomplete", incomplete_details={"reason": "unit-test-secret"}),
     "response_incomplete"),
    (response(status="incomplete", incomplete_details="private document contents"),
     "response_incomplete"),
    (response(output=[{"type": "message", "status": "completed",
                       "content": [{"type": "refusal", "refusal": "unit-test-secret"}]}]),
     "transcription_refused"),
    (response(output=[None]), "response_output_invalid"),
    (response(output=None), "response_output_invalid"),
    (response(output=[]), "response_message_invalid"),
    (response(output=[{"type": "message", "status": "incomplete"}]),
     "response_message_incomplete"),
    (response(output=[{"type": "message", "status": "completed", "content": []}]),
     "transcription_missing"),
    (text_response(None), "transcription_missing"),
    (text_response('{"private document contents":'), "transcription_json_invalid"),
    (response({"complete": "unit-test-secret", "blocks": []}),
     "transcription_schema_invalid"),
    (response({"complete": False, "blocks": []}), "transcription_incomplete"),
    (response({"complete": True, "blocks": []}), "transcription_empty_or_oversized"),
    (response({"complete": True, "blocks": [{"kind": "text", "text": "", "cells": []}]}),
     "text_block_invalid"),
    (response({"complete": True, "blocks": [{
        "kind": "text", "text": "[표]private document contents", "cells": [],
    }]}), "reserved_document_marker"),
    (response({"complete": True, "blocks": [{"kind": "table", "text": "", "cells": []}]}),
     "table_block_invalid"),
    (response({"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(0, 0, "A"), cell(0, 1, "B"), cell(1, 0, "C"),
    ]}]}), "table_structure_invalid"),
    (response({"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(0, 0, "A", row_span=2), cell(0, 1, "B"),
        cell(1, 0, "private document contents"), cell(1, 1, "C"),
    ]}]}), "table_structure_invalid"),
    (response({"complete": True, "blocks": [{"kind": "table", "text": "", "cells": [
        cell(199, 0, "A", row_span=2), cell(0, 1, "B"),
    ]}]}), "table_size_limit"),
])
def test_failure_code_preserves_text_and_never_retries(result, code, parser_logs):
    calls = []
    service = ocr(lambda req: calls.append(req) or result)
    original = ["제출서류 서류명 비고", "신청자격 중소기업"]
    actual, warning = service.enhance(two_distinct_scan_pages(), original)
    assert actual == original and warning
    assert len(calls) == service.calls == 2 and service.files == 1
    assert not vlm_ocr._CACHE  # A rejected page is never reused as successful OCR.
    assert f"error_code={code}" in parser_logs.text
    assert "stop_reason=response_or_page_error" in parser_logs.text
    assert "unit-test-secret" not in parser_logs.text
    assert "private document contents" not in parser_logs.text
    assert original[0] not in parser_logs.text


@pytest.mark.parametrize(("error", "code"), [
    (httpx.ConnectError("unit-test-secret"), "http_transport_error"),
    (OSError("unit-test-secret"), "file_or_runtime_error"),
    (ValueError("unit-test-secret"), "unclassified_error"),
    (TypeError("unit-test-secret"), "unclassified_error"),
    (KeyError("unit-test-secret"), "unclassified_error"),
])
def test_unexpected_error_details_are_not_logged(error, code, parser_logs):
    def failed_request(request):
        raise error

    service = ocr(failed_request)
    original = ["제출서류 비고", "신청자격 중소기업"]
    actual, warning = service.enhance(two_distinct_scan_pages(), original)
    assert actual == original and warning and service.calls == 2
    assert f"error_code={code}" in parser_logs.text
    assert "unit-test-secret" not in parser_logs.text


def test_file_deadline_cancels_request_and_preserves_original(parser_logs):
    cancelled = []

    async def slow(request):
        try:
            await asyncio.sleep(1)
        except asyncio.CancelledError:
            cancelled.append(True)
            raise
        return response()

    parser_logs.set_level("INFO", logger=vlm_ocr.__name__)
    service = ocr(slow, timeout_seconds=0.02)
    reader = PdfReader(io.BytesIO(scan_pdf()))
    pages, warning = service.enhance(reader, ["제출서류 비고"])
    assert pages == ["제출서류 비고"] and "파일 전체 시간 한도" in warning and cancelled
    assert service.calls == 1
    assert "stop_reason=file_timeout" in parser_logs.text
    assert "error_code=file_timeout" in parser_logs.text


def test_size_limit_rejects_before_paid_call(monkeypatch, parser_logs):
    monkeypatch.setattr(vlm_ocr, "MAX_PDF_BYTES", 100)
    service = ocr(lambda req: pytest.fail("oversized page must not be sent"))
    pages, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf())), [""])
    assert pages == [""] and warning and service.calls == 0
    assert "error_code=pdf_page_size_limit" in parser_logs.text


def test_source_text_size_limit_is_logged_before_paid_call(parser_logs):
    service = ocr(lambda req: pytest.fail("oversized source must not be sent"))
    original = ["제출서류 비고" + "가" * 12000]
    actual, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf())), original)
    assert actual == original and warning and service.calls == 0
    assert "error_code=source_text_size_limit" in parser_logs.text
    assert original[0] not in parser_logs.text


def test_large_response_falls_back_without_retry(monkeypatch, parser_logs):
    monkeypatch.setattr(vlm_ocr, "MAX_RESPONSE_BYTES", 100)
    service = ocr(lambda req: response())
    original = ["제출서류 비고"]
    pages, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf())), original)
    assert pages == original and warning and service.calls == 1
    assert "error_code=response_size_limit" in parser_logs.text


def test_regular_text_blank_and_existing_structured_tables_skip_ocr():
    page = PdfReader(io.BytesIO(_minimal_text_pdf("Hello GovInsight"))).pages[0]
    assert not _candidate(page, "Hello GovInsight")
    assert not _candidate({}, "")
    assert not _candidate(page, "[표]\nA\tB\nC\tD\n[/표]")
    assert _candidate(page, "제출서류\n서류명 비고\n신청서 필수")
    assert _candidate(PdfReader(io.BytesIO(scan_pdf())).pages[0], "안내")


@pytest.mark.parametrize(("extracted", "visual"), [
    ("제출서류 과과제제 수수 11개개", "제출서류 과제 수 1개"),
    ("제출서류 영리기관만 사업자등록증 제출", "제출서류 사업자등록증 영리기관만 제출"),
    ("제출서류 기한 22002266.1100.3300", "제출서류 기한 2026.10.30"),
])
def test_visual_text_can_correct_parser_artifacts_and_reuse_cache(extracted, visual):
    calls = []
    service = ocr(lambda req: calls.append(req) or response({
        "complete": True, "blocks": [{"kind": "text", "text": visual, "cells": []}],
    }))
    reader = PdfReader(io.BytesIO(scan_pdf()))
    for _ in range(2):
        pages, warning = service.enhance(reader, [extracted])
        assert pages == [visual] and warning is None
    assert len(calls) == service.calls == 1


def test_disabled_missing_key_and_busy_do_not_make_requests():
    reader = PdfReader(io.BytesIO(scan_pdf()))
    service = ocr(lambda req: pytest.fail("no request expected"), enabled=False)
    assert service.enhance(reader, [""]) == ([""], None)
    service.settings = replace(service.settings, enabled=True, api_key="")
    assert "API 키" in service.enhance(reader, [""])[1]
    service.settings = replace(service.settings, api_key="unit-test-secret")
    vlm_ocr._ACTIVE.acquire()
    vlm_ocr._ACTIVE.acquire()
    try:
        assert "동시 처리" in service.enhance(reader, [""])[1]
    finally:
        vlm_ocr._ACTIVE.release()
        vlm_ocr._ACTIVE.release()


def test_scanned_pdf_failure_is_not_reported_as_completed(tmp_path):
    path = tmp_path / "scan.pdf"
    path.write_bytes(scan_pdf())
    with pytest.raises(PdfParseError, match="읽을 텍스트"):
        PdfParser(ocr=ocr(lambda req: httpx.Response(401))).parse(path)


def test_registry_shares_budget_between_pdf_and_zip(tmp_path, monkeypatch):
    service = ocr(lambda req: response())
    from app.domains.monitoring.parsers import vlm_config
    monkeypatch.setattr(vlm_config.VlmOcrSettings, "from_env", lambda: service.settings)
    monkeypatch.setattr(vlm_ocr, "VlmOcr", lambda settings, **kwargs: service)
    registry = AttachmentParserRegistry()
    path = tmp_path / "first.pdf"
    path.write_bytes(scan_pdf())
    assert "[표]" in registry.find(path.name).parse(path).text
    archive = tmp_path / "more.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("second.pdf", scan_pdf(shade=30))
        output.writestr("third.pdf", scan_pdf(shade=60))
    parsed = registry.find(archive.name).parse(archive)
    assert service.calls == service.files == 2
    assert [e["status"] for e in parsed.archive_entries] == ["COMPLETED", "FAILED"]


def test_config_defaults_and_bad_values(monkeypatch):
    from app.domains.monitoring.parsers import vlm_config
    monkeypatch.setattr(vlm_config, "load_dotenv", lambda *a: None)
    monkeypatch.delenv("VLM_OCR_ENABLED")
    for key in ["VLM_OCR_MAX_PAGES_PER_FILE", "VLM_OCR_MAX_CALLS_PER_DOCUMENT",
                "VLM_OCR_MAX_FILES_PER_DOCUMENT", "VLM_OCR_TIMEOUT_SECONDS",
                "VLM_OCR_PAGE_TIMEOUT_SECONDS",
                "VLM_OCR_MAX_OUTPUT_TOKENS", "VLM_OCR_MODEL"]:
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("OPENAI_API_KEY", "secret-never-repr")
    settings = VlmOcrSettings.from_env()
    assert settings.enabled and settings.max_pages_per_file == 2
    assert settings.max_files_per_document == 2
    assert settings.page_timeout_seconds == 120 and settings.timeout_seconds == 240
    assert settings.max_calls_per_document == 4 and settings.max_output_tokens == 4096
    assert "secret-never-repr" not in repr(settings)
    for value in ["0", "-1", "5", "nan", "oops"]:
        monkeypatch.setenv("VLM_OCR_MAX_CALLS_PER_DOCUMENT", value)
        with pytest.raises(ValueError):
            VlmOcrSettings.from_env()


def test_downloader_runs_parser_off_event_loop_and_preserves_warning(tmp_path):
    from app.domains.monitoring.downloaders.attachment_downloader import AttachmentDownloader
    from app.domains.monitoring.parsers.base import ParsedAttachment
    from app.domains.monitoring.schemas.collected_document import CollectedAttachment

    event_loop_thread = threading.get_ident()

    class Parser:
        def parse(self, path):
            assert threading.get_ident() != event_loop_thread
            assert path.exists()
            return ParsedAttachment(text="기존 추출문", warning="OCR 한도")

    class Registry:
        def find(self, name):
            return Parser()

    async def run():
        downloader = AttachmentDownloader(parser_registry=Registry())
        transport = httpx.MockTransport(lambda req: httpx.Response(200, content=b"pdf"))
        async with httpx.AsyncClient(transport=transport) as client:
            return await downloader._download(
                client, CollectedAttachment(file_name="sample.pdf", download_url="https://test/a"),
                "https://test",
            )
    result = asyncio.run(run())
    assert result.parse_status == "COMPLETED"
    assert result.extracted_text == "기존 추출문" and result.error_message == "OCR 한도"


def test_default_budget_allows_two_pages_per_file_and_four_total_calls():
    calls = []
    service = ocr(lambda req: calls.append(req) or response())
    for file_index in range(3):
        writer = PdfWriter()
        for page_index in range(2):
            page = PdfReader(io.BytesIO(scan_pdf(shade=file_index * 10 + page_index))).pages[0]
            writer.add_page(page)
        stream = io.BytesIO()
        writer.write(stream)
        original = ["제출서류 비고", "신청자격 중소기업"]
        pages, warning = service.enhance(PdfReader(io.BytesIO(stream.getvalue())), original)
        if file_index < 2:
            assert all("[표]" in text for text in pages) and warning is None
        else:
            assert pages == original and "게시글 파일 수 한도" in warning
    assert len(calls) == service.calls == 4


def test_log_distinguishes_candidate_pages_requests_and_cache(monkeypatch):
    messages = []
    monkeypatch.setattr(vlm_ocr.logger, "info", lambda fmt, *args: messages.append(fmt % args))
    service = ocr(lambda req: response())
    # Identical pages can reuse the first result; 20 candidates do not mean 20 calls.
    pages, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf(pages=20))), [""] * 20)
    assert pages[0] and pages[1] and not any(pages[2:]) and warning is None
    assert "candidates=20 selected_pages=1,2 completed=2 requests=1 cache_hits=1" in messages[0]
    assert ("document_calls=1 document_call_limit=4 stop_reason=completed error_type=none"
            in messages[0])
    assert "error_code=none" in messages[0]


@pytest.mark.parametrize("name", ["VLM_OCR_MAX_FILES_PER_DOCUMENT", "VLM_OCR_MAX_PAGES_PER_FILE"])
def test_config_rejects_more_than_two_files_or_pages(monkeypatch, name):
    from app.domains.monitoring.parsers import vlm_config
    monkeypatch.setattr(vlm_config, "load_dotenv", lambda *a: None)
    monkeypatch.setenv(name, "3")
    with pytest.raises(ValueError, match=name):
        VlmOcrSettings.from_env()


def test_legacy_run_limit_does_not_cap_new_document_budgets(monkeypatch):
    from app.domains.monitoring.parsers import vlm_config
    monkeypatch.setattr(vlm_config, "load_dotenv", lambda *a: None)
    monkeypatch.setenv("VLM_OCR_MAX_CALLS_PER_RUN", "1")
    monkeypatch.delenv("VLM_OCR_MAX_CALLS_PER_DOCUMENT", raising=False)
    assert VlmOcrSettings.from_env().max_calls_per_document == 4


def test_two_single_page_files_exhaust_file_slots_before_call_budget():
    service = ocr(lambda req: response())
    for index in range(3):
        reader = PdfReader(io.BytesIO(scan_pdf(shade=index)))
        pages, warning = service.enhance(reader, ["기존 추출문"])
        if index < 2:
            assert "[표]" in pages[0] and warning is None
        else:
            assert pages == ["기존 추출문"] and "게시글 파일 수 한도" in warning
    assert service.calls == service.files == 2


def test_failed_file_keeps_its_slot_and_does_not_trigger_retries():
    calls = []
    service = ocr(lambda req: calls.append(req) or httpx.Response(429))
    for index in range(3):
        reader = PdfReader(io.BytesIO(scan_pdf(shade=index)))
        pages, warning = service.enhance(reader, ["기존 추출문"])
        assert pages == ["기존 추출문"] and warning
    assert len(calls) == service.calls == service.files == 2


def test_lower_document_call_limit_stops_second_file_without_retry():
    service = ocr(lambda req: response(), max_calls_per_document=1)
    service.enhance(PdfReader(io.BytesIO(scan_pdf())), ["기존 추출문"])
    pages, warning = service.enhance(
        PdfReader(io.BytesIO(scan_pdf(shade=1))), ["기존 추출문"],
    )
    assert pages == ["기존 추출문"] and "게시글 호출 한도" in warning
    assert service.calls == 1 and service.files == 2


def test_cache_hits_still_respect_two_file_limit():
    service = ocr(lambda req: response())
    reader = PdfReader(io.BytesIO(scan_pdf()))
    for _ in range(2):
        assert service.enhance(reader, ["기존 추출문"])[1] is None
    pages, warning = service.enhance(reader, ["기존 추출문"])
    assert pages == ["기존 추출문"] and "게시글 파일 수 한도" in warning
    assert service.calls == 1 and service.files == 2


def test_seven_notices_get_two_files_two_pages_each_across_sources(monkeypatch):
    from app.domains.monitoring.downloaders import AttachmentDownloader
    from app.domains.monitoring.parsers import vlm_config
    from app.domains.monitoring.schemas.collected_document import (
        CollectedAttachment,
        CollectedDocument,
        SourceCollectionResult,
    )

    settings = VlmOcrSettings(api_key="mock-only")
    monkeypatch.setattr(vlm_config.VlmOcrSettings, "from_env", lambda: settings)
    # Keep real PDF page selection, splitting, HTTP transport, registry and downloader.
    monkeypatch.setattr(PdfParser, "_extract_page_text", lambda self, page: "기존 추출문")
    services, calls, contents = [], [], {}

    def create_service(settings, **kwargs):
        service = VlmOcr(
            settings, transport=httpx.MockTransport(lambda req: calls.append(req) or response()),
            **kwargs,
        )
        services.append(service)
        return service

    monkeypatch.setattr(vlm_ocr, "VlmOcr", create_service)
    documents = []
    for notice in range(7):
        attachments = []
        for file_index in range(3):
            writer = PdfWriter()
            for page_index in range(3):
                shade = notice * 9 + file_index * 3 + page_index
                writer.add_page(PdfReader(io.BytesIO(scan_pdf(shade=shade))).pages[0])
            stream = io.BytesIO()
            writer.write(stream)
            url = f"https://test/files/{notice}/{file_index}"
            contents[url] = stream.getvalue()
            attachments.append(CollectedAttachment(
                file_name=f"file-{file_index}.pdf", download_url=url,
            ))
        documents.append(CollectedDocument(
            original_url=f"https://test/notices/{notice}", title=f"공고 {notice}",
            attachments=attachments,
        ))
    sources = [SourceCollectionResult(source_id=1, documents=documents[:2]),
               SourceCollectionResult(source_id=2, documents=documents[2:])]
    downloader = AttachmentDownloader(transport=httpx.MockTransport(
        lambda req: httpx.Response(200, content=contents[str(req.url)]),
    ))
    results = asyncio.run(downloader.enrich_results(sources))
    assert len(calls) == 28 and len(services) == 7
    assert all(service.calls == 4 and service.files == 2 for service in services)
    assert len({service.document_key for service in services}) == 7
    for result in results:
        for document in result.documents:
            for attachment in document.attachments[:2]:
                assert attachment.parse_status == "COMPLETED"
                assert attachment.extracted_text.count("[표]") == 2
                assert "기존 추출문" in attachment.extracted_text  # unselected third page
                assert attachment.error_message is None
            third = document.attachments[2]
            assert third.parse_status == "COMPLETED" and "[표]" not in third.extracted_text
            assert "게시글 파일 수 한도" in third.error_message
    # Reusing a downloader for another collection creates fresh per-notice counters;
    # the successful global page cache avoids repeating paid requests.
    asyncio.run(downloader.enrich_results(sources[:1]))
    assert len(services) == 9 and len(calls) == 28
    assert all(service.calls == 0 and service.files == 2 for service in services[-2:])



def two_distinct_scan_pages():
    writer = PdfWriter()
    for shade in [11, 12]:
        writer.add_page(PdfReader(io.BytesIO(scan_pdf(shade=shade))).pages[0])
    output = io.BytesIO()
    writer.write(output)
    return PdfReader(io.BytesIO(output.getvalue()))


def test_each_page_has_its_own_response_deadline():
    calls = []

    async def slow(request):
        calls.append(request)
        await asyncio.sleep(0.2)
        return response()

    service = ocr(slow, page_timeout_seconds=0.35, timeout_seconds=1.5)
    pages, warning = service.enhance(two_distinct_scan_pages(), ["", ""])
    assert len(calls) == service.calls == 2 and all(pages) and warning is None


def test_second_page_timeout_preserves_first_and_never_retries(parser_logs):
    calls, cancelled = [], []

    async def slow_second(request):
        calls.append(request)
        if len(calls) == 1:
            return response()
        try:
            await asyncio.sleep(1)
        except asyncio.CancelledError:
            cancelled.append(True)
            raise
        return response()

    parser_logs.set_level("INFO", logger=vlm_ocr.__name__)
    service = ocr(slow_second, page_timeout_seconds=0.02, timeout_seconds=0.5)
    pages, warning = service.enhance(
        two_distinct_scan_pages(), ["제출서류 비고", "신청자격 중소기업"],
    )
    assert "[표]" in pages[0] and pages[1] == "신청자격 중소기업"
    assert "페이지 응답 시간 한도" in warning and cancelled
    assert len(calls) == service.calls == 2 and len(vlm_ocr._CACHE) == 1
    assert "stop_reason=page_timeout" in parser_logs.text
    assert "error_code=page_timeout" in parser_logs.text


def test_slow_stream_is_cancelled_by_page_deadline():
    closed = []

    class Stream(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b'{'
            await asyncio.sleep(1)
            yield b'}'

        async def aclose(self):
            closed.append(True)

    service = ocr(lambda req: httpx.Response(200, stream=Stream()),
                  page_timeout_seconds=0.02, timeout_seconds=0.5)
    pages, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf())), ["기존 추출문"])
    assert pages == ["기존 추출문"] and "페이지 응답 시간 한도" in warning
    assert closed and service.calls == 1


def test_network_timeout_is_classified_without_logging_exception_body(parser_logs):
    def timed_out(request):
        raise httpx.ReadTimeout("private source and provider body")

    parser_logs.set_level("INFO", logger=vlm_ocr.__name__)
    service = ocr(timed_out)
    pages, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf())), ["기존 추출문"])
    assert pages == ["기존 추출문"] and "페이지 응답 시간 한도" in warning
    assert "stop_reason=page_timeout" in parser_logs.text and service.calls == 1
    assert "private" not in parser_logs.text


@pytest.mark.parametrize(("name", "value"), [
    ("VLM_OCR_PAGE_TIMEOUT_SECONDS", "0"), ("VLM_OCR_PAGE_TIMEOUT_SECONDS", "301"),
    ("VLM_OCR_TIMEOUT_SECONDS", "0"), ("VLM_OCR_TIMEOUT_SECONDS", "601"),
])
def test_ocr_deadlines_remain_bounded(monkeypatch, name, value):
    from app.domains.monitoring.parsers import vlm_config
    monkeypatch.setattr(vlm_config, "load_dotenv", lambda *a: None)
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError, match=name):
        VlmOcrSettings.from_env()


@pytest.fixture
def parser_logs(caplog):
    from app.domains.monitoring.parsers.vlm_ocr import logger

    logger.addHandler(caplog.handler)
    caplog.set_level("INFO", logger=logger.name)
    try:
        yield caplog
    finally:
        logger.removeHandler(caplog.handler)
