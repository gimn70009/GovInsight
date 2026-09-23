from app.domains.analysis.legal.risks import (
    LegalRiskAssessment,
    LegalRiskDecision,
    fallback_legal_risks,
    find_legal_risk_candidates,
    validate_legal_risk_assessment,
)
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import (
    LegalRiskStatus,
    LegalRiskType,
)


def _document(content: str, attachment_text: str = "") -> AnalysisDocumentRequest:
    return AnalysisDocumentRequest.model_validate({
        "detectionId": 1,
        "documentId": 1,
        "versionId": 1,
        "changeType": "NEW_DOCUMENT",
        "organizationName": "기관",
        "boardName": "공고",
        "title": "지원사업",
        "contentText": content,
        "originalUrl": "https://example.com/notice/1",
        "attachments": [{
            "attachmentId": 1,
            "fileName": "공고문.pdf",
            "extractedText": attachment_text,
        }],
    })


def test_finds_small_source_backed_candidate_set() -> None:
    candidates = find_legal_risk_candidates(_document(
        "동일 과제의 중복 지원은 금지합니다.",
        "성과물의 지식재산권 귀속은 사전 협의가 필요합니다.",
    ))

    assert {candidate.type for candidate in candidates} == {
        LegalRiskType.DUPLICATE_SUPPORT,
        LegalRiskType.RESULT_IP_REUSE,
    }
    assert all(len(candidate.excerpt) <= 300 for candidate in candidates)


def test_does_not_treat_land_ownership_as_intellectual_property() -> None:
    candidates = find_legal_risk_candidates(_document(
        "토지 소유형태와 근저당권, 지상권, 건축허가 제한 여부를 작성합니다."
    ))

    assert not any(
        candidate.type == LegalRiskType.RESULT_IP_REUSE
        for candidate in candidates
    )


def test_does_not_treat_intellectual_property_heading_as_reuse_condition() -> None:
    candidates = find_legal_risk_candidates(_document(
        "제5장 지식재산권"
    ))

    assert not any(
        candidate.type == LegalRiskType.RESULT_IP_REUSE
        for candidate in candidates
    )


def test_fallback_marks_candidates_as_caution_instead_of_confirmed() -> None:
    candidates = find_legal_risk_candidates(_document(
        "제공받은 비밀정보는 제3자에게 누설하지 않고 보호해야 합니다."
    ))

    risks = fallback_legal_risks(candidates)
    confidentiality = next(
        risk for risk in risks if risk.type == LegalRiskType.CONFIDENTIALITY
    )
    assert confidentiality.status == LegalRiskStatus.ASSESSMENT_INCOMPLETE
    assert confidentiality.evidence_excerpt is not None


def test_does_not_treat_project_content_duplication_as_cost_double_counting() -> None:
    candidates = find_legal_risk_candidates(_document(
        "신청은 가능하나 사업내용 중복 불가이며 평가 시 검증합니다."
    ))

    assert any(item.type == LegalRiskType.DUPLICATE_SUPPORT for item in candidates)
    assert not any(
        item.type == LegalRiskType.COST_DOUBLE_COUNTING for item in candidates
    )


def test_requires_cost_and_accounting_action_for_double_counting() -> None:
    candidates = find_legal_risk_candidates(_document(
        "동일 인건비를 다른 지원사업에 중복 계상할 수 없습니다."
    ))

    assert any(
        item.type == LegalRiskType.COST_DOUBLE_COUNTING for item in candidates
    )


def test_does_not_treat_security_pledge_title_as_confidentiality_risk() -> None:
    candidates = find_legal_risk_candidates(_document(
        "제출서류는 윤리·청렴 및 보안서약서입니다."
    ))

    assert not any(item.type == LegalRiskType.CONFIDENTIALITY for item in candidates)


def test_detects_explicit_confidentiality_obligation() -> None:
    candidates = find_legal_risk_candidates(_document(
        "제공받은 비밀정보는 제3자에게 공개하거나 누설해서는 안 됩니다."
    ))

    assert any(item.type == LegalRiskType.CONFIDENTIALITY for item in candidates)


def test_rejects_model_evidence_that_is_not_a_candidate_excerpt() -> None:
    candidates = find_legal_risk_candidates(_document(
        "동일 과제의 중복 지원은 금지합니다."
    ))
    findings = [
        LegalRiskDecision(
            type=risk_type,
            status=(
                LegalRiskStatus.RESTRICTION_FOUND
                if risk_type == LegalRiskType.DUPLICATE_SUPPORT
                else LegalRiskStatus.NOT_FOUND
            ),
            evidence_excerpt=(
                "원문에 존재하지 않는 임의의 인용문입니다."
                if risk_type == LegalRiskType.DUPLICATE_SUPPORT
                else None
            ),
        )
        for risk_type in LegalRiskType
    ]

    validated = validate_legal_risk_assessment(
        LegalRiskAssessment(legal_risks=findings), candidates
    )

    duplicate_support = next(
        risk for risk in validated if risk.type == LegalRiskType.DUPLICATE_SUPPORT
    )
    assert duplicate_support.status == LegalRiskStatus.ASSESSMENT_INCOMPLETE
    assert duplicate_support.evidence_excerpt == candidates[0].excerpt


def test_replaces_model_generated_summary_with_korean_template() -> None:
    candidates = find_legal_risk_candidates(_document(
        "동일 과제의 중복 지원은 금지합니다."
    ))
    assessment = LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type=LegalRiskType.DUPLICATE_SUPPORT,
        status=LegalRiskStatus.NOT_FOUND,
    )])

    validated = validate_legal_risk_assessment(assessment, candidates)

    duplicate_support = next(
        risk for risk in validated if risk.type == LegalRiskType.DUPLICATE_SUPPORT
    )
    assert duplicate_support.summary == (
        "원문에서 중복지원 관련 제한을 확인하지 못했습니다."
    )
    assert all("Candidate" not in risk.summary for risk in validated)


def test_recovers_valid_decision_when_other_model_items_are_invalid() -> None:
    candidates = find_legal_risk_candidates(_document(
        "동일 과제의 중복 지원은 금지합니다."
    ))
    assessment = LegalRiskAssessment(legal_risks=[
        LegalRiskDecision(
            type="UNKNOWN_TYPE",
            status="UNKNOWN_STATUS",
        ),
        LegalRiskDecision(
            type=LegalRiskType.DUPLICATE_SUPPORT,
            status=LegalRiskStatus.RESTRICTION_FOUND,
            evidence_excerpt=candidates[0].excerpt,
        ),
    ])

    validated = validate_legal_risk_assessment(assessment, candidates)

    duplicate_support = next(
        risk for risk in validated if risk.type == LegalRiskType.DUPLICATE_SUPPORT
    )
    assert duplicate_support.status == LegalRiskStatus.RESTRICTION_FOUND
    assert len(validated) == 5


def test_candidate_excerpt_stops_at_form_bullet_boundary() -> None:
    candidates = find_legal_risk_candidates(_document(
        "□ 토지 소유 현황을 작성합니다. "
        "□ 지식재산권 사용에는 사전 승인이 필요합니다. "
        "□ 다음 항목의 긴 설명은 별도 서식에 작성합니다."
    ))

    intellectual_property = next(
        item for item in candidates if item.type == LegalRiskType.RESULT_IP_REUSE
    )
    assert "토지 소유" not in intellectual_property.excerpt
    assert "다음 항목" not in intellectual_property.excerpt
    assert len(intellectual_property.excerpt) <= 220


def test_keeps_exception_after_line_break():
    candidates = find_legal_risk_candidates(_document(
        "동일 과제 중복 지원은 금지합니다.\n※ 다만 지원 범위가 구분되면 별도 심사를 거칩니다."
    ))
    assert "별도 심사" in candidates[0].excerpt


def test_missing_model_decision_is_incomplete():
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원은 금지합니다."))
    findings = validate_legal_risk_assessment(LegalRiskAssessment(), candidates)
    assert findings[0].status == LegalRiskStatus.ASSESSMENT_INCOMPLETE


def test_missing_attachment_is_insufficient_without_erasing_restriction():
    from app.domains.analysis.legal.risks import apply_document_coverage, no_candidate_legal_risks
    document = _document("동일 과제 중복 지원은 금지합니다.")
    findings = no_candidate_legal_risks()
    findings[0].status = LegalRiskStatus.RESTRICTION_FOUND
    result = apply_document_coverage(findings, document)
    assert result[0].status == LegalRiskStatus.RESTRICTION_FOUND
    assert result[1].status == LegalRiskStatus.DATA_INSUFFICIENT
    assert "첨부 1개" in result[0].summary


def test_samples_attachment_even_with_many_body_candidates():
    body = "\n".join(f"{i}번 동일 과제 중복 지원은 금지합니다." for i in range(10))
    candidates = find_legal_risk_candidates(_document(body, "동일 과제 중복 지원은 조건부 허용합니다."))
    assert len(candidates) == 6
    assert any(item.source == "공고문.pdf" for item in candidates)
    assert all(item.selection_limited for item in candidates)
    assert not any(item.incomplete for item in candidates)


def test_invented_suffix_in_evidence_is_rejected():
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원은 금지합니다."))
    result = validate_legal_risk_assessment(LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND",
        evidence_excerpt=candidates[0].excerpt + " 그러나 우리 회사는 예외입니다.",
    )]), candidates)
    assert result[0].status == LegalRiskStatus.ASSESSMENT_INCOMPLETE


def test_overlong_clause_cannot_be_confirmed_from_truncated_context():
    candidates = find_legal_risk_candidates(_document(
        "동일 과제 중복 지원은 금지합니다. " + "상세 설명 " * 700 + "다만 별도 승인을 받은 경우는 제외합니다."
    ))
    result = validate_legal_risk_assessment(LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND",
        evidence_excerpt=candidates[0].excerpt,
    )]), candidates)
    assert result[0].status == LegalRiskStatus.ASSESSMENT_INCOMPLETE
    assert result[0].evidence_excerpt is None


def test_complete_clause_is_not_discarded_when_other_candidates_are_omitted():
    candidates = find_legal_risk_candidates(_document("\n".join(
        f"{i}번 동일 과제 중복 지원은 금지합니다." for i in range(9))))
    result = validate_legal_risk_assessment(LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND", candidate_id=1,
    )]), candidates)
    assert result[0].status == LegalRiskStatus.RESTRICTION_FOUND
    assert result[0].evidence_excerpt == candidates[0].excerpt
    assert "조건·예외" in result[0].summary


def test_candidate_id_cannot_reference_another_risk_type():
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원 금지.\n비밀정보를 공개해서는 안 됩니다."))
    result = validate_legal_risk_assessment(LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type="CONFIDENTIALITY", status="RESTRICTION_FOUND", candidate_id=1,
    )]), candidates)
    finding = next(item for item in result if item.type == LegalRiskType.CONFIDENTIALITY)
    assert finding.status == LegalRiskStatus.ASSESSMENT_INCOMPLETE
    assert "대조" in finding.summary


def test_failure_reasons_are_distinct_and_not_legal_ambiguity():
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원 금지."))
    assert "오류" in fallback_legal_risks(candidates)[0].summary
    result = validate_legal_risk_assessment(LegalRiskAssessment(), candidates)
    assert "반환되지" in result[0].summary


def test_not_found_does_not_hide_unreviewed_candidates():
    candidates = find_legal_risk_candidates(_document("\n".join(
        f"{i}번 동일 과제 중복 지원은 금지합니다." for i in range(9))))
    result = validate_legal_risk_assessment(LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type="DUPLICATE_SUPPORT", status="NOT_FOUND",
    )]), candidates)
    assert result[0].status == LegalRiskStatus.ASSESSMENT_INCOMPLETE
    assert "전체를 검토하지" in result[0].summary


def test_missing_decision_is_repaired_with_grounded_insight():
    import asyncio
    from unittest.mock import AsyncMock

    from app.domains.analysis.legal.risks import assess_with_repair
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원은 금지합니다."))
    decision = LegalRiskDecision(type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND", candidate_id=1,
        interpretation="동일 과제에 대한 중복지원이 제한됩니다.",
        implication="기존 지원 과제와 수행 범위가 같다면 신청에 영향을 줄 수 있습니다.",
        verification="기존 과제와 신청 과제의 목표·수행 범위를 대조해야 합니다.")
    model = AsyncMock()
    model.ainvoke.side_effect = [LegalRiskAssessment(), LegalRiskAssessment(legal_risks=[decision])]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert model.ainvoke.await_count == 2
    assert result[0].status == LegalRiskStatus.RESTRICTION_FOUND
    assert decision.implication in result[0].summary
    assert result[0].evidence_excerpt == candidates[0].excerpt


def test_invalid_evidence_does_not_publish_model_insight_and_calls_are_bounded():
    import asyncio
    from unittest.mock import AsyncMock

    from app.domains.analysis.legal.risks import assess_with_repair
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원은 금지합니다."))
    model = AsyncMock()
    model.ainvoke.return_value = LegalRiskAssessment(legal_risks=[LegalRiskDecision(
        type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND", candidate_id=99,
        interpretation="허위 해석입니다.", implication="허위 영향입니다.", verification="허위 확인입니다.")])
    result = asyncio.run(assess_with_repair(model, candidates))
    assert model.ainvoke.await_count == 2
    assert result[0].status == LegalRiskStatus.ASSESSMENT_INCOMPLETE
    assert "허위" not in result[0].summary


def test_repair_error_preserves_successful_first_pass():
    import asyncio
    from unittest.mock import AsyncMock

    from app.domains.analysis.legal.risks import assess_with_repair
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원은 금지합니다.\n비밀정보의 공개는 금지합니다."))
    decision = LegalRiskDecision(type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND", candidate_id=1,
        interpretation="동일 과제의 중복지원 제한입니다.", implication="동일 과제이면 신청에 영향을 줍니다.",
        verification="기존 과제 범위를 대조해야 합니다.")
    model = AsyncMock()
    model.ainvoke.side_effect = [LegalRiskAssessment(legal_risks=[decision]), ValueError("bad response")]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert result[0].summary.startswith(decision.interpretation)
    assert next(item for item in result if item.type == LegalRiskType.CONFIDENTIALITY).status == LegalRiskStatus.ASSESSMENT_INCOMPLETE



def test_repair_timeout_preserves_completed_insight():
    import asyncio
    from unittest.mock import AsyncMock

    from app.domains.analysis.legal.risks import assess_with_repair
    candidates = find_legal_risk_candidates(_document("동일 과제 중복 지원은 금지합니다.\n비밀정보의 공개는 금지합니다."))
    decision = LegalRiskDecision(type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND", candidate_id=1,
        interpretation="동일 과제 중복지원이 제한됩니다.", implication="동일 과제이면 신청에 영향을 줍니다.",
        verification="기존 과제 범위를 대조해야 합니다.")
    model = AsyncMock()
    model.ainvoke.side_effect = [LegalRiskAssessment(legal_risks=[decision]), TimeoutError()]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert result[0].summary.startswith(decision.interpretation)
    assert model.ainvoke.await_count == 2


def test_model_schema_requires_every_risk_and_rejects_empty_response():
    import pytest
    from pydantic import ValidationError

    from app.domains.analysis.legal.risks import LegalRiskModelResponse
    with pytest.raises(ValidationError):
        LegalRiskModelResponse.model_validate({})
    verdict = dict(status="NOT_FOUND", candidate_id=None, interpretation="", implication="", verification="")
    response = LegalRiskModelResponse.model_validate({kind.value: verdict for kind in LegalRiskType})
    assert len(response.to_assessment().legal_risks) == 5
    assert set(LegalRiskModelResponse.model_json_schema()["required"]) == {kind.value for kind in LegalRiskType}


def test_normalized_legal_insight_keeps_original_evidence_without_retry():
    import asyncio
    from unittest.mock import AsyncMock

    from app.domains.analysis.legal.risks import assess_with_repair
    source = "동일 과제 중복 지원은 금지한다. 다만 별도 승인 시 예외로 한다."
    candidates = find_legal_risk_candidates(_document(source))
    decision = LegalRiskDecision(
        type="DUPLICATE_SUPPORT", status="RESTRICTION_FOUND", candidate_id=1,
        interpretation="동일 과제의 중복지원은 제한되나 별도 승인 예외가 있다.",
        implication="수행 범위가 같다면 승인 조건의 확인이 필요하다.",
        verification="기존 과제와 신청 과제의 수행 범위 확인")
    model = AsyncMock()
    model.ainvoke.return_value = LegalRiskAssessment(legal_risks=[decision])
    result = asyncio.run(assess_with_repair(model, candidates))
    assert model.ainvoke.await_count == 1
    assert result[0].failure_reason is None
    assert "승인 예외가 있습니다." in result[0].summary
    assert "확인이 필요합니다." in result[0].summary
    assert result[0].summary.endswith("수행 범위 확인이 필요합니다.")
    assert result[0].evidence_excerpt == source


def test_unrepairable_style_retries_only_failed_type_and_preserves_other_findings():
    import asyncio
    from unittest.mock import AsyncMock

    from app.domains.analysis.legal.risks import assess_with_repair
    candidates = find_legal_risk_candidates(_document(
        "동일 과제 중복 지원은 금지합니다.\n비밀정보의 공개는 금지합니다."))
    decisions = [LegalRiskDecision(
        type=candidate.type.value, status="RESTRICTION_FOUND", candidate_id=index,
        interpretation="제한 조항이 있습니다.", implication="해당 조건의 검토가 필요합니다.",
        verification="실제 적용 범위를 확인합니다.")
        for index, candidate in enumerate(candidates, 1)]
    bad = decisions[0].model_copy(update={"verification": "수행 범위 및 조건"})
    model = AsyncMock()
    model.ainvoke.side_effect = [
        LegalRiskAssessment(legal_risks=[bad, *decisions[1:]]),
        LegalRiskAssessment(legal_risks=[decisions[0]])]
    result = asyncio.run(assess_with_repair(model, candidates))
    assert model.ainvoke.await_count == 2
    assert "INVALID_STYLE" in model.ainvoke.call_args.args[0]
    for decision in decisions:
        finding = next(item for item in result if item.type.value == decision.type)
        assert finding.failure_reason is None
        assert finding.summary.startswith(decision.interpretation)
