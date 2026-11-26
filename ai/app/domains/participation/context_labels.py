"""Validate display labels without rewriting the condition or inventing a subject."""

import re


def program_role(value):
    """A bare role/region label cannot establish a business or track."""
    return bool(re.fullmatch(
        r".+(?:구성한|구성된|보유한|소재한|영위하는|충족하는|해당하는)"
        r".{0,30}(?:기업|기관|단체|협의체|컨소시엄)", (value or "").strip()
    ) or re.fullmatch(
        r"(?:협의체|컨소시엄|기업|기관|공모대상과제|모집대상|신청주체)"
        r"|(?:(?:총괄|공동|주관|연계|참여|수행|운영|신청|도입|공급)\s*)+"
        r"(?:기업|기관|지역|연구개발기관|연구자|연구원|책임자)", (value or "").strip()
    ))


def program_scope(value, evidence, quote=None):
    # Explicitly named program columns/rows can have arbitrary names, including
    # words also used for applicants. A bare role without that evidence is not a program.
    named = set()
    lines = evidence.splitlines()
    for index, line in enumerate(lines):
        header = line.split("\t")
        if len(header) < 2:
            continue
        following = []
        for row in lines[index + 1:]:
            if "\t" not in row:
                break
            following.append(row)
        cited_rows = [row for row in following if quote is None or quote in row or row in quote]
        if not cited_rows:
            continue
        if header[0].strip() == "구분" and any(
            row.split("\t")[0].strip()
            in {"지원대상", "신청대상", "참여대상", "신청자격", "지원자격"}
            for row in cited_rows
        ):
            named.update(cell.strip() for cell in header[1:])
        columns = [i for i, cell in enumerate(header)
                   if re.fullmatch(
                       r"(?:세부)?사업명|사업유형|지원유형|과제유형|트랙", cell.strip())]
        for row in cited_rows:
            cells = row.split("\t")
            named.update(cells[i].strip() for i in columns if i < len(cells))
    paths = []
    for path in (value or "").split(" / "):
        parts = [part for part in path.split(" · ") if not program_role(part) or part in named]
        if parts:
            paths.append(" · ".join(parts))
    return " / ".join(paths) or None


def structural_label(value):
    """A section path, recruitment title or form name is not a program name."""
    value = re.sub(r"\s+", "", value or "")
    value = re.sub(r"^(?:\(?(?:[0-9]+(?:-[0-9]+)*|[가-힣]|[IVXⅠ-Ⅻ]+)[.)]|[①-⑳])", "", value)
    if re.fullmatch(r".+(?:모집(?:공고|안내)?|선정공고|신청안내)", value):
        return True
    parts = re.split(r"[-–—>›/:：·ㆍ|]+", value)
    return bool(parts) and all(re.fullmatch(
        r"(?:사업|과제)?(?:개요|목적|내용|수행내용)|지원분야|수행내용"
        r"|(?:신청|지원|참여)?(?:자격|요건|대상)|지원내용|제출서류|신청서류"
        r"|신청방법|접수방법|평가방법|평가기준|선정기준|평가|유의사항"
        r"|(?:신청)?자격(?:적정성)?확인서|신청서|신청서식|서식|작성예시",
        part,
    ) for part in parts)


def audience_label(value, evidence):
    """Keep only labels supported by this condition's citation, not another source row."""
    label = (value or "").strip()
    if not label or structural_label(label):
        return None
    cited = re.sub(r"\s+", "", evidence)

    def supported(candidate):
        words = [word for word in re.split(r"[\s·ㆍ‧,/()（）]+", candidate)
                 if word and word not in {"및", "또는", "와", "과", "등"}]
        return bool(words) and all(word in cited for word in words)

    if supported(label):
        return label
    # An unsupported gloss cannot add a role to an otherwise grounded row label.
    match = re.fullmatch(r"([^()（）]+)[(（]([^()（）]+)[)）]", label)
    if match:
        if supported(match[1].strip()):
            return match[1].strip()
        if match[1].strip() in {"신청자", "신청기관", "신청기업"} and supported(match[2]):
            return match[2].strip()
    applicant = re.sub(r"\s+신청자$", "", label)
    return applicant if applicant != label and supported(applicant) else None
