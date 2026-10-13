"""Purpose-first report prose; remove only independently covered administrative details."""

import re

from app.domains.report.amounts import amount_covers, monetary_claim_supported, monetary_source_rows
from app.domains.report.presentation import _anchors, submission_display_lines
from app.domains.report.schedules import schedule_fields
from app.domains.report.submission_documents import _parts, source_priority

_MONETARY_CLAIM = re.compile(r"\d[\d,.]*\s*(?:천만|백만|억|만|천)?\s*원")
_UNTRUSTED_SOURCE = re.compile(r"작성\s*예시|작성\s*요령|참고용|견본|일부 원문 생략")
_UNVERIFIED_MONEY = "요약의 금액과 지원 범위는 원문 확인이 필요합니다."

_SENTENCES = re.compile(r"(?<=[다요])[.!?]\s+|[\r\n]+")
_MONEY_START = re.compile(
    r"^(?:(?:각|선정된)\s*)?(?:(?:과제|기업|업체)(?:당|별)|정부\s*(?:지원금|출연금)|"
    r"지원\s*(?:규모|한도|금액|예산|금)|총\s*(?:사업비|예산)|사업비|"
    r"연구개발비|과제\s*기간|연구개발\s*기간|지원\s*기간)"
)
_DEADLINE_START = re.compile(
    r"^(?:(?:국문|영문|국내|해외|한국|일본)\s*)?"
    r"(?:의견\s*제출|신청|접수|제출)\s*(?:기간|기한|마감)|^(?:마감일|제출일)"
)
_ROUTE_START = re.compile(r"^(?:의견\s*제출|접수|신청|제출)(?:\s*(?:방법|방식))?(?:은|는|:|：)")
_DETAIL_AFTER_COMMA = re.compile(
    r"[,;]\s*(?=(?:(?:과제|기업|업체)(?:당|별)|정부\s*(?:지원금|출연금)|"
    r"지원\s*(?:규모|한도|금액|예산|금)|과제\s*기간|연구개발\s*기간|"
    r"(?:국문|영문|국내|해외)?\s*(?:의견\s*제출|신청|접수|제출)\s*(?:기간|기한|마감|방법)))"
)
_MEANING = re.compile(
    r"변경|개정|확대|축소|연장|전환|폐지|정책|목적|목표|위해|위한|통해|"
    r"사업화|공동연구|기술개발을|분야|RFP|실증을|연구실|모집대상|지원대상|"
    r"신청\s*(?:자격|대상)|(?:기업|기관).{0,12}(?:신청|참여)|평가기준|요건|부채|매출|영업"
)
_CONDITION = re.compile(r"다만|단[,，:]|예외|경우|조건|불가|제외|필수|동시|반드시")
_SCOPE = re.compile(r"국문|영문|국내|해외|한국|일본|\d+차")
_CHANNEL = re.compile(r"전자우편|이메일|온라인|우편|방문")


def _valid(value: str) -> bool:
    return bool(value and not re.search(r"원문\s*확인|확인\s*필요|확인하지\s*못", value))


def _route_names(value: str) -> list[str]:
    value = re.sub(r"(?:접수|신청|제출|진행)합니다", "", value)
    ignored = {
        "접수", "신청", "제출", "의견", "방법", "방식", "기간", "기한", "마감", "마감일",
        "온라인", "우편", "전자우편", "이메일", "방문", "또는", "혹은", "후", "이후",
        "년", "월", "일", "시", "분", "오전", "오후",
    }
    names = []
    for token in re.findall(r"[가-힣A-Za-z][가-힣A-Za-z-]*", value):
        base = re.sub(r"(?:으로|에서|이며|이고|은|는|을|를|로|이|가)$", "", token)
        if base and base not in ignored:
            names.append(re.sub(r"[^가-힣a-z]", "", base.casefold()))
    return names


def _same_route(value: str, destination: str) -> bool:
    if not _valid(destination) or len(destination) > 300:
        return False
    identity = re.sub(r"[^가-힣a-z]", "", destination.casefold())
    if any(name not in identity for name in _route_names(value)):
        return False
    modes = _CHANNEL.findall(value)
    destination_modes = _CHANNEL.findall(destination)

    def normalize(values):
        return ["이메일" if mode == "전자우편" else mode for mode in values]

    if normalize(modes) != normalize(destination_modes):
        return False
    if len(modes) > 1:
        # Alternatives and sequential obligations are not interchangeable.
        for pattern in (r"또는|혹은", r"후|이후", r"동시|함께|병행"):
            if bool(re.search(pattern, value)) != bool(re.search(pattern, destination)):
                return False
    return True


def _scopes_match(claim: str, row: str, inherited: str = "") -> bool:
    claimed = set(_SCOPE.findall(claim)) or set(_SCOPE.findall(inherited))
    actual = set(_SCOPE.findall(row))
    # A row containing two countries/languages cannot prove which date or route
    # belongs to either one. Keep the original summary in that ambiguous case.
    roles = [scope for scope in claimed if not re.fullmatch(r"\d+차", scope)]
    return len(roles) <= 1 and claimed == actual


def _covered_deadline(value: str, facts) -> bool:
    deadline_claim, route_claim = value, None
    route_start = re.search(r"전자우편|이메일|온라인|우편|방문|https?://|@", value)
    if route_start:
        boundaries = [boundary for boundary in re.finditer(r"(?:이며|이고|[,;])\s*", value)
                      if boundary.end() <= route_start.start()]
        if not boundaries:
            return False
        boundary = boundaries[-1]
        deadline_claim = value[:boundary.start()]
        route_claim = value[boundary.end():]
    anchors = _anchors(deadline_claim)
    if not anchors:
        return False
    deadlines = schedule_fields(facts.deadline)["제출·의견 기한"]
    for deadline in deadlines:
        if not (_scopes_match(deadline_claim, deadline) and anchors <= _anchors(deadline)):
            continue
        if route_claim is None:
            return True
        route_anchors = _anchors(route_claim)
        for route in submission_display_lines(facts.destination, "제출처·방법"):
            if (_scopes_match(route_claim, route, inherited=deadline_claim)
                    and route_anchors <= _anchors(route) and _same_route(route_claim, route)):
                return True
    return False


def _covered(value: str, facts, opportunity) -> bool:
    if _MEANING.search(value):
        return False
    if _MONEY_START.search(value):
        return _valid(opportunity.amount or "") and amount_covers(opportunity.amount, value)
    if _CONDITION.search(value):
        # Do not remove restrictions merely because the dates or channels match.
        return False
    if _DEADLINE_START.search(value) and _valid(facts.deadline):
        return _covered_deadline(value, facts)
    if _ROUTE_START.search(value) and _valid(facts.destination):
        anchors = _anchors(value)
        modes = _CHANNEL.findall(value)
        return bool((anchors or modes) and any(
            _scopes_match(value, route) and anchors <= _anchors(route) and _same_route(value, route)
            for route in submission_display_lines(facts.destination, "제출처·방법")
        ))
    return False


def _close_clause(value: str) -> str | None:
    value = value.rstrip(" ,;")
    if re.search(r"[다요][.!?]?$", value):
        return value.rstrip(".!?") + "."
    # Complete only explicit Korean linking endings; never cut a noun phrase.
    for ending, replacement in ((r"(?:이며|이고|으로|로)$", "입니다."),
                                (r"(?:하며|하고)$", "합니다.")):
        if re.search(ending, value):
            return re.sub(ending, replacement, value)
    return None


def _money_source_spans(document) -> list[str]:
    spans = []
    for part in _parts(document):
        if source_priority(part) != 0 or not part.text.strip():
            continue
        # Neither the saved summary nor comparisonSummary is independent evidence.
        for line in monetary_source_rows(part.text):
            if (_MONETARY_CLAIM.search(line) and len(line) <= 900
                    and not _UNTRUSTED_SOURCE.search(line)):
                spans.append(line.strip())
    return spans


def _ground_monetary_sentence(sentence: str, sources: list[str]) -> str | None:
    if not _MONETARY_CLAIM.search(sentence):
        return sentence
    if any(monetary_claim_supported(source, sentence) for source in sources):
        return sentence
    # Keep a complete purpose clause when its unsupported monetary tail has a clear boundary.
    for boundary in _DETAIL_AFTER_COMMA.finditer(sentence):
        before = _close_clause(sentence[:boundary.start()])
        after = sentence[boundary.end():]
        if before and not _MONETARY_CLAIM.search(before) and _MONETARY_CLAIM.search(after):
            return before
    return None


def purpose_summary(document, facts, opportunity) -> str:
    """Ground monetary claims in current sources, then separate covered report details."""
    original = " ".join((document.summary or "").split())
    # Split at Korean sentence endings, not decimals, dates, abbreviations, or URLs.
    sentences = []
    start = 0
    for boundary in _SENTENCES.finditer(document.summary or ""):
        end = boundary.start() + (1 if boundary[0][0] in ".!?" else 0)
        sentences.append((document.summary or "")[start:end].strip())
        start = boundary.end()
    sentences.append((document.summary or "")[start:].strip())
    verified = []
    sources = _money_source_spans(document)
    for sentence in sentences:
        if sentence and (grounded := _ground_monetary_sentence(sentence, sources)):
            verified.append(grounded)
    kept = []
    for sentence in verified:
        if _covered(sentence, facts, opportunity):
            continue
        # A mixed purpose/funding sentence retains its complete research scope.
        # Only an entire covered tail after a clear delimiter can be separated.
        for boundary in _DETAIL_AFTER_COMMA.finditer(sentence):
            before = _close_clause(sentence[:boundary.start()])
            after = sentence[boundary.end():]
            if before and _covered(after, facts, opportunity):
                sentence = before
                break
        kept.append(sentence)
    # Never resurrect rejected monetary claims through the detail-only fallback.
    return " ".join(kept) or " ".join(verified) or (_UNVERIFIED_MONEY if original else "")
