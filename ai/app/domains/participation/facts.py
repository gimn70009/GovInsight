"""Bounded fact comparison, independent of prose and without model calls.

Known aliases may change; values stay attached to their metric and applicant.
Unrecognized numeric clauses retain their original order rather than being guessed.
"""

import re
import unicodedata
from collections import Counter
from decimal import Decimal

ALIASES = (
    (r"주관연구개발기관|주관연구기관", "주관기관"),
    (r"공동연구개발기관|공동연구기관", "공동기관"),
    (r"중소기업자", "중소기업"),
    (r"중소/중견기업", "중소기업또는중견기업"),
    (r"기업인경우는?|기업일경우|기업이면", "기업조건"),
    (
        r"인정(?:되지는|되지|하지는|하지)않(?:습니다|는다|는|음)?|인정할수없(?:습니다|는|음)?|미인정|인정불가",
        "불인정",
    ),
    (r"불인정대상", "불인정"),
    (r"허용되지않(?:습니다|는다|는|음)?|불허", "허용금지"),
    (r"없어야|없는|없어", "없음"),
    (r"혹은|거나", "또는"),
    (r"전부충족|동시에충족", "모두충족"),
    (r"(?<!직)전년도", "직전년도"),
    (r"접수마감일(?:현재|기준)", "접수마감일"),
    (r"이내", "이하"),
)
LAW = re.compile(
    r"[「『｢]([^」』｣\n]{2,100}(?:법|법률))[」』｣](시행령|시행규칙)?"
    r"((?:제\d+조(?:의\d+)?)(?:제?\d+(?:항|호)(?:의\d+)?)*)?"
)
ARTICLE = re.compile(r"제\d+조(?:의\d+)?(?:제?\d+(?:항|호)(?:의\d+)?)*")
ROLE = re.compile(r"주관기관|공동기관|수혜기업|운영기관|수행기관|[가-힣A-Za-z]{2,12}과제")
ENTITY = re.compile(
    r"기업부설연구소|연구개발전담부서|비영리법인|법인사업자|개인사업자|"
    r"중소기업|중견기업|대기업|창업기업|소상공인|소공인|공공기관|민간기관|전문기관|"
    r"상권활성화구역|골목형상점가|전통시장|상점가|상인연합회|상인회|협동조합|"
    r"대학교|대학|연구기관|사업자단체|단체|제조업|서비스업|건설업|"
    r"한국산업기술진흥협회|글로벌강소기업"
)
METRIC = re.compile(
    r"직접수출액?|수출액?|수출실적|매출액?|부채비율|유동비율|"
    r"상인회비납부율|상인회가입률|가맹률|가입률|계약실적|납품실적|"
    r"상시근로자수|근로자수|업력|창업|설립|영업점포"
)
NUMBER = re.compile(
    r"(?P<prefix>[A-Z]?)(?P<value>\d+(?:,\d{3})*(?:\.\d+)?)"
    r"(?P<unit>억원|만원|천원|원|만불|불|달러|년|개월|%|점|건|명|개사|개|종)?"
)
COMPARE = {
    "이상": "GE",
    "≥": "GE",
    ">=": "GE",
    "이하": "LE",
    "≤": "LE",
    "<=": "LE",
    "초과": "GT",
    ">": "GT",
    "미만": "LT",
    "<": "LT",
}
CRITICAL = re.compile(
    r"불인정|허용금지|없음|않|제외|불가|예외|단서|또는|모두|하나|무관|한정|기업조건|"
    r"등록|출원|실시권|주된|공고일|접수일|마감일|개최일|직전년도|최근|전일|이전|이후|"
    r">=|<=|≥|≤|>|<|이상|이하|초과|미만"
)
NEGATIVE = {"불인정", "허용금지", "없음", "않", "제외", "불가"}
ANCHOR = re.compile(ENTITY.pattern + r"|특허|인증|신용등급|신용평가등급")
GRADE = re.compile(r"[A-D]{1,3}[+-]?(?![A-Za-z])")


def compact(value):
    value = unicodedata.normalize("NFKC", value)
    value = re.sub(r"(^|[\s.※*;(])(?:단|다만)(?:[,，]\s*|\s+)", r"\1단서", value)
    return re.sub(r"\s+", "", value)


def normalize(value):
    value = compact(value)
    for pattern, replacement in ALIASES:
        value = re.sub(pattern, replacement, value)
    return value


def legal_content(text, quote):
    """Allow omitted citations, never a changed cited provision or an invented law."""
    text, quote = compact(text), compact(quote)
    source_refs = list(LAW.finditer(quote))
    allowed_names = {m[1] + (m[2] or "") for m in source_refs}
    for match in LAW.finditer(text):
        if match[1] + (match[2] or "") not in allowed_names:
            return None
    for match in source_refs:
        name = match[1] + (match[2] or "")
        provision = match[3] or ""
        # Remove decorations but retain every condition outside the legal reference.
        text = re.sub(r"[「」『』｢｣]", "", text)
        plain = name + provision
        text = text.replace(plain, "")
        if provision:
            # A name without its article may be abbreviated in the main sentence.
            text = re.sub(re.escape(name) + r"(?!제\d)", "", text)
    quote = LAW.sub("", quote)
    names = re.compile(r"[가-힣]{2,}법(?:시행령|시행규칙)?(?=제\d|에|$|[.,()])")
    if not set(names.findall(text)).issubset(set(names.findall(quote))):
        return None
    if not set(ARTICLE.findall(text)).issubset(set(ARTICLE.findall(quote))):
        return None
    return normalize(text), normalize(quote)


def previous(pattern, value, position):
    matches = list(pattern.finditer(value, 0, position))
    return matches[-1].group() if matches else ""


def metric_name(value):
    return {
        "매출액": "매출",
        "직접수출액": "직접수출",
        "수출액": "수출",
        "수출실적": "수출",
        "설립": "업력",
        "창업": "업력",
    }.get(value, value)


def quantities(value):
    known, unknown = [], []
    for number in NUMBER.finditer(value):
        metric = metric_name(previous(METRIC, value, number.start()))
        role = previous(ROLE, value, number.start())
        tail = value[number.end() :]
        comparison = re.match(r"(?:은|는|이|가)?(이상|이하|초과|미만|>=|<=|≥|≤|>|<)", tail)
        before = re.search(r"(>=|<=|≥|≤|>|<)$", value[: number.start()])
        operator = COMPARE[(comparison or before)[1]] if comparison or before else ""
        amount = format(Decimal(number["value"].replace(",", "")).normalize(), "f")
        item = (role, metric, number["prefix"], amount, number["unit"] or "", operator)
        (known if metric else unknown).append(item)
    return Counter(known), unknown


def entities(value):
    return {(previous(ROLE, value, m.start()), m.group()) for m in ENTITY.finditer(value)}


def qualifiers(value):
    result = []
    for match in CRITICAL.finditer(value):
        term = match.group()
        anchor = previous(ANCHOR, value, match.start()) if term in NEGATIVE else ""
        result.append((previous(ROLE, value, match.start()), anchor, COMPARE.get(term, term)))
    return Counter(result)


def alternatives(value):
    # Compare each OR branch separately so AND/OR regrouping cannot pass.
    if "또는" not in value:
        return None
    result = []
    for branch in value.split("또는"):
        known, unknown = quantities(branch)
        result.append(
            (
                tuple(sorted(known.items())),
                tuple(unknown),
                tuple(sorted(entities(branch))),
                tuple(sorted(qualifiers(branch).items())),
                tuple(GRADE.findall(branch)),
            )
        )
    return Counter(result)


def equivalent(text, quote):
    pair = legal_content(text, quote)
    if pair is None:
        return False
    text, quote = pair
    return (
        quantities(text) == quantities(quote)
        and alternatives(text) == alternatives(quote)
        and entities(text) == entities(quote)
        and set(ROLE.findall(text)) == set(ROLE.findall(quote))
        and qualifiers(text) == qualifiers(quote)
        and GRADE.findall(text) == GRADE.findall(quote)
        and ("=" in re.sub(r">=|<=", "", text)) == ("=" in re.sub(r">=|<=", "", quote))
        and all((symbol in text) == (symbol in quote) for symbol in ("±", "×"))
    )
