"""Readable monetary facts with quantity, scope and condition checks."""

import re
from collections import Counter
from decimal import Decimal

_QUANTITY = re.compile(
    r"(?P<number>\d+(?:[.,]\d+)*)\s*"
    r"(?P<scale>천만|백만|억|만|천)?\s*(?P<unit>개월|년|원|%|개|명)"
)
_SCALES = {None: 1, "천": 1000, "만": 10000, "백만": 1000000,
           "천만": 10000000, "억": 100000000}
_BULLET = re.compile(r"(?:^|\s)[□■○●ㅇo•※*]\s*(?=[가-힣(0-9’‘])")
_SCOPE = re.compile(
    r"(?P<project>과제(?:당|별)|각\s*과제)|(?P<company>(?:기업|업체)(?:당|별))|"
    r"(?P<total>신규\s*예산|(?:총|전체)\s*(?:신규\s*)?(?:예산|사업비|지원금)|사업\s*전체)|"
    r"(?P<contract>계약\s*(?:예산|금액|규모)|추정\s*(?:가격|금액)|기초\s*금액)"
)
_CAUSES = {
    "evaluation": r"평가\s*결과|평가에\s*따라",
    "phase_evaluation": r"단계\s*평가",
    "parliament_review": r"국회.{0,10}심의",
    "extra_appropriation": r"예산.{0,5}추가\s*확보|추가\s*예산.{0,5}확보",
    "final_notification": r"최종\s*선정\s*통보",

    "budget_circumstance": r"예산\s*(?:사정|상황)",
    "policy": r"정부\s*정책|정책\s*변경",
    "specified_conditions": r"명시된\s*조건|정해진\s*(?:지원)?조건",
    "budget_range": r"예산\s*범위",
}
_FACT_WORDS = {
    "government": r"정부\s*(?:지원|출연|예산)|정부가|정부는",
    "own_funding": r"자부담|(?:기관|민간)\s*부담|기관이.{0,15}부담",
    "cash": r"현금", "in_kind": r"현물",
    "subsidy": r"보조금", "loan": r"융자|대출", "guarantee": r"보증",
    "refund": r"환급", "repayment": r"반환|상환", "free": r"무상|무료", "full": r"전액",
    "collateral": r"담보", "first_come": r"선착순",
    "installments": r"분할|나누어\s*(?:지급|지원)|나눠\s*(?:지급|지원)",
    "increase": r"증액|상향", "decrease": r"감액|하향|축소", "fixed": r"확정|고정",
    "sme": r"중소기업", "large_company": r"대기업", "mid_company": r"중견기업",
}


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def readable_amount_source(value: str) -> str | None:
    """Clean syntax only; do not interpret an unlabelled or broken table."""
    value = " ".join(value.split())
    value = re.sub(r"^\s*\d+(?:-\d+)*[.)]\s*", "", value)
    value = re.sub(
        r"^지원\s*(?:규모|금액|한도)(?:\s*및\s*기간)?\s*[:：]?\s*"
        r"(?=(?:[oㅇ○•]\s*)?(?:신규|과제|기업|총|정부|사업))", "", value,
    )
    value = _BULLET.sub(" ", value).strip()
    value = re.sub(r"(?:^|\s)-\s+(?=[가-힣])", " ", value)
    # Collapsed table cells have no reliable amount-to-scope relationship.
    if (not value or len(value) > 500
            or re.match(r"(?:품목지정|자유공모|지정공모|연구개발비\s+품목지정|\d+(?!\d*년))", value)
            or re.search(
                r"(?:프로그램|대상국가|지원자격).{0,20}(?:프로그램|대상국가|지원자격)", value,
            )):
        return None
    def currency(match):
        amount = Decimal(match["number"].replace(",", "")) * _SCALES[match["scale"]]
        if match["scale"] == "백만" and amount >= 100000000:
            return f"{amount / Decimal(100000000):f}억 원"
        return f"{match['number']}{match['scale'] or ''} 원"
    value = re.sub(
        r"(?P<number>\d+(?:[.,]\d+)*)\s*(?P<scale>천만|백만|억|만|천)?\s*원",
        currency, value,
    )
    value = re.sub(r"\(\s*(총\s*(?:연구개발기간|정부지원연구개발비))\s*\)", r"\1: ", value)
    # The paired period/budget label is explicit, so remove its table slash safely.
    value = re.sub(
        r"사업\s*기간\s*/\s*예산\s*\(([^)]+?)\s*\)\s*:\s*"
        r"([’‘]?\d{2,4}년\s*[~∼-]\s*[’‘]?\d{2,4}년)\s*/\s*",
        r"사업 기간: \2. \1: ", value,
    )
    value = re.sub(r"(원\s*(?:내외|이내))\s+(?=단계\s*평가)", r"\1. ", value)
    value = re.sub(r"수\s*있음(?=[.)]|$)", "수 있습니다", value)
    value = re.sub(r"가능(?=[.(]|$)", "가능합니다", value)
    value = re.sub(r"가능\s+(?=[’‘]?\d{2,4}년)", "가능합니다. ", value)
    return " ".join(value.split())


def _funding_actor(before: str, after: str) -> str:
    patterns = {
        "own": r"자부담|(?:기관|민간)\s*부담|기관[이는]",
        "state": r"정부(?:\s*(?:지원|출연)|[가는])|지원\s*비율",
    }
    preceding = [(match.start(), label) for label, pattern in patterns.items()
                 for match in re.finditer(pattern, before)]
    if preceding:
        return max(preceding)[1]
    # Natural Korean often puts the payer after the value: "25%는 기관이 부담".
    clause = re.split(r"[,.;]", after)[0]
    post_patterns = {
        "own": r"기관[이는].{0,12}부담|(?:기관|민간)\s*부담(?:금|액)?(?:입니다|이다|함|$)",
        "state": r"정부[가는].{0,12}지원|정부\s*지원(?:금|액)?(?:입니다|이다|함|$)",
    }
    following = [(match.start(), label) for label, pattern in post_patterns.items()
                 for match in re.finditer(pattern, clause)]
    return min(following)[1] if following else ""


def _quantity_items(value: str) -> list[tuple]:
    facts = []
    previous_end = 0
    currency_end = 0
    quantities = list(_QUANTITY.finditer(value))
    for index, match in enumerate(quantities):
        number = Decimal(match["number"].replace(",", "")) * _SCALES[match["scale"]]
        unit = match["unit"]
        before = value[previous_end:match.start()]
        next_start = quantities[index + 1].start() if index + 1 < len(quantities) else len(value)
        after = value[match.end():next_start]
        before_money = value[currency_end:match.start()]
        bound = re.match(r"\s*(이상|이하|미만|초과|이내|내외|정도)", after)
        prefix = re.search(r"(최대|최소|약|대략)\s*$", before)
        comparator = {"이내": "<=", "이하": "<=", "최대": "<=", "이상": ">=",
                      "최소": ">=", "미만": "<", "초과": ">", "내외": "~", "정도": "~",
                      "약": "~", "대략": "~"}.get(
            bound[1] if bound else prefix[1] if prefix else "", "=",
        )
        scope = ""
        annual = False
        if unit == "원":
            scopes = list(_SCOPE.finditer(before_money))
            scope = scopes[-1].lastgroup if scopes else ""
            annual = bool(re.search(r"연간|매년|(?:^|\s)연(?=\s|최대|최소)", before_money)
                          or re.search(r"연간|매년", after))
            # A plain total amount cannot become an annual cap with the same digits.
            if not scope and re.search(r"총\s*(?:지원금|지원액)?\s*$", before_money):
                scope = "total"
            currency_end = match.end()
        actor = _funding_actor(before, after) if unit in {"원", "%"} else ""
        if unit == "원" and not actor:
            # In a period/budget table the payer label can precede the year cells.
            actor = _funding_actor(before_money, "")
        frequency = "annual" if annual else ""
        if unit == "원" and not frequency:
            for name, pattern in (("monthly", r"매월|월간|월\s*(?:최대|최소)?\s*$"),
                                  ("daily", r"매일|일간|일\s*(?:최대|최소)?\s*$")):
                if re.search(pattern, before_money) or re.search(pattern, after):
                    frequency = name
                    break
        if unit == "원" and not frequency and re.search(
            r"총\s*(?:(?:정부)?(?:지원|출연)(?:연구개발비|금|비|액)?|사업비|예산|연구비|"
            r"연구개발비)?\s*(?:은|는|이|가|:)?\s*(?:약|대략|최대|최소)?\s*$", before_money,
        ):
            frequency = "total"
        facts.append((number, unit, comparator, scope, frequency, actor))
        previous_end = match.end()
    return facts


def _quantity_facts(value: str) -> Counter:
    return Counter(_quantity_items(value))


def _conditions(value: str) -> set[str]:
    compact = _compact(value)
    result = {name for name, pattern in _CAUSES.items() if re.search(pattern, value)}
    for label, pattern in _FACT_WORDS.items():
        if re.search(pattern, value):
            result.add(label)
    vat = re.search(r"(?:부가(?:가치)?세|VAT)(?:는|가|를)?[ ()]*(포함|별도|제외)", value, re.I)
    if vat:
        result.add("vat:" + ("include" if vat[1] == "포함" else "exclude"))
    adjust = bool(re.search(r"변동|조정|변경|달라질|달라질수", compact))
    if adjust:
        result.add("adjustment")
        if re.search(r"수있|가능", compact):
            result.add("adjustment_possible")
        for field, pattern in (("budget", r"지원(?:예산|금|액)|예산"),
                               ("duration", r"지원기간|기간|개월수")):
            if re.search(pattern, compact):
                result.add("adjustment:" + field)
    for label, pattern in {
        "project_cancellation": r"과제\s*(?:가\s*)?중단|과제를\s*중단",
        "cancelled_project_count": r"중단(?:된)?\s*과제\s*수(?:만큼|\s*만큼)",
        "additional_selection": r"추가\s*선정|신규\s*선정",
        "lower_priority": r"후순위",
        "selected_project_count": r"선정\s*과제\s*수",
        "annual_budget": r"연차별\s*예산",
        "start_date": r"개시일|시작일",
    }.items():
        if re.search(pattern, value):
            result.add(label)
    for label, pattern in {
        "cancellation_possible": (
            r"과제(?:가)?중단.{0,6}(?:가능|수있)|과제를중단.{0,6}(?:가능|수있)"
        ),
        "selection_possible": r"(?:추가|신규)선정.{0,6}(?:가능|수있)",
    }.items():
        if re.search(pattern, compact):
            result.add(label)
    # Preserve actor-specific restrictions instead of just a bag of negation words.
    subjects = r"중소기업|중견기업|대기업|정부지원연구개발비|정부지원금"
    matches = list(re.finditer(subjects, compact))
    for index, match in enumerate(matches):
        subject = re.sub(r"정부지원연구개발비", "정부지원금", match[0])
        end = matches[index + 1].start() if index + 1 < len(matches) else len(compact)
        tail = re.split(r"[.,;]", compact[match.end():min(end, match.end() + 24)])[0]
        if re.search(r"제외|불가|지원하지|지원않", tail):
            result.add("exclude:" + subject)
        if re.search(r"한해|한함|한합|한정|만(?:포함|지원|가능)|에만", tail):
            result.add("only:" + subject)
    for label, pattern in {
        "negative": r"하지\s*않|되지\s*않|없음|없습|미지원|미제공",
        "prohibited": r"불가|금지", "waiver": r"면제",
    }.items():
        if re.search(pattern, value):
            result.add(label)
    if re.search(r"필수|의무|반드시|(?:해야|하여야)\s*(?:함|합니다|한다)?", value):
        result.add("mandatory")
    if re.search(r"선택|납부할\s*수|부담할\s*수", value):
        result.add("optional")
    # Identical money in separately named schemes is not a duplicate.
    names = re.findall(
        r"(?:사업|프로그램)\s+([A-Za-z가-힣0-9_-]+)(?=\s+(?:지원|예산|사업비)|[:：])", value,
    )
    names += re.findall(r"([A-Za-z가-힣0-9_-]+)\s*(?:사업|프로그램)\s*[:：]", value)
    for name in names:
        if name not in {"기간", "예산", "지원금", "규모", "사업비"}:
            result.add("program:" + name)
    for track in re.findall(
        r"[가-힣A-Za-z0-9]+(?:형|분야|트랙|부문|유형)(?![가-힣])", value,
    ):
        result.add("track:" + _compact(track))

    return result


def _remaining_numbers(value: str) -> Counter:
    return Counter(re.findall(r"\d+(?:[.,]\d+)*", _QUANTITY.sub("", value)))


def validated_amount_display(source: str, display: str | None) -> str | None:
    """Accept financial prose only when typed quantities and protected facts agree."""
    if display is None:
        return None
    display = " ".join(display.split())
    clean = readable_amount_source(source)
    if (not clean or not display or len(display) > 300
            or not re.search(r"[가-힣]", display) or _BULLET.search(display)
            or re.search(r"[□■]|(?:기간|예산)\s*/|\s/\s|^\d+(?:-\d+)*[.)]", display)
            or re.search(r"누구나|무조건|보장|확정|모든\s*기업", display)):
        return None
    facts = _quantity_facts(clean)
    if not facts or facts != _quantity_facts(display):
        return None
    # Preserve unparsed endpoints/counts too; a range must not silently become its cap.
    if _remaining_numbers(clean) != _remaining_numbers(display):
        return None
    if _conditions(clean) != _conditions(display):
        return None
    return display


def monetary_source_rows(text: str) -> list[str]:
    """Keep wrapped financial notes with their amount through a real paragraph boundary."""
    rows = []
    continuing_note = False
    for raw in text.splitlines():
        line = raw.strip()
        # A primary notice can append application forms and filled examples. Their
        # sample funding is never current notice evidence, even in the same file.
        if re.search(r"작성\s*(?:예시|요령)|기입\s*예시|참고용|견본", line) or re.match(
            r"^[\[【<( ]*(?:붙임|별첨|첨부|서식|양식)\s*\d+[^\n]{0,50}"
            r"(?:사업계획서|신청서|제안서)", line,
        ):
            break
        if not line:
            continuing_note = False
            continue
        note = bool(re.match(
            r"^\s*[□■○ㅇo•∙·-]*\s*(?:[※*]|단[,，:]|다만|예외|자부담|부담금|"
            r"지원\s*기간|연구개발\s*기간|[’‘]?\d{2,4}년\s*(?:정부)?예산.{0,25}(?:따라|시))",
            line,
        ))
        boundary = bool(re.match(r"^(?:[□■○●ㅇ•▶▸]|\d+[.)]?\s+[가-힣]|[-–]\s+)", line))
        if rows and (note or continuing_note and not boundary):
            rows[-1] += " " + line
        else:
            rows.append(line)
        continuing_note = (note or continuing_note and not boundary) and not bool(re.search(
            r"(?:[다요][.!?]|있음[.]?|가능[.]?|한함[.]?|바람[.]?)$", line,
        ))
    return rows


def monetary_claim_supported(source: str, claim: str) -> bool:
    """Verify saved monetary prose against one current source span, never a global number bag."""
    if not readable_amount_source(source):
        return False
    claimed = _quantity_items(claim)
    actual = _quantity_items(source)
    money = [fact for fact in claimed if fact[1] == "원"]
    if not money or not Counter(claimed) <= Counter(actual):
        return False
    if not _remaining_numbers(claim) <= _remaining_numbers(source):
        return False
    if _conditions(claim) != _conditions(source):
        return False
    # Policy before/after amounts and separately scoped amounts keep their order.
    available = iter(fact for fact in actual if fact[1] == "원")
    return all(any(fact == expected for fact in available) for expected in money)


def deduplicate_amounts(values: list[str]) -> list[str]:
    """Combine spelling duplicates; keep different scopes and qualifiers distinct."""
    result = []
    seen = set()
    for value in values:
        key = (
            tuple(sorted(_quantity_facts(value).items())),
            tuple(sorted(_conditions(value))),
            tuple(sorted(_remaining_numbers(value).items())),
        )
        if not key[0] or key in seen:
            continue
        seen.add(key)
        result.append(value)
    return result


def amount_covers(complete: str, candidate: str) -> bool:
    """A validated combined item can replace its own repeated source-only parts."""
    facts = _quantity_facts(candidate)
    return bool(facts and facts <= _quantity_facts(complete)
                and _remaining_numbers(candidate) <= _remaining_numbers(complete)
                and _conditions(candidate) <= _conditions(complete))


def recover_amount_quote(source: str, fragments: list[str]) -> str | None:
    """Restore one short original span from exact, ordered, unambiguous fragments."""
    if not fragments:
        return None
    if len(fragments) == 1:
        fragment = fragments[0]
        has_scoped_money = any(
            unit == "원" and scope
            for (_, unit, _, scope, _, _), count in _quantity_facts(fragment).items() if count
        )
        if len(_compact(fragment)) < 15 or not has_scoped_money:
            return None
    positions = [index for index, character in enumerate(source) if not character.isspace()]
    compact = "".join(source[index] for index in positions)
    spans = [(0, None)]
    for fragment in fragments:
        needle = _compact(fragment)
        if not needle:
            return None
        following = []
        for cursor, start in spans:
            offset = compact.find(needle, cursor)
            while offset >= 0:
                first = offset if start is None else start
                end = offset + len(needle)
                if positions[end - 1] + 1 - positions[first] <= 700:
                    following.append((end, first))
                offset = compact.find(needle, offset + 1)
                if len(following) > 20:
                    return None
        spans = following
        if not spans:
            return None
    ranges = set((positions[start], positions[end - 1] + 1) for end, start in spans)
    if len(ranges) != 1:
        return None
    start, end = ranges.pop()
    quote = source[start:end]
    # Do not bridge separately named programs or tracks just to repair a quotation.
    identities = [value for value in _conditions(quote)
                  if value.startswith(("program:", "track:"))]
    if len(identities) > 1:
        return None
    return quote
