"""Source-grounded opportunity fields for reports, independent of document kind."""

import re
from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, Field

from app.domains.report.amounts import (
    amount_covers,
    deduplicate_amounts,
    monetary_source_rows,
    readable_amount_source,
    recover_amount_quote,
    validated_amount_display,
)
from app.domains.report.applicants import checkbox_applicants
from app.domains.report.outline import outline_display_lines
from app.domains.report.schemas.request import ReportDocumentRequest
from app.domains.report.submission_documents import _parts, source_priority

BUSINESS_TYPES = {
    "R&D·기술개발": r"R&D|연구개발|기술개발|공동연구",
    "실증·도입": r"실증|테스트베드|PoC|솔루션.{0,10}도입",
    "창업·성장": r"창업|스케일업|액셀러레이",
    "사업화·판로": r"사업화|제품화|판로|마케팅|전시회",
    "수출·해외진출": r"수출|해외진출|해외시장",
    "자금·금융": r"정책자금|융자|보증|투자유치|금융지원",
    "인력·고용": r"채용|인건비|인력|고용|인재양성",
    "경영·전문지원": r"컨설팅|인증|특허|법률|회계",
    "공모·경진대회": r"경진대회|공모전|포상|아이디어.{0,10}공모",
    "용역·조달": r"용역|조달|입찰|물품.{0,10}(?:구매|공급)",
    "위탁운영": r"위탁|운영기관|수탁기관|운영사",
    "공간·입주": r"입주|사무공간|보육시설",
}
BUSINESS_METHODS = {
    "사업비 지원": r"사업비.{0,12}지원|지원금|보조금|출연금|연구개발비",
    "연구개발비 지원": r"정부지원연구개발비|연구개발비.{0,12}지원|연구개발비.{0,12}출연",
    "보조금": r"보조금",
    "출연금": r"출연금",
    "바우처": r"바우처",
    "융자": r"융자|대출",
    "보증": r"보증(?:지원|제공)|신용보증|기술보증",
    "투자": r"직접.{0,8}투자|투자금.{0,8}(?:지원|제공)|지분.{0,8}투자",
    "투자유치 연계": r"투자유치.{0,8}(?:연계|지원)|투자.{0,8}연계",
    "컨설팅·교육": r"(?:컨설팅|교육).{0,12}(?:지원|제공|수강|참여)",
    "현물·시설": r"(?:현물|장비|시설|공간).{0,10}(?:지원|제공|이용)",
    "용역·구매계약": r"용역|물품.{0,8}(?:구매|공급)|입찰",
    "위탁·운영협약": r"위탁|수탁|운영협약",
    "상금·포상": r"상금|포상|시상금",
}
OPPORTUNITY_FIELDS = (
    "notice_kinds", "business_types", "business_methods", "amounts", "participation_roles",
)


class ClassificationEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(max_length=30)
    quote: str = Field(min_length=3, max_length=700)


class ClassifiedFact(BaseModel):
    model_config = ConfigDict(extra="forbid")
    value: str = Field(min_length=2, max_length=40)
    evidence: ClassificationEvidence


@dataclass(frozen=True)
class OpportunityFacts:
    notice_kind: str = "확인 필요"
    business_type: str | None = None
    business_method: str | None = None
    amount: str | None = None
    participation_role: str | None = None


_PROVIDER = re.compile(
    r"(?:위탁|수탁|운영)(?:기관|업체|기업|사|자)?.{0,12}(?:모집|선정|공모)"
)
_SERVICE_PROVIDER = re.compile(r"(?:수행|공급|용역)(?:기관|업체|기업|사).{0,12}(?:모집|선정|공모)")
_ORDINARY = re.compile(
    r"(?:선정|심사|평가|합격|입찰|공모).{0,12}결과|보도자료|정책.{0,12}(?:발표|동향)"
    r"|행정예고|입법예고|인사발령|휴무|(?:시스템|서비스|홈페이지).{0,12}(?:점검|중단)"
    r"|개정.{0,8}안내"
)
_ACTION = re.compile(r"모집|공모|신청|접수|입찰|시행계획|(?:지원|사업|과제).{0,25}공고")
_AMOUNT = re.compile(
    r"(?:지원\s*(?:규모|금액|한도|금)|사업비|연구개발비|보조금|출연금|"
    r"사업예산|예산액|추정(?:금액|가격)|기초금액|계약(?:예산|금액|규모)|상금|자부담|부담금)"
)
_MONEY = re.compile(r"\d[\d,.]*\s*(?:천|만|백만|천만|억)?\s*원|\d[\d,.]*\s*%")
_CONDITION = re.compile(
    r"^\s*[□■○ㅇo•∙·-]*\s*(?:[※*]|단[,，:]|다만|예외|자부담|부담금|"
    r"지원\s*기간|연구개발\s*기간|\d{4}년\s*(?:정부)?예산.{0,25}(?:따라|시))"
)
_UNSAFE = re.compile(r"일부 원문 생략|작성\s*예시|참고용|견본")


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def _matches(patterns: dict[str, str], value: str) -> list[str]:
    matches = []
    for label, pattern in patterns.items():
        for match in re.finditer(pattern, value, re.I):
            before = re.split(r"[,;]\s*|\n", value[max(0, match.start() - 12):match.start()])[-1]
            after = re.split(r"[,;]\s*|\n", value[match.end():match.end() + 18])[0]
            surrounding = before + match[0] + after
            if label == "현물·시설" and re.search(r"부담|납부|산정|계상", surrounding):
                continue
            if re.search(
                r"(?:지원\s*)?(?:없음|제외|미제공|불가)|(?:지원|제공)하지|(?:지원|제공)\s*않",
                surrounding,
            ):
                continue
            matches.append(label)
            break
    return matches


def _business(value: str) -> bool:
    return bool(_ACTION.search(value) and _matches(BUSINESS_TYPES, value))


def _source_rows(text: str) -> list[str]:
    rows = []
    for line in re.split(r"\r?\n|(?<=[다요]\.)\s+", text):
        line = line.strip()
        if not line:
            continue
        if rows and _CONDITION.match(line):
            rows[-1] += " " + line
        else:
            rows.append(line)
    return rows


def participation_display_lines(value: str) -> list[str]:
    """Keep participation conditions, excluding separately marked budget-only notes."""
    checkbox = checkbox_applicants(value)
    if checkbox is not None:
        return [" ".join(outline_display_lines(line)) for line in checkbox]
    result = []
    for line in outline_display_lines(value):
        budget_note = re.match(
            r"^(?:(?:단|다만)[,，:]?\s*)?(?:지원\s*(?:예산|기간)|신규\s*예산|"
            r"(?:정부)?예산(?:액)?)(?=[은는이가을를\s:：]|및)", line,
        )
        participation = re.search(r"참여|신청|자격|컨소시엄|공동수급|기업|기관|법인", line)
        if budget_note and not participation:
            continue
        result.append(line)
    return result


def _participation_rows(text: str) -> list[str]:
    rows = []
    for line in re.split(r"\r?\n|(?<=[다요]\.)\s+", text):
        line = line.strip()
        if not line:
            continue
        if rows and _CONDITION.match(line):
            # Preserve the original row boundary so an attached financial note
            # can be distinguished from the preceding participation requirement.
            rows[-1] += "\n" + line
        else:
            rows.append(line)
    return rows


def _amount_rows(text: str) -> list[str]:
    rows = monetary_source_rows(text)
    candidates = []
    core = re.compile(
        r"지원\s*(?:규모|금액|한도|금)|신규\s*예산|사업\s*예산|예산액|"
        r"추정(?:가격|금액)|기초금액|계약(?:예산|금액|규모)|과제당|기업당|업체당|"
        r"^\s*[○ㅇo•-]*\s*(?:사업비|상금|보조금|출연금|자부담|부담금)\s*[:：]"
    )
    for index, row in enumerate(rows):
        if not core.search(row):
            continue
        if not _MONEY.search(row) and index + 1 < len(rows) and _MONEY.search(rows[index + 1]):
            row += " " + rows[index + 1]
        # Standalone per-project/enterprise caps are unambiguous even in a table.
        # Retain nearby explicit budget/duration notes without borrowing other cells.
        if re.search(r"과제당|기업당|업체당", row):
            for following in rows[index + 1:index + 7]:
                if re.match(r"^\d+[.)]\s*[가-힣]", following):
                    break
                notes = re.finditer(
                    r"[※*]\s*지원(?:예산|기간).*?"
                    r"(?=\s+(?:[※*○●•]|-\s+(?=[가-힣]))|$)", following,
                )
                for note in notes:
                    if note[0] not in row:
                        # Copy the complete note through its actual bullet boundary.
                        # The complete field limit below rejects oversized notes.
                        row += " " + note[0]
        if re.search(r"신규\s*예산", row):
            for following in rows[index + 1:index + 25]:
                if re.match(r"^\d+[.)]\s*[가-힣]", following):
                    break
                note = re.search(
                    r"[※*]\s*신규예산은.*?(?=\s+(?:[※*○●•]|-\s+(?=[가-힣]))|$)", following,
                )
                if note and note[0] not in row:
                    row += " " + note[0]
        if (_MONEY.search(row) and len(row) <= 500 and not _UNSAFE.search(row)
                and not re.search(r"작성\s*요령|인건비|채용.{0,15}기준", row)):
            candidates.append(row)
    return candidates


def _provider_amount(value: str) -> bool:
    return bool(re.search(
        r"운영(?:사업비|비)|위탁(?:사업비|운영비)|추정(?:가격|금액)|기초금액|"
        r"계약(?:예산|금액|규모)|^\s*[○ㅇo•-]*\s*사업비\s*[:：]", value,
    ))


def _designation_notice(title: str, text: str) -> bool:
    if not re.search(r"지정(?:\s*(?:변경|취소))?\s*고시(?:\s|$)", title):
        return False
    if re.search(r"모집|공모|신청|접수|계획", title):
        return False
    # A designation notification can quote the old competition. Only explicit
    # current application directions override the completed administrative act.
    return not re.search(
        r"지정(?:을\s*)?(?:받기|희망)|지정\s*신청|모집합니다|공모합니다|"
        r"신청하시기|신청하세요|접수합니다", text,
    )


def source_opportunity_facts(document: ReportDocumentRequest) -> OpportunityFacts:
    parts = [p for p in _parts(document) if source_priority(p) == 0]
    text = "\n".join(p.text for p in parts)
    # A stored GENERAL_NOTICE can still be a real R&D solicitation.
    title = document.title
    follow_on = bool(re.search(r"추가\s*모집|재공모|신규\s*모집", title))
    staff_recruitment = bool(
        re.search(r"(?:기간제|공무직|직원|근로자|인턴|경력직|연구원).{0,15}채용", title)
        and re.search(r"응시|채용\s*인원|근무\s*조건", text)
        and not re.search(r"지원|사업|용역|입찰", title)
    )
    ordinary = ((bool(_ORDINARY.search(title)) and not follow_on) or staff_recruitment
                or _designation_notice(title, text))
    kind = "일반 공지" if ordinary else (
        "사업 기회" if _business(title) or _business(text) else "확인 필요"
    )
    if kind != "사업 기회":
        return OpportunityFacts(notice_kind=kind)
    types = _matches(BUSINESS_TYPES, title)
    provider = _PROVIDER.search(title)
    service_provider = _SERVICE_PROVIDER.search(title)
    business_type = (
        "위탁운영" if provider else "용역·조달" if service_provider
        else "R&D·기술개발" if re.search(r"연구개발\s*과제|기술개발사업", title)
        else types[0] if len(types) == 1 else None
    )
    amounts = list({_compact(row): row for p in reversed(parts)
                    for row in _amount_rows(p.text)}.values())
    purpose_rows = [row for p in parts for row in _source_rows(p.text)[:10]
                    if len(row) <= 250 and not _UNSAFE.search(row)]
    methods = _matches(BUSINESS_METHODS, "\n".join([title, *purpose_rows, *amounts]))
    if business_type == "R&D·기술개발" and "연구개발비 지원" not in methods:
        funding = {"연구개발비 지원": BUSINESS_METHODS["연구개발비 지원"]}
        if any(_matches(funding, row) for p in parts for row in _source_rows(p.text)
               if "정부지원연구개발비" in row and len(row) <= 250):
            methods.append("연구개발비 지원")
    if business_type == "공모·경진대회":
        methods = [method for method in methods if method == "상금·포상"]
    if "연구개발비 지원" in methods or "보조금" in methods or "출연금" in methods:
        methods = [m for m in methods if m != "사업비 지원"]
    # Provider procurement does not imply that the provider receives the end-user service.
    if provider or business_type == "용역·조달":
        amounts = [value for value in amounts if _provider_amount(value)]
        methods = [m for m in methods if m in {"위탁·운영협약", "용역·구매계약"}]
    role = provider[0] if provider else service_provider[0] if service_provider else None
    if service_provider and not methods:
        methods = ["용역·구매계약"]
    amounts = deduplicate_amounts([clean for value in amounts
                                   if (clean := readable_amount_source(value))])
    amount = "\n".join(amounts) if amounts and len("\n".join(amounts)) <= 900 else None
    return OpportunityFacts(
        kind, business_type, " + ".join(methods) if 0 < len(methods) <= 3 else None,
        amount, role,
    )


def _amount_evidence(item, quoted: str, source: str) -> str | None:
    fragments = item.fragments or [item.text]
    cursor = 0
    compact_quote = _compact(quoted)
    for fragment in fragments:
        offset = compact_quote.find(_compact(fragment), cursor)
        if offset < 0:
            return None
        cursor = offset + len(_compact(fragment))
    trusted_source = _compact("\n".join(monetary_source_rows(source)))
    if any(_compact(fragment) not in trusted_source for fragment in fragments):
        return None
    complete_rows = _amount_rows(source)
    matched = []
    for fragment in fragments:
        candidates = [row for row in complete_rows if _compact(fragment) in _compact(row)]
        if not candidates:
            # An explicit scope note may occur below a funding table rather than
            # immediately beside the amount. It remains in the cited quotation.
            if re.match(
                r"^[□■○ㅇo•※*]*\s*(?:신규예산|지원예산|지원기간|지원과제\s*수|자부담|부가세)",
                fragment,
            ):
                candidates = [fragment]
            else:
                candidates = [row for row in complete_rows if _compact(row) in _compact(fragment)]
        clean = list(dict.fromkeys(value for row in candidates
                                  if (value := readable_amount_source(row))))
        if not clean:
            return None
        # Heading-only duplicates can yield the same value twice. Distinct
        # adjacent conditions cannot be dropped to make a candidate fit.
        if len(clean) > 1:
            keys = deduplicate_amounts(clean)
            if len(keys) != 1 and not all(
                _compact(row) in _compact(fragment) for row in candidates
            ):
                return None
            clean = keys or clean
        for value in clean:
            if value not in matched and not any(
                _compact(value) in _compact(prior) for prior in matched
            ):
                matched.append(value)
    return " ".join(matched) or None


def validate_opportunity(output, context, document, issues: list[str]) -> OpportunityFacts:
    fallback = source_opportunity_facts(document)

    def quote(item, field):
        source = context.sources.get(item.evidence.source_id)
        value = item.evidence.quote
        if not source or source_priority(source) != 0 or _UNSAFE.search(value):
            issues.append(f"{field}:unsupported_quote")
            return None
        if _compact(value) not in _compact(source.text):
            if field == "amount":
                recovered = recover_amount_quote(source.text, item.fragments)
                if recovered and not _UNSAFE.search(recovered):
                    return recovered
            issues.append(f"{field}:unsupported_quote")
            return None
        return value

    nature = fallback.notice_kind
    for item in output.notice_kinds:
        value = quote(item, "notice_kind")
        if not value:
            continue
        supported = (
            item.value == "사업 기회" and (_business(value) or nature == "사업 기회")
            or item.value == "일반 공지" and (
                _ORDINARY.search(value) or nature == "일반 공지"
            )
        )
        if supported and (nature == "확인 필요" or item.value == nature):
            nature = item.value
        elif item.value != "확인 필요":
            issues.append("notice_kind:unsupported_classification")
    if nature != "사업 기회":
        return OpportunityFacts(notice_kind=nature)
    business_type = fallback.business_type
    for item in output.business_types:
        value = quote(item, "business_type")
        if value and item.value in _matches(BUSINESS_TYPES, value):
            if not (_PROVIDER.search(document.title) or _SERVICE_PROVIDER.search(document.title)):
                business_type = item.value
        elif value:
            issues.append("business_type:unsupported_classification")
    methods = []
    for item in output.business_methods:
        value = quote(item, "business_method")
        if value and item.value in _matches(BUSINESS_METHODS, value):
            if business_type in {"위탁운영", "용역·조달"} and item.value not in {
                "위탁·운영협약", "용역·구매계약",
            }:
                issues.append("business_method:wrong_participation_role")
            elif business_type == "공모·경진대회" and item.value != "상금·포상":
                issues.append("business_method:wrong_participation_role")
            elif item.value not in methods:
                methods.append(item.value)
        elif value:
            issues.append("business_method:unsupported_classification")
    amounts = []
    roles = []
    for field, items, values in (
        ("amount", output.amounts, amounts),
        ("participation_role", output.participation_roles, roles),
    ):
        for item in items:
            value = quote(item, field)
            if not value:
                continue
            source = context.sources[item.evidence.source_id].text
            if field == "amount":
                selected = context.sources[item.evidence.source_id]
                identity = (selected.name, selected.url, selected.archive)
                originals = [part for part in _parts(document)
                             if (part.name, part.url, part.archive) == identity
                             and source_priority(part) == 0]
                # Selection may omit the preceding example/form heading or a
                # following budget condition. Verify the complete matching file.
                complete_values = [_amount_evidence(item, value, part.text) for part in originals]
                complete = complete_values[0] if complete_values and all(complete_values) else None
                if not complete:
                    issues.append("amount:incomplete_context")
                    continue
                if business_type in {"위탁운영", "용역·조달"} and not _provider_amount(complete):
                    issues.append("amount:wrong_participation_role")
                    continue
                display = validated_amount_display(complete, item.display_text)
                if item.display_text is not None and display is None:
                    issues.append("amount:unsafe_display")
                    continue
                display = display or readable_amount_source(complete)
                if display and display not in values:
                    values.append(display)
                continue
            rows = _participation_rows(source)
            raw = " ".join(item.fragments) if item.fragments else item.text
            if _compact(raw) not in _compact(value):
                issues.append(f"{field}:unsupported_value")
                continue
            candidates = [row for row in rows if _compact(raw) in _compact(row)]
            if len(candidates) != 1:
                issues.append(f"{field}:incomplete_context")
                continue
            value = candidates[0]
            if field == "amount" and business_type in {"위탁운영", "용역·조달"}:
                if not _provider_amount(value):
                    issues.append("amount:wrong_participation_role")
                    continue
            if field == "participation_role" and not re.search(
                r"참여\s*역할|(?:모집|신청|지원|참여)\s*대상|"
                r"(?:주관|공동|수행|운영|수탁|공급)(?:기관|기업|업체|사)|컨소시엄", value,
            ):
                issues.append(f"{field}:missing_role")
                continue
            value = "\n".join(participation_display_lines(value))
            if value and len(value) <= 500 and value not in values:
                values.append(value)
    # Keep accepted prose first; supplement only genuinely distinct funding facts.
    amount = "\n".join(deduplicate_amounts([
        *amounts, *(row for row in (fallback.amount or "").splitlines()
                    if not any(amount_covers(value, row) for value in amounts)),
    ])) or None
    method = " + ".join(methods) or fallback.business_method
    if business_type in {"위탁운영", "용역·조달"}:
        if amount:
            amount = "\n".join(row for row in amount.splitlines()
                               if row in amounts or _provider_amount(row)) or None
        if method:
            method = " + ".join(value for value in method.split(" + ")
                                if value in {"위탁·운영협약", "용역·구매계약"}) or None
    if business_type == "공모·경진대회" and method:
        method = "상금·포상" if "상금·포상" in method.split(" + ") else None
    role = "\n".join(roles) or fallback.participation_role
    return OpportunityFacts(
        nature, business_type, method,
        amount if amount and len(amount) <= 900 else None,
        role if role and len(role) <= 900 else None,
    )


def opportunity_fields_needed(document: ReportDocumentRequest) -> tuple[str, ...]:
    """Never skip the model just because the old submission fields are complete."""
    facts = source_opportunity_facts(document)
    if facts.notice_kind == "일반 공지":
        return ()
    known = {
        "notice_kinds": facts.notice_kind == "사업 기회",
        "business_types": bool(facts.business_type),
        "business_methods": bool(facts.business_method),
        # Raw extracted amounts still need the same call's readable display prose.
        "amounts": False,
        "participation_roles": bool(facts.participation_role),
    }
    return tuple(name for name in OPPORTUNITY_FIELDS if not known[name])
