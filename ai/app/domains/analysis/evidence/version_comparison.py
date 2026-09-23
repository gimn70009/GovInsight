"""Bounded source comparisons; business significance is left to the analysis model."""

from collections import defaultdict
from difflib import unified_diff

from app.domains.analysis.schemas.request import AnalysisDocumentRequest

MAX_SOURCE_CHARS = 200_000
MAX_ATTACHMENT_NAMES = 20


def _text_diff(
    before: str | None, after: str | None, budget: int, *, inventory_change: bool = False
) -> dict:
    before = (before or "").strip()
    after = (after or "").strip()
    available = bool(before and after) or (inventory_change and bool(before or after))
    result = {
        "available": available,
        "textChanged": before != after if available else None,
        "contentDiff": "",
        "sourceTruncated": False,
        "diffTruncated": False,
    }
    if not available:
        result["reason"] = "missing_extracted_text"
        return result
    if before == after:
        return result
    # Compare before clipping the diff so changes in the middle of long inputs survive.
    result["sourceTruncated"] = max(len(before), len(after)) > MAX_SOURCE_CHARS
    old_lines = before[:MAX_SOURCE_CHARS].splitlines()
    new_lines = after[:MAX_SOURCE_CHARS].splitlines()
    prefix = 0
    while prefix < min(len(old_lines), len(new_lines)) and old_lines[prefix] == new_lines[prefix]:
        prefix += 1
    suffix = 0
    while (
        suffix < min(len(old_lines), len(new_lines)) - prefix
        and old_lines[-1 - suffix] == new_lines[-1 - suffix]
    ):
        suffix += 1
    # Repeated headers/footers otherwise confuse difflib's autojunk matching and hide
    # the replacement behind a huge deletion. Keep only two context lines at each edge.
    start = max(0, prefix - 2)
    old_end = min(len(old_lines), len(old_lines) - suffix + 2)
    new_end = min(len(new_lines), len(new_lines) - suffix + 2)
    result["excerptStartLine"] = start + 1
    chunks = []
    used = 0
    for line in unified_diff(
        old_lines[start:old_end],
        new_lines[start:new_end],
        fromfile="previous",
        tofile="current",
        lineterm="",
        n=2,
    ):
        chunk = ("\n" if chunks else "") + line
        room = max(0, budget - used)
        chunks.append(chunk[:room])
        used += min(len(chunk), room)
        if len(chunk) > room:
            result["diffTruncated"] = True
            break
    result["contentDiff"] = "".join(chunks)
    return result


def _attachment_diff(previous, current, budget: int) -> dict:
    if previous is None:
        return {
            "available": False,
            "complete": False,
            "reason": "previous_attachment_inventory_unavailable",
            "files": [],
            "omittedFileCount": 0,
        }
    old = defaultdict(list)
    new = defaultdict(list)
    for attachment in previous:
        old[attachment.file_name].append(attachment)
    for attachment in current:
        new[attachment.file_name].append(attachment)
    names = sorted(old.keys() | new.keys())

    # Do not let unchanged files consume the evidence budget ahead of changed ones.
    def unchanged(name):
        return (
            len(old[name]) == len(new[name]) == 1
            and bool((old[name][0].extracted_text or "").strip())
            and (old[name][0].extracted_text or "").strip()
            == (new[name][0].extracted_text or "").strip()
        )

    names.sort(key=unchanged)
    selected = names[:MAX_ATTACHMENT_NAMES]
    per_file = max(0, budget) // max(1, sum(not unchanged(name) for name in selected))
    files = []
    complete = len(names) <= MAX_ATTACHMENT_NAMES
    for name in selected:
        before, after = old[name], new[name]
        record = {
            "fileName": name,
            "previousAttachmentIds": [item.attachment_id for item in before],
            "currentAttachmentIds": [item.attachment_id for item in after],
        }
        if len(before) > 1 or len(after) > 1:
            record.update(
                status="AMBIGUOUS_NAME",
                available=False,
                reason="duplicate_file_name",
                contentDiff="",
            )
            complete = False
        else:
            old_text = before[0].extracted_text if before else None
            new_text = after[0].extracted_text if after else None
            detail = _text_diff(
                old_text, new_text, per_file, inventory_change=not before or not after
            )
            status = (
                "ADDED"
                if not before
                else "REMOVED"
                if not after
                else "UNCOMPARABLE"
                if not detail["available"]
                else "TEXT_CHANGED"
                if detail["textChanged"]
                else "TEXT_UNCHANGED"
            )
            record.update(status=status, **detail)
            record["previousTextAvailable"] = bool((old_text or "").strip())
            record["currentTextAvailable"] = bool((new_text or "").strip())
            if not detail["available"] or detail["sourceTruncated"] or detail["diffTruncated"]:
                complete = False
        files.append(record)
    return {
        "available": True,
        "complete": complete,
        "files": files,
        "omittedFileCount": len(names) - len(selected),
    }


def compare_versions(document: AnalysisDocumentRequest, budget: int) -> dict:
    previous = document.previous_version
    if previous is None:
        return {"available": False, "complete": False, "reason": "previous_version_unavailable"}
    budget = max(0, budget)
    body_budget = budget // 2 if previous.attachments or document.attachments else budget
    body = _text_diff(previous.content_text, document.content_text, body_budget)
    attachments = _attachment_diff(
        previous.attachments, document.attachments, budget - len(body["contentDiff"])
    )
    return {
        "available": True,
        "complete": (
            body["available"]
            and not body["sourceTruncated"]
            and not body["diffTruncated"]
            and attachments["complete"]
        ),
        "previousVersionId": previous.version_id,
        "titleChanged": previous.title.strip() != document.title.strip(),
        "previousTitle": previous.title,
        "currentTitle": document.title,
        "contentDiff": body["contentDiff"],
        "bodyComparison": {key: value for key, value in body.items() if key != "contentDiff"},
        "attachmentComparison": attachments,
    }
