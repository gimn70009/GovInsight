import io
import zipfile

import pytest

from app.domains.monitoring.parsers.base import AttachmentParseError
from app.domains.monitoring.parsers.docx_parser import DocxParser
from app.domains.monitoring.parsers.registry import AttachmentParserRegistry
from app.domains.monitoring.parsers.xlsx_parser import XlsxParser

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def package(parts):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, value in parts.items():
            archive.writestr(name, value)
    return output.getvalue()


def docx_bytes():
    return package(
        {
            "word/document.xml": f"""<w:document xmlns:w="{W}"><w:body>
      <w:p><w:r><w:t>신청서류</w:t></w:r></w:p>
      <w:tbl>
        <w:tr><w:tc><w:p><w:r><w:t>구분</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>서류명</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>비고</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr>
          <w:p><w:r><w:t>필수</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>신청서</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>직인</w:t></w:r></w:p><w:p><w:r><w:t>날인</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>
          <w:tc><w:p><w:r><w:t>사업계획서</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>
        <w:tr><w:tc><w:p/></w:tc>
          <w:tc><w:p><w:r><w:t>추천서</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr>
      </w:tbl>
      <w:p><w:del><w:r><w:t>삭제된 제출 조건</w:t></w:r></w:del>
        <w:ins><w:r><w:t>현재 안내</w:t></w:r></w:ins></w:p>
    </w:body></w:document>"""
        }
    )


def xlsx_bytes(sheet, *, extras=None, epoch="0"):
    return package(
        {
            "xl/workbook.xml": f'<workbook xmlns="{S}" xmlns:r="{R}">'
            f'<workbookPr date1904="{epoch}"/>'
            '<sheets><sheet name="제출서류" sheetId="1" r:id="rId1"/>'
            '<sheet name="숨김" state="hidden" sheetId="2" r:id="rId2"/></sheets></workbook>',
            "xl/_rels/workbook.xml.rels": "<Relationships>"
            f'<Relationship Id="rId1" Type="{R}/worksheet" Target="worksheets/sheet7.xml"/>'
            f'<Relationship Id="rId2" Type="{R}/worksheet" Target="worksheets/hidden.xml"/>'
            "</Relationships>",
            "xl/worksheets/sheet7.xml": f'<worksheet xmlns="{S}">{sheet}</worksheet>',
            "xl/worksheets/hidden.xml": f'<worksheet xmlns="{S}"><sheetData><row>'
            '<c r="A1" t="inlineStr">'
            "<is><t>숨긴 예시</t></is></c></row></sheetData></worksheet>",
            **(extras or {}),
        }
    )


def test_docx_preserves_rows_real_merges_blank_cells_and_current_text(tmp_path):
    path = tmp_path / "sample.docx"
    path.write_bytes(docx_bytes())
    text = DocxParser().parse(path).text
    assert text == (
        "신청서류\n[표]\n구분\t서류명\t비고\n필수\t신청서\t직인 ⏎ 날인\n"
        "필수\t사업계획서\t\n\t추천서\t\n[/표]\n현재 안내"
    )
    assert "삭제된" not in text


def test_xlsx_shared_strings_sparse_cells_merges_and_formula_cache(tmp_path):
    sheet = """<sheetData>
      <row r="1"><c r="A1" t="inlineStr"><is><t>서류명</t></is></c>
        <c r="B1" t="inlineStr"><is><t>구분</t></is></c>
        <c r="C1" t="inlineStr"><is><t>수량</t></is></c></row>
      <row r="2"><c r="A2" t="s"><v>0</v></c>
        <c r="B2" t="inlineStr"><is><t>필수</t></is></c><c r="C2"><f>1+1</f><v>2</v></c></row>
      <row r="3"><c r="A3" t="inlineStr"><is><t>신청서</t></is></c><c r="B3"/></row>
      <row r="4"><c r="A4" t="inlineStr"><is><t>추천서</t></is></c>
        <c r="C4"><f>REMOTE()</f></c></row>
      </sheetData><mergeCells><mergeCell ref="B2:B3"/></mergeCells>"""
    shared = "<sst><si><r><t>사업</t></r><r><t>계획서</t></r><rPh><t>발음</t></rPh></si></sst>"
    path = tmp_path / "sample.xlsx"
    path.write_bytes(xlsx_bytes(sheet, extras={"xl/sharedStrings.xml": shared}))
    text = XlsxParser().parse(path).text
    assert "사업계획서\t필수\t2\n신청서\t필수\t\n추천서\t\t(수식 결과 미저장: 원문 확인)" in text
    assert "숨긴" not in text and "발음" not in text and "REMOTE" not in text


@pytest.mark.parametrize(
    ("epoch", "serial", "expected"),
    [
        ("0", "1", "1900-01-01"),
        ("0", "61", "1900-03-01"),
        ("1", "0", "1904-01-01"),
        ("0", "60", "(Excel 날짜 오류: 1900-02-29)"),
    ],
)
def test_xlsx_date_values_do_not_appear_as_unexplained_serials(tmp_path, epoch, serial, expected):
    sheet = f'<sheetData><row><c r="A1" s="1"><v>{serial}</v></c></row></sheetData>'
    styles = '<styleSheet><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>'
    path = tmp_path / "dates.xlsx"
    path.write_bytes(xlsx_bytes(sheet, extras={"xl/styles.xml": styles}, epoch=epoch))
    assert expected in XlsxParser().parse(path).text


@pytest.mark.parametrize("reference", ["XFE999999", "ZZZ9999999"])
def test_xlsx_sparse_huge_coordinates_do_not_allocate_a_huge_grid(tmp_path, reference):
    path = tmp_path / "sparse.xlsx"
    path.write_bytes(
        xlsx_bytes(
            f'<sheetData><row><c r="A1"><v>1</v></c>'
            f'<c r="{reference}"><v>2</v></c></row></sheetData>'
        )
    )
    text = XlsxParser().parse(path).text
    assert len(text) < 150 and "[행" in text


def test_package_rejects_dtd_entities_and_bad_shared_string_reference(tmp_path):
    path = tmp_path / "bad.docx"
    path.write_bytes(
        package(
            {
                "word/document.xml": '<!DOCTYPE document [<!ENTITY x "확대">]>'
                "<document><p><t>&x;</t></p></document>"
            }
        )
    )
    with pytest.raises(AttachmentParseError, match="DTD"):
        DocxParser().parse(path)
    path.write_bytes(xlsx_bytes('<sheetData><row><c r="A1" t="s"><v>-1</v></c></row></sheetData>'))
    with pytest.raises(AttachmentParseError):
        XlsxParser().parse(path)


def test_registry_and_zip_detect_office_content_and_keep_failed_member_isolated(tmp_path):
    registry = AttachmentParserRegistry()
    path = tmp_path / "renamed.pdf"
    path.write_bytes(docx_bytes())
    assert "사업계획서" in registry.find(path.name).parse(path).text
    path = tmp_path / "batch.zip"
    path.write_bytes(package({"공고.docx": docx_bytes(), "오류.xlsx": b"broken"}))
    result = registry.find(path.name).parse(path)
    assert [item["status"] for item in result.archive_entries] == ["COMPLETED", "FAILED"]
    assert "사업계획서" in result.text


def test_policy_blocked_parser_import_is_local_to_that_file(monkeypatch, tmp_path):
    from app.domains.monitoring.parsers import registry as module

    real_import = module.import_module

    def blocked_import(name):
        if name.endswith("pdf_parser"):
            raise ImportError("DLL load failed: application control policy")
        return real_import(name)

    monkeypatch.setattr(module, "import_module", blocked_import)
    registry = AttachmentParserRegistry()  # Must not load the blocked reader here.
    path = tmp_path / "sample.pdf"
    path.write_bytes(b"%PDF-1.4")
    with pytest.raises(AttachmentParseError, match="파서를 불러올"):
        registry.find(path.name).parse(path)
    path.write_bytes(docx_bytes())
    assert "사업계획서" in registry.find("sample.docx").parse(path).text


@pytest.mark.parametrize(
    ("row_history", "cell_history"),
    [
        ("", '<w:gridSpan w:val="2"/>'),
        ("", '<w:vMerge/>'),
        ("", '<w:hMerge w:val="restart"/>'),
        ('<w:gridBefore w:val="1"/>', ""),
    ],
)
def test_docx_ignores_previous_table_properties(tmp_path, row_history, cell_history):
    document = f"""<w:document xmlns:w="{W}"><w:body><w:tbl>
      <w:tr><w:tc><w:p><w:r><w:t>서류명</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>구분</w:t></w:r></w:p></w:tc></w:tr>
      <w:tr><w:trPr><w:trPrChange><w:trPr>{row_history}</w:trPr>
        </w:trPrChange></w:trPr>
        <w:tc><w:tcPr><w:tcPrChange><w:tcPr>{cell_history}</w:tcPr>
          </w:tcPrChange></w:tcPr><w:p><w:r><w:t>사업계획서</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>필수</w:t></w:r></w:p></w:tc></w:tr>
      </w:tbl></w:body></w:document>"""
    path = tmp_path / "tracked.docx"
    path.write_bytes(package({"word/document.xml": document}))
    assert DocxParser().parse(path).text == "[표]\n서류명\t구분\n사업계획서\t필수\n[/표]"
