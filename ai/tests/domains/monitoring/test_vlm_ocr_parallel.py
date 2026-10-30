"""Concurrency and cancellation contracts for bounded, mocked VLM OCR."""

import asyncio
import io
import threading
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from pypdf import PdfReader, PdfWriter

from app.domains.monitoring.parsers import vlm_ocr
from tests.domains.monitoring.test_vlm_ocr import (
    ocr,
    response,
    scan_pdf,
    two_distinct_scan_pages,
)


@pytest.fixture(autouse=True)
def isolated_ocr_state(monkeypatch):
    with vlm_ocr._CACHE_LOCK:
        vlm_ocr._CACHE.clear()
    monkeypatch.setattr(vlm_ocr, "_ACTIVE", threading.BoundedSemaphore(2))
    monkeypatch.setattr(vlm_ocr, "_REQUESTS", threading.BoundedSemaphore(2))
    yield
    with vlm_ocr._CACHE_LOCK:
        vlm_ocr._CACHE.clear()


def _assert_request_slots_released():
    acquired = 0
    try:
        for _ in range(2):
            assert vlm_ocr._REQUESTS.acquire(blocking=False)
            acquired += 1
        assert not vlm_ocr._REQUESTS.acquire(blocking=False)
    finally:
        for _ in range(acquired):
            vlm_ocr._REQUESTS.release()


def _reader_with_shades(first):
    writer = PdfWriter()
    for shade in (first, first + 1):
        writer.add_page(PdfReader(io.BytesIO(scan_pdf(shade=shade))).pages[0])
    data = io.BytesIO()
    writer.write(data)
    return PdfReader(io.BytesIO(data.getvalue()))


def test_selected_pages_start_before_either_response_finishes():
    entered = []
    both_started = asyncio.Event()

    async def handle(request):
        entered.append(request)
        if len(entered) == 2:
            both_started.set()
        await both_started.wait()
        return response()

    service = ocr(handle, page_timeout_seconds=1, timeout_seconds=2)
    pages, warning = service.enhance(two_distinct_scan_pages(), ["", ""])

    assert both_started.is_set()
    assert len(entered) == service.calls == 2
    assert all("[표]" in page for page in pages)
    assert warning is None
    _assert_request_slots_released()


@pytest.mark.parametrize("failure", ["http_error", "page_timeout"])
def test_failed_first_page_preserves_successful_peer(failure):
    entered, cancelled = [], []
    both_started = asyncio.Event()

    async def handle(request):
        entered.append(request)
        position = len(entered)
        if position == 2:
            both_started.set()
        await both_started.wait()
        if position == 1:
            if failure == "http_error":
                return httpx.Response(429)
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled.append(True)
                raise
        return response()

    service = ocr(handle, page_timeout_seconds=0.05, timeout_seconds=1)
    pages, warning = service.enhance(two_distinct_scan_pages(), ["", ""])

    assert pages[0] == "" and "[표]" in pages[1]
    assert len(entered) == service.calls == 2 and warning
    assert len(vlm_ocr._CACHE) == 1
    assert bool(cancelled) == (failure == "page_timeout")
    _assert_request_slots_released()


def test_file_deadline_keeps_completed_page_and_cancels_peer():
    entered, cancelled = [], []
    both_started = asyncio.Event()

    async def handle(request):
        entered.append(request)
        position = len(entered)
        if position == 2:
            both_started.set()
        await both_started.wait()
        if position == 2:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled.append(True)
                raise
        return response()

    service = ocr(handle, page_timeout_seconds=1, timeout_seconds=0.05)
    pages, warning = service.enhance(two_distinct_scan_pages(), ["", ""])

    assert "[표]" in pages[0] and pages[1] == ""
    assert service.calls == 2 and cancelled == [True]
    assert "파일 전체 시간 한도" in warning
    assert len(vlm_ocr._CACHE) == 1
    _assert_request_slots_released()


def test_process_request_limit_spans_worker_threads_and_event_loops(monkeypatch):
    waiting_for_slot = threading.Event()
    first_two_started = threading.Event()
    release_responses = threading.Event()
    start_workers = threading.Barrier(2)
    state_lock = threading.Lock()
    state = {"active": 0, "peak": 0, "requests": 0}
    loop_ids = set()

    class ObservedSemaphore:
        def __init__(self):
            self.inner = threading.BoundedSemaphore(2)

        def acquire(self, blocking=True):
            acquired = self.inner.acquire(blocking=blocking)
            if not acquired:
                waiting_for_slot.set()
            return acquired

        def release(self):
            self.inner.release()

    monkeypatch.setattr(vlm_ocr, "_REQUESTS", ObservedSemaphore())

    async def handle(request):
        with state_lock:
            loop_ids.add(id(asyncio.get_running_loop()))
            state["requests"] += 1
            state["active"] += 1
            state["peak"] = max(state["peak"], state["active"])
            if state["active"] == 2:
                first_two_started.set()
        try:
            while not release_responses.is_set():
                await asyncio.sleep(0.001)
            return response()
        finally:
            with state_lock:
                state["active"] -= 1

    services = [ocr(handle, page_timeout_seconds=4, timeout_seconds=5) for _ in range(2)]
    readers = [_reader_with_shades(first) for first in (10, 20)]

    def run(index):
        start_workers.wait(timeout=3)
        return services[index].enhance(readers[index], ["", ""])

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(run, index) for index in range(2)]
        try:
            assert first_two_started.wait(timeout=3)
            assert waiting_for_slot.wait(timeout=3)
            with state_lock:
                assert state["active"] == state["peak"] == 2
        finally:
            release_responses.set()
        results = [future.result(timeout=5) for future in futures]

    assert state == {"active": 0, "peak": 2, "requests": 4}
    assert len(loop_ids) == 2
    assert all(service.calls == 2 for service in services)
    assert all(all(pages) and warning is None for pages, warning in results)
    _assert_request_slots_released()


def test_file_deadline_cancels_queued_pages_without_spending_call_budget():
    service = ocr(lambda request: pytest.fail("queued pages must not be requested"),
                  page_timeout_seconds=1, timeout_seconds=0.02)
    vlm_ocr._REQUESTS.acquire()
    vlm_ocr._REQUESTS.acquire()
    try:
        pages, warning = service.enhance(two_distinct_scan_pages(), ["", ""])
        assert pages == ["", ""] and "파일 전체 시간 한도" in warning
        assert service.calls == 0 and service.files == 1
        assert not vlm_ocr._CACHE
    finally:
        vlm_ocr._REQUESTS.release()
        vlm_ocr._REQUESTS.release()
    _assert_request_slots_released()


def test_cached_pages_complete_while_all_request_slots_are_busy():
    reader = PdfReader(io.BytesIO(scan_pdf()))
    warm = ocr(lambda request: response())
    expected, _ = warm.enhance(reader, [""])
    service = ocr(lambda request: pytest.fail("cached page must not be requested"),
                  timeout_seconds=0.02)
    vlm_ocr._REQUESTS.acquire()
    vlm_ocr._REQUESTS.acquire()
    try:
        pages, warning = service.enhance(reader, [""])
        assert pages == expected and warning is None
        assert service.calls == 0
    finally:
        vlm_ocr._REQUESTS.release()
        vlm_ocr._REQUESTS.release()
    _assert_request_slots_released()


@pytest.mark.parametrize("success", [True, False])
def test_identical_selected_pages_share_success_or_failure_without_retry(success):
    entered = []

    async def handle(request):
        entered.append(request)
        await asyncio.sleep(0)
        return response() if success else httpx.Response(429)

    service = ocr(handle)
    pages, warning = service.enhance(PdfReader(io.BytesIO(scan_pdf(pages=2))), ["", ""])

    assert len(entered) == service.calls == 1
    if success:
        assert pages[0] == pages[1] and "[표]" in pages[0] and warning is None
    else:
        assert pages == ["", ""] and warning
        assert not vlm_ocr._CACHE
    _assert_request_slots_released()


def test_parallel_pages_cannot_race_past_single_call_budget():
    entered = []

    async def handle(request):
        entered.append(request)
        await asyncio.sleep(0)
        return response()

    service = ocr(handle, max_calls_per_document=1)
    pages, warning = service.enhance(two_distinct_scan_pages(), ["", ""])

    assert len(entered) == service.calls == 1 and service.files == 1
    assert sum(bool(page) for page in pages) == 1
    assert "게시글 호출 한도" in warning
    _assert_request_slots_released()
