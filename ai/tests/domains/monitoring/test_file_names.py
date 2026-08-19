import pytest

from app.domains.monitoring.file_names import (
    attachment_file_extension,
    normalize_attachment_file_name,
)


@pytest.mark.parametrize(
    ("raw_name", "expected_name"),
    [
        ("공고문.hwpx", "공고문.hwpx"),
        ("공고문.hwpx [123 KB]", "공고문.hwpx"),
        ("공고문.hwpx (1.5 MB)", "공고문.hwpx"),
        ("공고문.hwpx,", "공고문.hwpx"),
        ("공고문.HWPX\u200b ", "공고문.HWPX"),
    ],
)
def test_normalizes_attachment_file_name(raw_name: str, expected_name: str) -> None:
    assert normalize_attachment_file_name(raw_name) == expected_name


@pytest.mark.parametrize(
    "raw_name",
    [
        "공고문.hwpx",
        "공고문.hwpx [123 KB]",
        "공고문.hwpx,",
        "공고문.HWPX\u200b",
    ],
)
def test_extracts_hwpx_extension_from_decorated_file_name(raw_name: str) -> None:
    assert attachment_file_extension(raw_name) == ".hwpx"
