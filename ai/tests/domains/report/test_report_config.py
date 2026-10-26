"""Configuration bounds must keep report generation inside the backend lease."""

import pytest

from app.domains.report import config
from app.domains.report.config import ReportBriefSettings


@pytest.fixture
def isolated_environment(monkeypatch):
    monkeypatch.setattr(config, "load_dotenv", lambda *_: None)
    for name in tuple(config.os.environ):
        if name.startswith("REPORT_BRIEF_"):
            monkeypatch.delenv(name)
    monkeypatch.setenv("OPENAI_API_KEY", "test-placeholder")


def test_existing_base_configuration_enables_bounded_scaling(isolated_environment, monkeypatch):
    monkeypatch.setenv("REPORT_BRIEF_TOTAL_TIMEOUT_SECONDS", "60")

    settings = ReportBriefSettings.from_env()

    assert settings.timeout_seconds == 30
    assert settings.total_timeout_seconds == 60
    assert settings.max_total_timeout_seconds == 90
    assert settings.batch_timeout_seconds(1) == 60
    assert settings.batch_timeout_seconds(9) == 90
    assert settings.batch_timeout_seconds(1000) == 90


def test_explicit_maximum_can_preserve_a_fixed_deadline(isolated_environment, monkeypatch):
    monkeypatch.setenv("REPORT_BRIEF_TOTAL_TIMEOUT_SECONDS", "60")
    monkeypatch.setenv("REPORT_BRIEF_MAX_TOTAL_TIMEOUT_SECONDS", "60")

    settings = ReportBriefSettings.from_env()

    assert settings.batch_timeout_seconds(1) == settings.batch_timeout_seconds(1000) == 60


@pytest.mark.parametrize("name", [
    "REPORT_BRIEF_TIMEOUT_SECONDS",
    "REPORT_BRIEF_TOTAL_TIMEOUT_SECONDS",
    "REPORT_BRIEF_MAX_TOTAL_TIMEOUT_SECONDS",
])
@pytest.mark.parametrize("value", ["0", "-1", "nan", "inf"])
def test_invalid_timeouts_are_rejected(isolated_environment, monkeypatch, name, value):
    monkeypatch.setenv(name, value)

    with pytest.raises(ValueError, match="positive finite"):
        ReportBriefSettings.from_env()


def test_base_cannot_exceed_maximum(isolated_environment, monkeypatch):
    monkeypatch.setenv("REPORT_BRIEF_MAX_TOTAL_TIMEOUT_SECONDS", "59")

    with pytest.raises(ValueError, match="base timeout"):
        ReportBriefSettings.from_env()


def test_maximum_reserves_time_for_backend_result_delivery(isolated_environment, monkeypatch):
    monkeypatch.setenv("REPORT_BRIEF_MAX_TOTAL_TIMEOUT_SECONDS", "120")

    with pytest.raises(ValueError, match="90 seconds"):
        ReportBriefSettings.from_env()
