import pytest


@pytest.fixture(autouse=True)
def disable_paid_ocr(monkeypatch):
    # A developer's real .env must never turn parser tests into paid API calls.
    monkeypatch.setenv("VLM_OCR_ENABLED", "false")
