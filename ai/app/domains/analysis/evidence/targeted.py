"""Bounded literal search of the current notice, preserving original source offsets."""

import json
import re
from _thread import LockType
from collections.abc import Callable
from dataclasses import dataclass, field
from threading import Lock

from app.domains.analysis.evidence.selection import range_covered, source_units
from app.domains.analysis.schemas.request import AnalysisDocumentRequest

MAX_ADDITIONAL_CHARS = 3_000
MAX_SEARCH_CHARS = 1_500
MAX_READ_CHARS = 2_000
MAX_HITS = 5
MAX_TARGETED_REQUESTS = 2
SEARCH_TOOLS = frozenset({"search_document_evidence", "read_document_evidence"})


@dataclass(frozen=True)
class Source:
    source_id: str
    text: str
    file_name: str | None = None
    attachment_id: int | None = None

    def metadata(self) -> dict:
        return {
            "sourceId": self.source_id,
            "fileName": self.file_name,
            "attachmentId": self.attachment_id,
            "originalChars": len(self.text),
        }


@dataclass
class TargetedEvidence:
    # Sync LangChain tools can run in parallel threads. Reserve the shared budget atomically.
    cache: dict[str, str] = field(default_factory=dict)
    completed: set[tuple[str, str, str]] = field(default_factory=set)
    selected_chars: int = 0
    lock: LockType = field(default_factory=Lock, repr=False)

    def run(
        self,
        name: str,
        call_id: str | None,
        arguments: dict,
        max_text_chars: int,
        produce: Callable[[int], dict],
    ) -> str:
        key = json.dumps([name, arguments], sort_keys=True, ensure_ascii=False)
        with self.lock:
            if key not in self.cache:
                limit = min(MAX_ADDITIONAL_CHARS, max(0, max_text_chars))
                payload = (
                    produce(max(0, limit - self.selected_chars))
                    if len(self.cache) < MAX_TARGETED_REQUESTS
                    else {"status": "REQUEST_LIMIT", "selectedChars": 0}
                )
                self.selected_chars += payload.get("selectedChars", 0)
                payload["additionalCharsLimit"] = limit
                self.cache[key] = json.dumps(payload, ensure_ascii=False)
            result = self.cache[key]
            self.completed.add((call_id or "", name, result))
            return result

    def contains(self, call_id: str, name: str, content: object) -> bool:
        return isinstance(content, str) and (call_id, name, content) in self.completed


def _sources(document: AnalysisDocumentRequest) -> list[Source]:
    return [
        Source("body", document.content_text or ""),
        *[
            Source(f"attachment:{item.attachment_id}", item.extracted_text or "",
                   item.file_name, item.attachment_id)
            for item in document.attachments
        ],
    ]


def _resolve(document: AnalysisDocumentRequest, source_id: str) -> tuple[list[Source], str]:
    sources = _sources(document)
    if source_id != "all":
        sources = [source for source in sources if source.source_id == source_id]
    if not sources:
        return [], "UNKNOWN_SOURCE"
    ids = [source.source_id for source in sources]
    if len(set(ids)) != len(ids):
        return [], "AMBIGUOUS_SOURCE"
    return sources, "OK"


def _base(document: AnalysisDocumentRequest, source_id: str) -> dict:
    return {
        "versionId": document.version_id,
        "sourceId": source_id,
        "selectedChars": 0,
        "notice": "일치 없음은 조건 부재의 증명이 아닙니다. 반환된 원문 범위만 확인했습니다.",
    }


def _coverage(source: Source, start: int, end: int) -> dict:
    return {
        "originalChars": len(source.text),
        "selectedChars": end - start,
        "sourceRanges": [{"start": start, "end": end}],
        "truncated": start > 0 or end < len(source.text),
    }


def _matching_units(sources: list[Source], pattern: re.Pattern):
    seen_texts: set[str] = set()
    for source in sources:
        # Identical attachments are the same evidence. Targeting an ID still reads that file.
        if not source.text.strip() or source.text in seen_texts:
            continue
        seen_texts.add(source.text)
        units = source_units(source.text)
        unit_index = 0
        last_unit = -1
        for match in pattern.finditer(source.text):
            while unit_index < len(units) and units[unit_index][1] <= match.start():
                unit_index += 1
            if unit_index == len(units):
                break
            start, end = units[unit_index]
            if unit_index <= last_unit:
                continue
            # A phrase may span adjacent units. Keep its complete original range.
            last_index = unit_index
            while end < match.end() and last_index + 1 < len(units):
                last_index += 1
                end = units[last_index][1]
            last_unit = last_index
            yield source, match.start(), match.end(), start, end


def search_sources(
    document: AnalysisDocumentRequest,
    queries: list[str],
    source_id: str,
    offset: int,
    budget: int,
    exclude_ranges: dict[str, list[tuple[int, int]]] | None = None,
    include_ranges: dict[str, list[tuple[int, int]]] | None = None,
) -> dict:
    payload = {**_base(document, source_id), "queries": queries, "offset": offset,
               "matches": [], "nextOffset": None}
    normalized = [" ".join(query.split()) for query in queries]
    if not normalized or any(len(query) < 2 for query in normalized):
        return {**payload, "status": "INVALID_QUERY"}
    sources, status = _resolve(document, source_id)
    if status != "OK":
        return {**payload, "status": status}
    payload["unavailableSourceIds"] = [s.source_id for s in sources if not s.text.strip()]
    if not any(source.text.strip() for source in sources):
        return {**payload, "status": "SOURCE_UNAVAILABLE"}
    # Literal phrases only, with optional whitespace between words. Never execute user regex.
    pattern = re.compile(
        "|".join(r"\s*".join(re.escape(word) for word in query.split()) for query in normalized),
        re.IGNORECASE,
    )
    remaining = min(MAX_SEARCH_CHARS, max(0, budget))
    matches = (
        match for match in _matching_units(sources, pattern)
        if not range_covered(match[3], match[4], (exclude_ranges or {}).get(match[0].source_id, []))
        and (include_ranges is None or any(
            left < match[4] and right > match[3]
            for left, right in include_ranges.get(match[0].source_id, [])
        ))
    )
    payload["alreadyReadRangesExcluded"] = exclude_ranges is not None
    payload["candidateRangesOnly"] = include_ranges is not None
    for index, (source, match_start, match_end, start, end) in enumerate(matches):
        if index < offset:
            continue
        if len(payload["matches"]) == MAX_HITS:
            payload["nextOffset"] = offset + MAX_HITS
            break
        # Return complete units only; a long sentence must not become a false condition.
        include = end - start <= min(800, remaining)
        hit = {
            **source.metadata(),
            "matchStart": match_start,
            "matchEnd": match_end,
            "suggestedRange": {"start": start, "end": end},
            "text": source.text[start:end] if include else "",
            "contextStatus": "INCLUDED" if include else "READ_REQUIRED",
            "coverage": _coverage(source, start, end) if include else {
                "originalChars": len(source.text), "selectedChars": 0,
                "sourceRanges": [], "truncated": True,
            },
        }
        payload["matches"].append(hit)
        if include:
            remaining -= end - start
            payload["selectedChars"] += end - start
    payload["status"] = "OK" if payload["matches"] else "NO_MATCH"
    return payload


def read_source(
    document: AnalysisDocumentRequest,
    source_id: str,
    start: int,
    end: int,
    budget: int,
) -> dict:
    payload = {**_base(document, source_id), "requestedRange": {"start": start, "end": end},
               "text": ""}
    sources, status = _resolve(document, source_id)
    if status != "OK":
        return {**payload, "status": status}
    if len(sources) != 1:
        return {**payload, "status": "UNKNOWN_SOURCE"}
    source = sources[0]
    payload.update(source.metadata())
    if not source.text.strip():
        return {**payload, "status": "SOURCE_UNAVAILABLE"}
    if start < 0 or end <= start or end > len(source.text):
        return {**payload, "status": "INVALID_RANGE"}
    limit = min(MAX_READ_CHARS, max(0, budget))
    if limit == 0:
        return {**payload, "status": "BUDGET_EXHAUSTED"}
    units = source_units(source.text)
    indexes = [index for index, (left, right) in enumerate(units)
               if left < end and right > start]
    if not indexes:
        return {**payload, "status": "INVALID_RANGE"}
    first, last = indexes[0], indexes[-1]
    left, right = min(start, units[first][0]), max(end, units[last][1])
    if right - left > limit:
        return {**payload, "status": "CONTEXT_TOO_LARGE", "requiredChars": right - left,
                "maxReadChars": limit}
    # Include adjacent complete context when it fits, especially following exceptions.
    if last + 1 < len(units) and units[last + 1][1] - left <= limit:
        right = units[last + 1][1]
    if first > 0 and right - units[first - 1][0] <= limit:
        left = units[first - 1][0]
    return {
        **payload, "status": "OK", "text": source.text[left:right],
        "selectedChars": right - left, "coverage": _coverage(source, left, right),
    }
