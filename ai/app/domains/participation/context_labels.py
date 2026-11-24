"""Validate display labels without rewriting the condition or inventing a subject."""

import re


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
