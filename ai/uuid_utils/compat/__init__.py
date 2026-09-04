"""Pure-Python implementation of the ``uuid_utils.compat.uuid7`` API."""

from __future__ import annotations

import secrets
import time
from threading import Lock
from uuid import UUID

_COUNTER_BITS = 41
_COUNTER_MAX = (1 << _COUNTER_BITS) - 1
_STATE_LOCK = Lock()
_last_timestamp_ms = -1
_counter = 0


def uuid7(timestamp: int | float | None = None, nanos: int | None = None) -> UUID:
    """Return a monotonic RFC 9562 UUIDv7 as a standard-library UUID."""
    if timestamp is None:
        timestamp_ns = time.time_ns()
    else:
        timestamp_ns = int(timestamp * 1_000_000_000) + (nanos or 0)
    timestamp_ms = timestamp_ns // 1_000_000

    global _counter, _last_timestamp_ms
    with _STATE_LOCK:
        if timestamp_ms == _last_timestamp_ms:
            _counter += 1
            if _counter > _COUNTER_MAX:
                timestamp_ms += 1
                _counter = secrets.randbits(_COUNTER_BITS)
        else:
            _counter = secrets.randbits(_COUNTER_BITS)
        _last_timestamp_ms = timestamp_ms
        counter = _counter

    counter_hi = counter >> 30
    counter_lo = counter & ((1 << 30) - 1)
    value = (
        ((timestamp_ms & 0xFFFF_FFFF_FFFF) << 80)
        | (7 << 76)
        | (counter_hi << 64)
        | (0b10 << 62)
        | (counter_lo << 32)
        | secrets.randbits(32)
    )
    return UUID(int=value)


__all__ = ["uuid7"]
