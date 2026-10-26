"""Keep explicitly linked form enclosures with the form that contains them."""

import re

from app.domains.analysis.proposals.submission_requirements import (
    SubmissionRequirement,
    SubmissionSources,
    _key,
    _sources,
)


def linked_contents(
    rows: list[SubmissionRequirement],
    document: SubmissionSources,
) -> dict[int, list[str]]:
    result: dict[int, list[str]] = {}
    for source in _sources(document):
        parents = [
            (i, row)
            for i, row in enumerate(rows)
            if row.source.section_title.startswith("제출서류의 ")
            and row.source.attachment_name == source.name
        ]
        if not parents:
            continue
        for heading in re.finditer(r"(?m)^\s*붙임\s*서류\s*$", source.text):
            prefix = _key(source.text[: heading.start()])
            preceding = [(prefix.rfind(_key(row.title)), i) for i, row in parents]
            preceding = [(position, i) for position, i in preceding if position >= 0]
            if not preceding:
                continue
            parent_index = max(preceding)[1]
            if not rows[parent_index].included_in:
                continue
            block = source.text[heading.end() : heading.end() + 1200]
            for line in block.lstrip().splitlines():
                match = re.fullmatch(r"\s*\d+[.)]\s*([^\r\n]{2,180})", line)
                if not match:
                    break
                value = match[1].strip()
                if re.search(r"▸|▶|▷|해당|경우|최초|변경|면제|불필요|선택|택일|참고|예시", value):
                    continue  # Conditional enclosures remain separate obligations.
                if not re.search(r"(?:계획서|직무기술서|명세서|내역서|확인서)$", value):
                    continue
                if any(_key(value) == _key(row.title) for row in rows):
                    continue
                entries = result.setdefault(parent_index, [])
                if value not in entries:
                    entries.append(value)
    return result
