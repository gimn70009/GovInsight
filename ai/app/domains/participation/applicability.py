"""Cross-source checks for explicitly linked notice exemptions and form clauses.

These checks withhold an unresolved restriction; they never invent an exemption,
change a threshold, or use a similar notice as authority.
"""

import re

from . import completeness

EXEMPTION = re.compile(r"산정(?:에서)?제외|적용(?:에서)?제외|면제|제한(?:이)?없|적용하지않")
ORGANIZATION = re.compile(
    r"(?:(?:총괄|공동|주관|참여|수행|운영|공급|도입))+?(?:연구개발)?(?:기관|기업)"
)
PERSONNEL_SUBJECT = re.compile(
    r"(?:운영인력|전담인력|상근인력|매니저|직원)(?:\([^)]*\))?(?:은|는|이|가)"
)
DIMENSIONS = {
    "project_count": r"동시수행|(?:수행|참여)(?:가능)?과제(?:수|는최대)",
    "participation_rate": r"참여율|인건비계상률",
    "debt_ratio": r"부채비율",
    "liquidity_ratio": r"유동비율",
    "company_age": r"업력|사업개시|설립후",
    "membership_count": r"가입(?:수|개수)|협의체.*(?:개이내|개까지)",
    "contribution": r"민간부담|자기부담|기업부담|자부담",
}


def dimensions(text):
    value = completeness.compact(text)
    return {key for key, pattern in DIMENSIONS.items() if re.search(pattern, value)}


def organizations(text):
    return {m.replace("연구개발", "") for m in ORGANIZATION.findall(completeness.compact(text))}


def recruitment(source):
    match = re.search(r"\[공고 대상\]([^\n]+)", source.group_label)
    return completeness.compact(match[1]) if match else ""


def linked_exemptions(source, sources):
    if "[자격 확인서]" not in source.group_label:
        return []
    context = recruitment(source)
    # Missing recruitment identity is not permission to borrow another notice's exception.
    if not context:
        return []
    return [
        s
        for s in sources
        if "[확인서 적용 예외]" in s.group_label
        and recruitment(s) == context
        and EXEMPTION.search(completeness.compact(s.excerpt))
    ]


def conflict(source, text, details, quotes, sources):
    prose = "\n".join([text, *details])
    claimed = dimensions(prose)
    for exception in linked_exemptions(source, sources):
        roles, exempt_roles = organizations(prose), organizations(exception.excerpt)
        if roles and exempt_roles and roles.isdisjoint(exempt_roles):
            continue
        relevant = claimed & dimensions(exception.excerpt)
        if not relevant:
            continue
        # An unrelated exemption cannot authorize the asserted restriction. Require
        # the linked original to be cited and its exception in the main condition.
        cited = any(
            completeness.compact(q) in completeness.compact(exception.excerpt)
            and relevant & dimensions(q)
            and EXEMPTION.search(completeness.compact(q))
            for q in quotes
        )
        preserved = bool(
            EXEMPTION.search(completeness.compact(text))
            and relevant & dimensions(text)
            and exempt_roles <= organizations(text)
            and not re.search(
                r"(?:면제|제외)(?:되지않|하지않|하지아니)", completeness.compact(text)
            )
        )
        if not cited or not preserved:
            return True
    return False


def criterion(code, text):
    """Route explicit contribution/personnel conditions without changing their facts."""
    if code == "REVENUE" and re.search(r"민간부담금|자기부담금|기업부담금|자부담", text):
        if not re.search(r"매출|수출|수입금액", text):
            return "CREDIT"
    if code == "BUSINESS_FORM" and PERSONNEL_SUBJECT.search(completeness.compact(text)):
        return "EXPERIENCE"
    return code
