"""Pure-Python compatibility for the small xxhash surface LangGraph uses.

Windows application-control policies can block the native ``_xxhash`` module.
The project only needs deterministic 128-bit identifiers, so this module keeps
the required API available with the Python standard library.
"""

from __future__ import annotations

from hashlib import blake2b
type Buffer = bytes | bytearray | memoryview

__version__ = "pure-python-compat"


class _XXH3_128_Compat:
    """Hash object compatible with the xxh3_128 methods used by dependencies."""

    digest_size = 16
    block_size = 128
    name = "xxh3_128_compat"

    def __init__(self, data: Buffer = b"", *, seed: int = 0) -> None:
        if not 0 <= seed < 1 << 64:
            raise ValueError("seed must be an unsigned 64-bit integer")
        self._hash = blake2b(digest_size=self.digest_size, person=b"GovInsightHash")
        self._hash.update(seed.to_bytes(8, "little"))
        self.update(data)

    def update(self, data: Buffer) -> None:
        self._hash.update(data)

    def digest(self) -> bytes:
        return self._hash.digest()

    def hexdigest(self) -> str:
        return self._hash.hexdigest()

    def copy(self) -> _XXH3_128_Compat:
        duplicate = object.__new__(type(self))
        duplicate._hash = self._hash.copy()
        return duplicate


def xxh3_128(data: Buffer = b"", *, seed: int = 0) -> _XXH3_128_Compat:
    """Return a deterministic pure-Python-compatible 128-bit hash object."""
    return _XXH3_128_Compat(data, seed=seed)


def xxh3_128_hexdigest(data: Buffer, *, seed: int = 0) -> str:
    """Return the deterministic 32-character digest required by LangGraph."""
    return xxh3_128(data, seed=seed).hexdigest()


__all__ = ["xxh3_128", "xxh3_128_hexdigest"]
