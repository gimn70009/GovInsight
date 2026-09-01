from __future__ import annotations

import xxhash


def test_xxh3_128_compat_returns_stable_128_bit_digest() -> None:
    first = xxhash.xxh3_128(b"gov-insight").digest()
    second = xxhash.xxh3_128(b"gov-insight").digest()

    assert first == second
    assert len(first) == 16


def test_xxh3_128_hexdigest_matches_incremental_hash() -> None:
    incremental = xxhash.xxh3_128()
    incremental.update(b"gov-")
    incremental.update(b"insight")

    digest = xxhash.xxh3_128_hexdigest(b"gov-insight")

    assert digest == incremental.hexdigest()
    assert len(digest) == 32


def test_xxh3_128_seed_changes_digest() -> None:
    assert xxhash.xxh3_128_hexdigest(b"same", seed=1) != xxhash.xxh3_128_hexdigest(
        b"same", seed=2
    )
