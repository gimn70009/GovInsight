from app.domains.analysis.legal_risks import (
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
    assert confidentiality.status == LegalRiskStatus.CAUTION
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
    assert duplicate_support.status == LegalRiskStatus.CAUTION
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
