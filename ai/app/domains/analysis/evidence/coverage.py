"""Local checks of unread keyword-bearing source units, never a semantic verdict."""

import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass

from app.domains.analysis.evidence.selection import range_covered, source_units

# Specific markers avoid treating generic references to a project as eligibility facts.
_MARKERS = (
    ("exclusion", re.compile(
        r"지원\s*제외|신청\s*제한|참여\s*제한|제외\s*대상|지원\s*불가|"
        r"신청할\s*수\s*없|참여할\s*수\s*없"
    )),
    ("documents", re.compile(r"제출\s*서류|구비\s*서류|필수\s*서류|제출\s*자료")),
    ("eligibility", re.compile(
        r"신청\s*자격|참여\s*자격|(?:지원|신청)\s*대상(?=\s*[:：\n]|은)"
    )),
    ("deadline", re.compile(r"접수\s*마감|신청\s*마감|신청\s*기간|접수\s*기간")),
)


@dataclass(frozen=True)
class Gap:
    source_id: str
    start: int
    end: int
    topic: str
    query: str

    def hint(self):
        return dict(sourceId=self.source_id, start=self.start, end=self.end,
                    topic=self.topic, query=self.query)


def confirmed_ranges(document, result_cache, additional=()):
    """Read only actual text coverage, excluding errors, queries and empty search hits."""
    ranges = defaultdict(list)

    def add(item):
        source_id = item.get("sourceId")
        if source_id and item.get("coverage", {}).get("selectedChars", 0) > 0:
            ranges[source_id].extend(
                (span["start"], span["end"]) for span in item["coverage"]["sourceRanges"]
            )

    if "get_document_content" in result_cache:
        add(json.loads(result_cache["get_document_content"]))
    if "get_attachment_texts" in result_cache:
        for item in json.loads(result_cache["get_attachment_texts"]):
            add(item)
    for content in additional:
        payload = json.loads(content)
        if payload.get("status") == "OK":
            add(payload)
            for hit in payload.get("matches", []):
                if hit.get("text"):
                    add(hit)
    # Identical sources share already-read text, but duplicate IDs never establish coverage.
    sources = [("body", document.content_text or "")] + [
        (f"attachment:{item.attachment_id}", item.extracted_text or "")
        for item in document.attachments
    ]
    counts = Counter(identity for identity, _ in sources)
    by_text = defaultdict(list)
    for identity, text in sources:
        if counts[identity] == 1 and text.strip():
            by_text[text].append(identity)
    for identities in by_text.values():
        combined = sorted({span for identity in identities for span in ranges[identity]})
        for identity in identities:
            ranges[identity] = combined
    for identity, count in counts.items():
        if count > 1:
            ranges[identity] = []
    return dict(ranges)


def unread_gaps(document, ranges):
    sources = [("body", document.content_text or "")] + [
        (f"attachment:{item.attachment_id}", item.extracted_text or "")
        for item in document.attachments
    ]
    gaps, seen = [], set()
    for source_id, text in sources:
        if not text.strip() or text in seen:
            continue
        seen.add(text)
        for start, end in source_units(text):
            if range_covered(start, end, ranges.get(source_id, [])):
                continue
            unit = text[start:end]
            for topic, pattern in _MARKERS:
                match = pattern.search(unit)
                if match:
                    query = " ".join(match.group().split())
                    gaps.append(Gap(source_id, start, end, topic, query))
                    break  # One source unit needs one read, even with several topic markers.
    order = {topic: index for index, (topic, _) in enumerate(_MARKERS)}
    return sorted(gaps, key=lambda gap: (order[gap.topic], gap.source_id, gap.start))
