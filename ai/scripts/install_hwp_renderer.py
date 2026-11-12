"""Install the pinned MIT-licensed JS/WASM data files without executing npm hooks."""

import hashlib
import io
import sys
import tarfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.domains.monitoring.parsers.hwp_renderer_assets import (  # noqa: E402
    RUNTIME,
    TARBALL,
    TARBALL_SHA512,
    verified_assets,
)


def main():
    with urllib.request.urlopen(TARBALL, timeout=30) as response:
        data = response.read(20 * 1024 * 1024 + 1)
    if len(data) > 20 * 1024 * 1024 or hashlib.sha512(data).hexdigest() != TARBALL_SHA512:
        raise ValueError("Pinned renderer download failed integrity verification")
    RUNTIME.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for name in ("rhwp.js", "rhwp_bg.wasm", "LICENSE", "package.json"):
            member = archive.getmember("package/" + name)
            if not member.isfile() or member.size > 16 * 1024 * 1024:
                raise ValueError("Invalid renderer archive")
            with archive.extractfile(member) as source:
                contents = source.read()
            target = RUNTIME / name
            temporary = target.with_suffix(target.suffix + ".tmp")
            temporary.write_bytes(contents)
            temporary.replace(target)
    verified_assets()
    print("Installed @rhwp/core 0.8.7 JS/WASM. No native extension or install hook executed.")


if __name__ == "__main__":
    main()
