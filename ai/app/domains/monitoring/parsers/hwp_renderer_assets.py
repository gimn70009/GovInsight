"""Pinned, data-only WebAssembly runtime; installed explicitly, never at parse time."""

import hashlib
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[4]
VERSION = "0.8.7"
RUNTIME = AI_ROOT / ".hwp-renderer" / VERSION
TARBALL = f"https://registry.npmjs.org/@rhwp/core/-/core-{VERSION}.tgz"
TARBALL_SHA512 = (
    "aa036963d69c942bf2f43edc3e7a6ea65bb8da450564208f85d8c04b91c1b28e19e1"
    "77871b7b9c85875495e401a1ab3fcc4bb07c9ec7e3fba57a9a9d2ff2d70c"
)
ASSET_HASHES = {
    "rhwp.js": "70cde06a369fa7fd4fc8bc8f3d6acaee158596ba1a116a2c72159002b0b5654e",
    "rhwp_bg.wasm": "54cf95014312e605c51b98eb9021247ea79ef2bbd0a7080e11c011d0246e3989",
}


def verified_assets() -> dict[str, bytes]:
    result = {}
    for name, digest in ASSET_HASHES.items():
        data = (RUNTIME / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("HWP renderer asset checksum mismatch")
        result[name] = data
    return result
