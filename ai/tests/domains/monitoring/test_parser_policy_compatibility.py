import subprocess
import sys
from pathlib import Path

from tests.domains.monitoring.test_office_parsers import docx_bytes, xlsx_bytes


def test_app_health_and_office_parsing_work_with_new_native_loads_blocked():
    # Establish the application's existing runtime first. Then emulate a policy
    # that rejects any additional extension/DLL load during the new file readers.
    script = r'''
import importlib.machinery
import tempfile
from pathlib import Path
from fastapi.testclient import TestClient
from app.main import app
from app.domains.monitoring.parsers.registry import AttachmentParserRegistry

with TestClient(app) as client:
    assert client.get("/health").json() == {"status": "UP"}
    registry = AttachmentParserRegistry()
    documents = DOCUMENT_HEX
    with tempfile.TemporaryDirectory() as directory:
        def reject(self, spec):
            raise ImportError("Test policy blocks additional native extension: " + spec.name)
        importlib.machinery.ExtensionFileLoader.create_module = reject
        for name, contents in documents.items():
            path = Path(directory) / name
            path.write_bytes(bytes.fromhex(contents))
            assert "신청서" in registry.find(name).parse(path).text
        assert client.get("/health").status_code == 200
'''
    documents = {
        "sample.docx": docx_bytes().hex(),
        "sample.xlsx": xlsx_bytes(
            '<sheetData><row><c r="A1" t="inlineStr"><is><t>신청서</t></is></c></row></sheetData>',
        ).hex(),
    }
    script = script.replace("DOCUMENT_HEX", repr(documents))
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=Path(__file__).resolve().parents[3],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr
