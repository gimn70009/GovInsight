"""Admit submission obligations, not merely quotations, into the saved checklist.

The table reconciler remains conservative about unknown names. This final gate
keeps its verified rows, checks additional model candidates in their own source,
and returns unresolved candidates as review notes instead of mandatory files.
"""

import re
import unicodedata

from app.domains.analysis.proposals.submission_contents import linked_contents
from app.domains.analysis.proposals.submission_evidence import submission_section_contains
from app.domains.analysis.proposals.submission_names import (
    same_period_submission_source,
    same_submission_source,
    submission_name,
)
from app.domains.analysis.proposals.submission_requirements import (
    SubmissionRequirement,
    _document_key,
    _key,
    _level,
    _preserve_period_submitter,
    _sources,
    collect_submission_requirements,
    reconcile_submission_documents,
)
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import (
    PreparationChecklistItem,
    RequirementStage,
)

_DESCRIPTION = re.compile(r"\((?:기업\s*프로필|(?:회사\s*)?서명\s*[·ㆍ/]\s*직인\s*포함)\)")
_NEGATIVE = re.compile(r"제출\s*(?:불필요|면제|생략)|제출하지\s*않|참고용|작성\s*예시")
_STAGES = (
    (r"협약\s*(?:시|체결|단계)", RequirementStage.AGREEMENT),
    (r"선정\s*(?:이후|후)", RequirementStage.POST_SELECTION),
    (r"(?:운영|사업|실습)\s*(?:완료|종료)", RequirementStage.REPORTING),
    (r"(?:신청|접수)\s*(?:시|단계|서류)", RequirementStage.APPLICATION),
)


def preparation_action(title: str, quote: str, *, included_in: str = "") -> str:
    """Build an action from complete source clauses, without inventing a department."""
    last = ord(title[-1]) - ord("가")
    particle = "을" if 0 <= last <= 11171 and last % 28 else "를"
    action = f"{title}{particle} 준비합니다."
    if included_in:
        action += f" {included_in}에 포함해 준비합니다."
    notes = []
    for line in quote.splitlines():
        line = re.sub(r"^[\s*☞‣▸▶▷]+", "", line).strip()
        line = re.sub(r"^\d+[.)]\s+", "", line)
        if re.fullmatch(r"\d+[.)]?", line):
            continue
        line = re.sub(r"^" + re.escape(title) + r"\s*[▸▶▷]\s*", "", line)
        if not line or _key(line) == _key(title) or re.match(r"파일명\s*[:：]", line):
            continue
        if re.fullmatch(r"(?:hwp[x]?|pdf|docx?|xlsx?|zip)(?:/\w+)*", line, re.I):
            continue
        if re.search(r"최근|원본|사본|대조필|압축|최초|변경|경우|해당|서명|날인|직인|포함", line):
            if len(action + " " + " ".join(notes + [line])) <= 300:
                notes.append(line)
    return action + (" " + " ".join(notes) if notes else "")


def _complete_clause(quote: str, text: str) -> tuple[str, str] | None:
    """Recover a whole local clause so a shortened citation cannot hide a condition."""
    positions = []
    chars = []
    for index, char in enumerate(text):
        for normalized in unicodedata.normalize("NFKC", char).casefold():
            if normalized.isalnum():
                positions.append(index)
                chars.append(normalized)
    key = "".join(c for c in unicodedata.normalize("NFKC", quote).casefold() if c.isalnum())
    value = "".join(chars)
    start = value.find(key)
    if not key or start < 0 or value.find(key, start + 1) >= 0:
        return None
    left, right = positions[start], positions[start + len(key) - 1] + 1
    boundary = r"[\n.;○□■*]"
    previous = list(re.finditer(boundary, text[:left]))
    begin = previous[-1].end() if previous else 0
    following = re.search(boundary, text[right:])
    end = right + following.start() if following else len(text)
    tail = re.match(r"[.\s]*(?:다만|단[,\s]|※|\*)[^\n]{1,200}", text[end:])
    if tail:
        end += tail.end()
    clause = text[begin:end].strip()
    if not 5 <= len(clause) <= 300:
        return None
    return clause, text[max(0, begin - 160) : begin]


def _period_submitter(
    item: PreparationChecklistItem,
    row: SubmissionRequirement,
    document: AnalysisDocumentRequest,
) -> PreparationChecklistItem:
    if item.applies_to != "원문에 명시된 제출 대상":
        return item
    for source in _sources(document):
        for match in re.finditer(
            r"(?P<role>참여기업|주관기관|공동기관|참여기관)의?\s*"
            r"최근\s*(?P<period>\d+)\s*(?:개)?(?:회계연도|년도|년)[^\n*•☞]{5,240}",
            source.text,
        ):
            reference = source.reference(match[0].strip(), "제출 대상 및 기간")
            if same_period_submission_source(
                reference,
                row.source,
                submission_name(row.title).key,
                int(match["period"]),
            ):
                candidate = item.model_copy(
                    update={"applies_to": match["role"], "source": reference}
                )
                return _preserve_period_submitter(item, candidate) or item
    return item


def _same_row(item: PreparationChecklistItem, row: SubmissionRequirement) -> bool:
    if item.stage != row.stage or not item.source:
        return False
    left = submission_name(_DESCRIPTION.sub("", item.title), split_period=True)
    right = submission_name(row.title, split_period=True)
    if left.key != right.key:
        return False
    if left.references and left.references != right.references:
        if any(reference not in _key(row.source.excerpt) for reference in left.references):
            return False
    if left.submitters and right.submitters and left.submitters != right.submitters:
        return False
    if left.period != right.period and left.period and right.period:
        return False
    if item.source == row.source:
        return True
    if same_submission_source(item.source, row.source):
        return True
    period = left.period or right.period
    if period is None:
        match = re.search(r"최근\s*(\d+)\s*(?:개)?(?:회계연도|년도|년)", row.source.excerpt)
        period = int(match[1]) if match else None
    return bool(
        period and same_period_submission_source(item.source, row.source, right.key, period)
    )


def _own_text(item: PreparationChecklistItem, document: AnalysisDocumentRequest) -> str:
    if not item.source or (item.source.origin == "ATTACHMENT" and not item.source.attachment_name):
        return ""
    for source in _sources(document):
        name = item.source.attachment_name if item.source.origin == "ATTACHMENT" else None
        if item.source.origin not in {"ATTACHMENT", "NOTICE_BODY"} or source.name != name:
            continue
        quote = _key(item.source.excerpt)
        if quote and quote in _key(source.text):
            return source.text
    return ""


def _additional_obligation(
    item: PreparationChecklistItem,
    text: str,
) -> PreparationChecklistItem | None:
    if not text or not item.source:
        return None
    context = _complete_clause(item.source.excerpt, text)
    if context is None:
        return None
    quote, preceding = context
    title = _DESCRIPTION.sub("", item.title).strip()
    name = submission_name(title, split_period=True)
    if not name.key or name.key not in _key(quote) or _NEGATIVE.search(quote):
        return None
    if name.period is not None and not re.search(
        rf"최근\s*{name.period}\s*(?:개)?(?:회계\s*연도|년도|년)",
        quote,
    ):
        return None
    if any(reference not in _key(quote) for reference in name.references):
        return None
    if any(subject not in _key(quote) for subject in name.submitters):
        return None
    # The whole document name must be linked to a submission command. A nearby
    # evaluation score, form field or generic eligibility proof does not suffice.
    compact = _key(quote)
    command = re.search(
        re.escape(name.key) + r"(?:을|를|은|는)?(?:필수로|반드시)?제출"
        r"(?:해야|하여야|합니다|한다|필수|할것|요망|바랍니다|대상|서류|$)",
        compact,
    )
    in_section = submission_section_contains(quote, text)
    if not command and not in_section:
        return None
    if not in_section and re.search(r"참고|예시|견본|평가\s*(?:기준|항목)", preceding):
        return None
    if not in_section and not any(re.search(pattern, quote) for pattern, _ in _STAGES):
        if any(
            re.search(pattern, preceding)
            for pattern, stage in _STAGES
            if stage != RequirementStage.APPLICATION
        ):
            return None
    level = _level(quote)
    if level is None:
        return None
    stages = {stage for pattern, stage in _STAGES if re.search(pattern, quote)}
    if len(stages) > 1:
        return None
    if stages:
        stage = next(iter(stages))
    elif in_section:
        stage = RequirementStage.APPLICATION
    elif item.stage == RequirementStage.APPLICATION:
        stage = item.stage
    else:
        return None  # Do not infer a later stage from an ungrounded model label.
    target = level[1]
    if not target:
        target = item.applies_to if _key(item.applies_to) in compact else "제출 대상 확인 필요"
    detail = f"원문 제출 안내: {quote}"
    return item.model_copy(
        update={
            "title": title,
            "stage": stage,
            "requirement_level": level[0],
            "applies_to": target,
            "detail": detail,
            "next_action": preparation_action(title, quote),
            "source": item.source.model_copy(update={"excerpt": quote}),
        }
    )


def _represented_year_variant(
    item: PreparationChecklistItem,
    rows: list[SubmissionRequirement],
) -> bool:
    """A rejected citation need not re-open a period already explicitly in the table."""
    if item.source is not None:
        return False
    period = r"[’‘']?(\d{2,4})\s*[~∼–-]\s*[’‘']?(\d{2,4})"
    match = re.fullmatch(r"(.+?)\s*\(\s*" + period + r"\s*\)", item.title)
    if not match:
        return False
    matches = [
        row
        for row in rows
        if _document_key(row.title) == _document_key(match[1])
        and row.stage == item.stage
        and (match[2], match[3]) in re.findall(period, row.source.excerpt)
    ]
    return len(matches) == 1


def validate_submission_documents(
    items: list[PreparationChecklistItem],
    document: AnalysisDocumentRequest,
) -> tuple[list[PreparationChecklistItem], list[str]]:
    rows = collect_submission_requirements(document)
    contents = linked_contents(rows, document)
    reconciled = reconcile_submission_documents(items, document)
    verified: list[PreparationChecklistItem] = []
    notes: list[str] = []
    seen_rows: set[int] = set()
    for item in reconciled:
        matches = [(index, row) for index, row in enumerate(rows) if _same_row(item, row)]
        if len(matches) == 1:
            index, row = matches[0]
            if index not in seen_rows:
                # The reconciler has already retained any separately proven period
                # and submitter evidence. Only replace the generic action here.
                item = _period_submitter(item, row, document)
                detail = item.detail
                children = contents.get(index, [])
                if children:
                    addition = " 포함 자료: " + ", ".join(children) + "."
                    if len(detail + addition) > 500:
                        raise ValueError("서류의 포함 자료와 제출 조건을 함께 표시할 수 없습니다.")
                    if addition not in detail:
                        detail += addition
                action = preparation_action(
                    row.title,
                    row.detail or row.source.excerpt,
                    included_in=row.included_in,
                )
                if children and len(action + " 포함 자료: " + ", ".join(children)) <= 300:
                    action += " 포함 자료: " + ", ".join(children)
                verified.append(
                    item.model_copy(
                        update={
                            "title": row.title,
                            "detail": detail,
                            "next_action": action,
                        }
                    )
                )
                seen_rows.add(index)
            continue
        additional = _additional_obligation(item, _own_text(item, document))
        if additional is not None:
            identity = (
                _document_key(additional.title),
                additional.stage,
                additional.applies_to,
                additional.requirement_level,
                additional.source,
            )
            if not any(
                (
                    _document_key(old.title),
                    old.stage,
                    old.applies_to,
                    old.requirement_level,
                    old.source,
                )
                == identity
                for old in verified
            ):
                verified.append(additional)
            continue
        if _represented_year_variant(item, rows):
            continue
        label = (item.source.attachment_name or "공고 본문") if item.source else "원문"
        if len(label) > 150:
            label = "첨부자료"
        notes.append(
            f"제출 여부 확인: {item.title} — 별도 제출 의무가 확인되지 않았습니다. "
            f"{label}에서 제출 대상·조건과 상위 서류에 포함할 내용인지 확인합니다."
        )
    return verified, list(dict.fromkeys(notes))
