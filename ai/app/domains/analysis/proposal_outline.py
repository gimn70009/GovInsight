"""Source-grounded heading candidates for forms with duplicated table extraction."""

import re

_SCALAR_FIELD = re.compile(
    r"company name|full name|address(?:\s*\(hq\))?|\(hq\)|revenue|export|"
    r"employees?\s*(?:no\.?)?|total|r&d|timeline|date|dd\s+mm,?\s+yyyy|"
    r"web\s*(?:page|site)|the person in charge|title|division|tel\.?|"
    r"cell phone|e[- ]?mail|million euro|\d{4}\s*fy|"
    r"회사명|기업명|성명|대표자명|사업자등록번호|주소|전화번호|연락처|이메일|사업\s*기간",
    re.I,
)
_GUIDANCE = re.compile(
    r"작성|기재|서술|(?:기술|설명)(?:하|합|해)|\b(?:describe|explain|outline|write)\b", re.I
)
_NARRATIVE_GUIDANCE = re.compile(
    r"\b(?:describe|explain)\b|서술|(?:상세|구체).*(?:작성|기술)", re.I
)
_ANNOTATION = re.compile(r"^[※*•]|^[\[(](?:영문|국문|영어|한국어)\s*작성|니다[.]?$|주세요[.]?$")
_FORM_HEADER = re.compile(
    r"^(?:executive summary of (?:application|proposal)|business plan|research proposal|"
    r"(?:[\w· .-]{0,40})?(?:사업\s*계획서|연구\s*개발\s*계획서|신청서|제안서))"
    r"(?:\s*[\[(].*)?$",
    re.I,
)


def _compact(text):
    return re.sub(r"\s+", "", text)


def heading_candidates(text):
    """Keep original line IDs; remove only structural duplicates and obvious non-headings."""
    lines = text.splitlines()
    aggregate = set()
    for index, line in enumerate(lines):
        target = _compact(line)
        if not target:
            continue
        joined = ""
        count = 0
        for child_index in range(index + 1, len(lines)):
            child = lines[child_index]
            if not child.strip():
                continue
            joined += _compact(child)
            count += 1
            if not target.startswith(joined):
                break
            if joined == target:
                if count > 1:
                    aggregate.add(index)
                break

    candidates = {}
    for index, line in enumerate(lines):
        title = line.strip()
        following = lines[index + 1] if index + 1 < len(lines) else ""
        scalar_title = re.sub(r"^(?:\d+[.)]\s*|[가-힣][.)]\s*)|[:：]\s*$", "", title).strip()
        if (
            index in aggregate
            or not 2 <= len(title) <= 180
            or (_SCALAR_FIELD.fullmatch(scalar_title) and not _NARRATIVE_GUIDANCE.search(following))
            or _ANNOTATION.search(title)
            or _FORM_HEADER.fullmatch(title)
        ):
            continue
        guided = bool(_GUIDANCE.search(following))
        # A label followed by scalar value cells belongs to the basic-information table.
        following_cells = [value.strip() for value in lines[index + 1 : index + 3]]
        if (
            not guided
            and len(following_cells) == 2
            and all(_SCALAR_FIELD.fullmatch(value) for value in following_cells)
        ):
            continue
        key = _compact(title).casefold()
        previous = candidates.get(key)
        # The table's summary cell may repeat a label before its actual narrative field.
        if previous is None or (guided and not previous["has_writing_guidance"]):
            candidates[key] = {"line": index + 1, "text": line, "has_writing_guidance": guided}

    result = sorted(candidates.values(), key=lambda item: item["line"])
    for index, candidate in enumerate(result):
        start = candidate["line"]
        end = result[index + 1]["line"] - 1 if index + 1 < len(result) else len(lines)
        candidate["context"] = "\n".join(
            lines[position]
            for position in range(start, min(start + 8, end))
            if position not in aggregate
        )[:1000]
    return result
