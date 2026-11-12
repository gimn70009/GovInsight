from app.domains.participation import focused, selection
from app.domains.participation.schemas import Source


def test_support_target_parent_does_not_promote_funding_or_application_steps():
    for title, body in [
        ("지원 범위", "입지지원금은 설비지원금을 초과할 수 없음. 기계장비 투자 지원"),
        ("신청서 제출 전", "신청기업은 지자체 협의 후 신청시스템에서 접수"),
        ("운영 규정", "국가첨단산업 중소기업 투자지원금 운영 규정"),
    ]:
        source = Source(id="a", group_label=f"[상위 구역] 지원 대상\n□ {title}",
                        excerpt=f"□ {title}\n{body}")
        assert selection.prepare(source, focused.source_fields) is None


def test_exclusions_nested_in_precautions_preserve_dates_negation_and_all_grounds():
    grounds = (
        "ㅇ 지원제외대상\n"
        "- 신청서류가 허위인 경우\n"
        "- 신청기업 및 대표자가 접수마감일 기준 국가연구개발사업 참여제한인 경우\n"
        "- 신청기업 및 대표자가 접수마감일 기준 신용거래 불량자인 경우\n"
    )
    source = Source(id="a", group_label="□ 기타 유의사항",
                    excerpt="□ 기타 유의사항\nㅇ 제출서류는 반환하지 않음\n" + grounds)
    prepared, _ = selection.prepare(source, focused.source_fields)
    assert prepared.excerpt == grounds
    assert prepared.id == source.id


def test_explicit_qualification_inside_funding_section_is_not_lost():
    eligibility = "□ 신청자격\n중소기업 또는 중견기업이 신청할 수 있습니다.\n"
    source = Source(id="a", group_label="□ 지원 범위", excerpt=(
        "□ 지원 범위\n설비투자 비용 지원\n" + eligibility
        + "□ 신청방법\n신청시스템을 통해 접수\n"
    ))
    prepared, _ = selection.prepare(source, focused.source_fields)
    assert prepared.excerpt == eligibility
