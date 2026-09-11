import json

import pytest

from app.domains.analysis.agent import _analysis_inputs, _strategy_instruction
from app.domains.analysis.schemas.request import AnalysisChangeType, AnalysisDocumentRequest
from app.domains.analysis.tools import AnalysisToolContext, compare_with_previous_version
from app.domains.analysis.version_comparison import MAX_SOURCE_CHARS


def attachment(id, text, name="공고문.pdf"):
    return {"attachmentId": id, "fileName": name, "extractedText": text}


def document(old_files=None, new_files=None, before="현재 본문", after="현재 본문"):
    return AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "changeType": "UPDATED_DOCUMENT",
            "organizationName": "기관",
            "boardName": "공고",
            "title": "공고",
            "contentText": after,
            "originalUrl": "https://example.org/notice",
            "attachments": new_files or [],
            "previousVersion": {
                "versionId": 2,
                "title": "공고",
                "contentText": before,
                "attachments": old_files,
            },
        }
    )


def compare(doc, budget=10_000):
    return json.loads(compare_with_previous_version(AnalysisToolContext(doc, budget)))


def test_attachment_only_change_reaches_analysis_input_with_before_and_after():
    doc = document([attachment(10, "기한: 9월 20일")], [attachment(20, "기한: 9월 30일")])
    result = compare(doc)
    assert result["contentDiff"] == ""
    file = result["attachmentComparison"]["files"][0]
    assert file["status"] == "TEXT_CHANGED"
    assert "-기한: 9월 20일" in file["contentDiff"]
    assert "+기한: 9월 30일" in file["contentDiff"]
    assert file["previousAttachmentIds"] == [10]
    assert file["currentAttachmentIds"] == [20]
    assert result["complete"] is True
    sections, tools = _analysis_inputs(AnalysisToolContext(doc, 10_000))
    assert "attachmentComparison" in "\n".join(sections)
    assert "-기한: 9월 20일" in "\n".join(sections)
    assert tools.count("compare_previous_version") == 1


@pytest.mark.parametrize(
    "old,new,status",
    [
        ([attachment(1, "동일 본문")], [attachment(2, "동일 본문")], "TEXT_UNCHANGED"),
        ([], [attachment(2, "추가 서류")], "ADDED"),
        ([attachment(1, "삭제 서류")], [], "REMOVED"),
        ([attachment(1, None)], [attachment(2, "현재 내용")], "UNCOMPARABLE"),
        ([attachment(1, "이전 내용")], [attachment(2, None)], "UNCOMPARABLE"),
        ([attachment(1, "")], [attachment(2, " ")], "UNCOMPARABLE"),
        ([attachment(1, "이전"), attachment(2, "중복")], [attachment(3, "현재")], "AMBIGUOUS_NAME"),
        ([attachment(1, "이전")], [attachment(2, "현재"), attachment(3, "중복")], "AMBIGUOUS_NAME"),
    ],
)
def test_inventory_changes_and_missing_or_ambiguous_text_are_distinct(old, new, status):
    result = compare(document(old, new))["attachmentComparison"]
    file = result["files"][0]
    assert file["status"] == status
    if status in {"UNCOMPARABLE", "AMBIGUOUS_NAME"}:
        assert result["complete"] is False
        assert file["available"] is False
        assert file["contentDiff"] == ""
    else:
        assert result["complete"] is True


def test_missing_inventory_is_not_an_empty_inventory_and_older_requests_still_parse():
    doc = document(new_files=[attachment(2, "현재 파일")])
    value = doc.model_dump(by_alias=True)
    del value["previousVersion"]["attachments"]
    result = compare(AnalysisDocumentRequest.model_validate(value))
    assert result["attachmentComparison"]["available"] is False
    assert result["attachmentComparison"]["files"] == []
    assert result["complete"] is False
    assert compare(document([], []))["attachmentComparison"]["complete"] is True


def test_renamed_files_are_not_guessed_to_be_revisions_of_one_another():
    result = compare(
        document([attachment(1, "이전", "이전.pdf")], [attachment(2, "현재", "현재.pdf")])
    )
    assert {file["status"] for file in result["attachmentComparison"]["files"]} == {
        "ADDED",
        "REMOVED",
    }


def test_missing_previous_version_and_unreadable_body_do_not_claim_no_change():
    doc = document([attachment(1, "이전")], [attachment(2, "현재")], before=None)
    result = compare(doc)
    assert result["bodyComparison"]["available"] is False
    assert result["bodyComparison"]["textChanged"] is None
    assert result["attachmentComparison"]["files"][0]["status"] == "TEXT_CHANGED"
    assert result["complete"] is False
    assert compare(doc.model_copy(update={"previous_version": None}))["available"] is False


def test_middle_changes_survive_a_small_output_budget_in_body_and_archive_text():
    before = "공통 줄\n" * 1000 + "ZIP 내부: 신청 자격 중소기업\n" + "마지막 줄\n" * 1000
    after = before.replace("중소기업", "중견기업")
    result = compare(
        document(
            [attachment(1, before, "자료.zip")], [attachment(2, after, "자료.zip")], before, after
        ),
        budget=2000,
    )
    assert "-ZIP 내부: 신청 자격 중소기업" in result["contentDiff"]
    assert (
        "+ZIP 내부: 신청 자격 중견기업" in result["attachmentComparison"]["files"][0]["contentDiff"]
    )
    assert result["complete"] is True


def test_comparison_limits_are_explicit_and_changed_files_get_priority():
    old = [attachment(i + 1, "동일", f"{i:02d}.pdf") for i in range(21)]
    new = [attachment(i + 101, "동일", f"{i:02d}.pdf") for i in range(21)]
    old.append(attachment(99, "이전 조건\n" * 100, "수정.pdf"))
    new.append(attachment(199, "새로운 조건\n" * 100, "수정.pdf"))
    result = compare(document(old, new, "이전 본문\n" * 100, "현재 본문\n" * 100), budget=100)
    files = result["attachmentComparison"]["files"]
    assert files[0]["fileName"] == "수정.pdf"
    assert result["attachmentComparison"]["omittedFileCount"] == 2
    assert result["complete"] is False
    assert result["bodyComparison"]["diffTruncated"] is True
    assert len(result["contentDiff"]) + sum(len(file["contentDiff"]) for file in files) <= 100


def test_oversized_source_is_not_reported_as_fully_compared():
    prefix = "본문 " * (MAX_SOURCE_CHARS // 3 + 1)
    result = compare(document([], [], prefix + "이전", prefix + "현재"))
    assert result["bodyComparison"]["textChanged"] is True
    assert result["bodyComparison"]["sourceTruncated"] is True
    assert result["complete"] is False


def test_only_updated_notice_uses_change_first_instructions_and_uncertainty_rules():
    updated = _strategy_instruction(AnalysisChangeType.UPDATED_DOCUMENT)
    assert "변경 전 → 변경 후 → 우리 회사에 미치는 영향" in updated
    assert "이전 첨부 목록 미전달을 첨부 없음으로 해석하지 않습니다" in updated
    assert "임의로 동일 파일의 개정 전후로 연결하지 않습니다" in updated
    assert "REVIEW_REQUIRED" in updated
    for kind in (AnalysisChangeType.NEW_DOCUMENT, AnalysisChangeType.UNCHANGED_DOCUMENT):
        assert "변경 중심 해석 규칙" not in _strategy_instruction(kind)
        doc = document().model_copy(update={"change_type": kind})
        _, tools = _analysis_inputs(AnalysisToolContext(doc, 10_000))
        assert "compare_previous_version" not in tools


def test_unchanged_files_do_not_take_text_budget_from_the_changed_attachment():
    old = [attachment(i + 1, "동일", f"{i:02d}.pdf") for i in range(10)]
    new = [attachment(i + 101, "동일", f"{i:02d}.pdf") for i in range(10)]
    old.append(attachment(99, "이전 조건입니다. " * 20, "수정.pdf"))
    new.append(attachment(199, "새로운 조건입니다. " * 20, "수정.pdf"))
    result = compare(document(old, new), budget=1000)
    file = result["attachmentComparison"]["files"][0]
    assert file["status"] == "TEXT_CHANGED"
    assert "새로운 조건입니다." in file["contentDiff"]
    assert file["diffTruncated"] is False
    assert result["complete"] is True
