import pytest

from app.domains.report.brief import BriefContext, Evidence, SourcedText, _values
from app.domains.report.submission_documents import SourcePart


@pytest.mark.parametrize(('raw', 'display'), [
    ('신청 대상: 중소기업', '신청 대상: 대기업'),
    ('국내 주관기관: 대학', '국내 주관기관: 기업'),
    ('신청 자격: 참여제한 중인 기관은 신청할 수 없습니다.',
     '신청 자격: 참여제한 중인 기관도 신청할 수 있습니다.'),
    ('신청 자격: 인원 5명 이상, 업력 3년 이하',
     '신청 자격: 인원 5명 이하, 업력 3년 이상'),
    ('신청 자격: 인원 5명 이상, 업력 3년 이하',
     '신청 자격: 인원 3명 이하, 업력 5년 이상'),
    ('신청 자격: 중소기업만 가능하며 대기업은 제외합니다.',
     '신청 자격: 중소기업과 대기업 모두 가능합니다.'),
    ('신청 자격: 참여제한이 없는 기관만 신청 가능합니다.',
     '신청 자격: 모든 기관이 신청 가능합니다.'),
])
def test_changed_conditions_never_reach_report(raw, display):
    context = BriefContext('{}', {'body': SourcePart(None, raw)})
    item = SourcedText(text=raw, evidence=Evidence(source_id='body', quote=raw),
                      display_text=display)
    result = _values([item], context, 'applicant')
    assert result != display
    assert result in (raw, '원문 확인 필요')


def test_omitted_exception_is_restored_from_full_verified_quote():
    raw = "신청 자격: 중소기업"
    quote = raw + ". 단, 참여제한 중인 기관은 제외합니다."
    context = BriefContext('{}', {'body': SourcePart(None, quote)})
    item = SourcedText(text=raw, evidence=Evidence(source_id='body', quote=quote),
                      fragments=[raw], display_text="신청 자격: 중소기업")
    assert _values([item], context, 'applicant') == quote


def test_deadline_rewrite_cannot_remove_receipt_exception():
    quote = "접수 마감: 2026년 10월 14일 18:00까지. 우편은 도착분만 유효합니다."
    context = BriefContext('{}', {'body': SourcePart(None, quote)})
    display = "접수 마감: 2026년 10월 14일 18:00까지. 우편은 발송분도 유효합니다."
    item = SourcedText(text=quote, evidence=Evidence(source_id='body', quote=quote),
                      display_text=display)
    assert _values([item], context, 'deadline') == quote


def test_long_condition_quote_is_not_truncated_to_remove_exception():
    raw = "신청 자격: 중소기업"
    details = "기타 상세 조건을 원문에서 확인해야 합니다. " * 8
    quote = raw + ". " + details + "대기업은 제외합니다."
    context = BriefContext('{}', {'body': SourcePart(None, quote)})
    item = SourcedText(text=raw, evidence=Evidence(source_id='body', quote=quote),
                      fragments=[raw], display_text=raw)
    assert _values([item], context, 'applicant') == '원문 확인 필요'


def test_safe_korean_condition_formatting_keeps_source():
    quote = "신청 자격: 인원 5명 이상, 업력 3년 이하"
    context = BriefContext('{}', {'body': SourcePart(None, quote)})
    item = SourcedText(text=quote, evidence=Evidence(source_id='body', quote=quote),
                      display_text="신청 자격: 인원 5 명 이상, 업력 3 년 이하")
    assert _values([item], context, 'applicant') == quote
