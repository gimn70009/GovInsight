from __future__ import annotations

from uuid import UUID

from uuid_utils import compat


def test_uuid7_generates_valid_time_ordered_uuid(monkeypatch) -> None:
    monkeypatch.setattr(compat.time, "time_ns", lambda: 1_777_777_777_123_456_789)

    first = compat.uuid7()
    second = compat.uuid7()

    assert isinstance(first, UUID)
    assert first.version == 7
    assert first.variant == "specified in RFC 4122"
    assert first.int < second.int
    assert first.int >> 80 == 1_777_777_777_123


def test_uuid7_accepts_uuid_utils_timestamp_arguments() -> None:
    result = compat.uuid7(timestamp=1_777_777_777, nanos=123_456_789)

    assert result.version == 7
    assert result.int >> 80 == 1_777_777_777_123
