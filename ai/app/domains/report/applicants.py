"""Conservative display of role rows; checkbox states are data, never bullets."""

import re

_MARK = re.compile(r"[□☐■☑☒]")
_ROLE = re.compile(
    r"(?:(?:국내|국외|해외)\s*)?"
    r"(?:주관기관\s*외\s*필수\s*참여기관|주관(?:연구개발)?기관|"
    r"공동(?:연구개발)?기관|필수\s*참여기관)"
    r"(?:\s*(?:유형|자격))?(?=\s*(?:[:：]|[□☐■☑☒]))"
)
_OPTION = re.compile(
    r"(?:제한\s*없음|(?:중소|중견|대)기업|중소[·ㆍ/]중견기업|기업|"
    r"대학(?:교)?|출연\(연\)|국공립연|특정연|연구기관|"
    r"(?:기타\s*)?비영리법인\s*(?:\(병원\s*등\))?|외국법인)"
)
_CONDITION = re.compile(r"(?:단[,，:]|다만(?:[,，:]|\s)|※)")
_UNKNOWN = "원문 확인 필요"


def checkbox_applicants(value: str) -> list[str] | None:
    """None means ordinary prose; unknown rows remain explicit instead of disappearing.

    Only a role followed by category options is interpretable. Separate column
    headings, missing selection states and unknown labels never imply eligibility.
    """
    marks = list(_MARK.finditer(value))
    roles = []
    for role in _ROLE.finditer(value):
        checkbox_row = _MARK.match(value[role.end():].lstrip(" :："))
        if roles and not checkbox_row and _CONDITION.search(value[roles[-1].end():role.start()]):
            continue
        roles.append(role)
    if not marks:
        return None
    # A square before a section title is also a common document outline symbol.
    if not roles:
        chunks = [value[m.end():marks[i + 1].start() if i + 1 < len(marks) else len(value)]
                  for i, m in enumerate(marks)]
        if len(marks) < 2 or re.match(
            r"\s*(?:신청\s*자격|신청\s*대상|지원\s*대상)\s*[:：]", chunks[0]
        ):
            return None
        condition = _CONDITION.search(value)
        suffix = ". " + value[condition.start():].strip() if condition else ""
        return [_UNKNOWN + suffix]
    if marks[-1].start() < roles[0].end():
        return None
    prefix = value[:roles[0].start()].strip(" :：/|\n□■☐☑☒")
    if prefix and not re.fullmatch(r"(?:신청\s*자격|신청\s*대상|지원\s*대상)", prefix):
        return [_UNKNOWN]
    labels = [_role_label(m[0]) for m in roles]
    result = []
    for index, role in enumerate(roles):
        label = labels[index]
        row = value[role.end():roles[index + 1].start() if index + 1 < len(roles) else len(value)]
        row = row.strip(" :：/|\n")
        condition = _CONDITION.search(row)
        suffix = row[condition.start():].strip() if condition else ""
        options = row[:condition.start()].strip() if condition else row
        markers = list(_MARK.finditer(options))
        if not markers and row and labels.count(label) == 1:
            result.append(f"{label}: {row}")
            continue
        selected, choices = [], []
        valid = len(markers) >= 2 and not options[:markers[0].start()].strip() if markers else False
        for pos, marker in enumerate(markers):
            option = options[marker.end():markers[pos + 1].start()
                             if pos + 1 < len(markers) else len(options)].strip(" \n/|;.")
            valid = valid and bool(_options(option))
            key = re.sub(r"\s+", "", option)
            if key in choices:
                valid = False
            choices.append(key)
            if marker[0] in "■☑☒":
                selected.append(re.sub(r"제한\s*없음", "제한 없음", option))
        valid = valid and bool(selected) and len(selected) < len(markers)
        valid = valid and labels.count(label) == 1
        valid = valid and not (len(selected) > 1 and "제한 없음" in selected)
        content = " · ".join(selected) if valid else _UNKNOWN
        if suffix:
            content += ". " + suffix
        result.append(f"{label}: {content}")
    return list(dict.fromkeys(result))


def _options(value: str) -> bool:
    # Slashes may join several research institution categories in a single option.
    return all(_OPTION.fullmatch(part.strip()) for part in value.strip(" \n/|;.").split("/"))


def _role_label(value: str) -> str:
    value = re.sub(r"\s+", "", value)
    value = re.sub(r"(?:유형|자격)$", "", value)
    value = re.sub(r"^(국내|국외|해외)", r"\1 ", value)
    return value.replace("주관기관외", "주관기관 외 ").replace("필수참여기관", "필수 참여기관")


def applicant_source_display(value: str) -> str:
    """Safe structural formatting retains every non-checkbox condition verbatim."""
    checkbox = checkbox_applicants(value)
    if checkbox is not None:
        return "\n".join(checkbox)
    value = re.sub(r"(?:^|\s+)[ㅇ○●▪•□■◦▫◇▶▸]\s+", "\n", value).strip()
    value = re.sub(
        r"^(?:[가-하][.)]\s*)?(?:신청\s*자격|신청\s*대상|지원\s*대상)"
        r"(?=\s|[:：]|$)\s*[:：]?\s*", "", value,
    )
    return "\n".join(" ".join(line.split()) for line in value.splitlines() if line.strip())


def equivalent_applicant_display(source: str, display: str) -> bool:
    """Permit whitespace and label punctuation changes, never different category words."""
    def key(value: str) -> str:
        return re.sub(r"[\s:：]+", "", applicant_source_display(value))

    return key(source) == key(display)
