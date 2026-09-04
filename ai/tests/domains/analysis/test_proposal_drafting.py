import asyncio
import json

from app.domains.analysis.proposal_drafting import (
    CORE_PROPOSAL_SECTION_TITLES,
    ProposalDraftOutput,
    ProposalModelOutput,
    TwoStageAnalysisWorkflow,
    _apply_strategy_eligibility_guardrails,
    _build_proposal_source_context,
    _is_international_requirement,
    _normalize_preparation_structure,
    _parse_model_response,
    _retain_verified_source_references,
    _safe_error,
)
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.schemas.result import (
    CompanyEvidenceLevel,
    DocumentAnalysisResult,
    EvidenceOrigin,
    PreparationChecklistItem,
    ProposalDraftStatus,
    ProposalPreparation,
    RequirementLevel,
    RequirementSource,
    RequirementStage,
    StopCriterionType,
    StrategyCapabilityMatch,
    StrategyDecision,
    StrategyGap,
    StrategyOnePage,
    StrategyStopCriterion,
    _normalize_strategy_sentence,
)


def document(with_attachment: bool = True) -> AnalysisDocumentRequest:
    payload: dict[str, object] = {
        "detectionId": 1,
        "documentId": 2,
        "versionId": 3,
        "changeType": "NEW_DOCUMENT",
        "organizationName": "한국산업기술진흥원",
        "boardName": "사업공고",
        "title": "산업 AI 실증 사업",
        "contentText": "사업계획서를 제출해야 합니다.",
        "originalUrl": "https://example.com/notices/3",
    }
    if with_attachment:
        payload["attachments"] = [
            {
                "attachmentId": 4,
                "fileName": "신청서식.hwp",
                "extractedText": (
                    "목차 I. 수행계획 1. 과제 개요. "
                    "산업 AI 공급기업이 신청할 수 있습니다. "
                    "사업계획서를 제출해야 합니다."
                ),
            }
        ]
    return AnalysisDocumentRequest.model_validate(payload)


def result(
    document_type: str = "PROPOSAL_REQUEST", company_fit: int = 80
) -> DocumentAnalysisResult:
    return DocumentAnalysisResult.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "summary": "산업 AI 실증 사업에 사업계획서를 제출하는 공고입니다.",
            "keyPoints": ["사업계획서 제출이 필요합니다."],
            "importance": "HIGH",
            "reason": "회사 핵심 산업과 제조 AI 과업이 직접 관련됩니다.",
            "eligibility": "REVIEW_REQUIRED",
            "favorableOrNot": "NOT_APPLICABLE",
            "proposal": {
                "sections": [
                    {
                        "title": "핵심 판단",
                        "body": "회사 기술을 활용할 수 있어 사업 참여를 검토할 필요가 있습니다.",
                    }
                ],
                "documentType": document_type,
                "draftStatus": (
                    "REVIEW_REQUIRED" if document_type == "PROPOSAL_REQUEST" else "NOT_APPLICABLE"
                ),
                "draftReason": "1단계 공고 분류와 회사 적합성 판단 결과입니다.",
                "sourceAttachmentNames": [],
                "templateSections": [],
                "draftSections": [],
            },
            "opportunity": {
                "dimensions": [
                    {
                        "type": "COMPANY_FIT",
                        "score": company_fit,
                        "reason": "회사 핵심 산업과 직접 관련된 공고입니다.",
                    },
                    {
                        "type": "BUSINESS_VALUE",
                        "score": 70,
                        "reason": "신규 사업 실적을 확보할 수 있는 기회입니다.",
                    },
                    {
                        "type": "FEASIBILITY",
                        "score": 60,
                        "reason": "신청 자격은 추가 확인이 필요합니다.",
                    },
                    {"type": "URGENCY", "score": 50, "reason": "접수 마감까지 남은 20일입니다."},
                ]
            },
            "usedTools": ["get_document_content", "get_attachment_texts", "get_company_profile"],
            "modelName": "mock-model",
        }
    )


class BaseWorkflow:
    def __init__(self, analysis: DocumentAnalysisResult) -> None:
        self.analysis = analysis

    async def analyze(self, _document: AnalysisDocumentRequest) -> DocumentAnalysisResult:
        return self.analysis


class ProposalRunner:
    def __init__(self, fail: bool = False) -> None:
        self.call_count = 0
        self.fail = fail

    async def generate(
        self,
        _document: AnalysisDocumentRequest,
        _analysis: DocumentAnalysisResult,
    ) -> ProposalDraftOutput:
        self.call_count += 1
        if self.fail:
            raise TimeoutError
        return ProposalDraftOutput(
                source_attachment_names=["신청서식.hwp"],
                preparation=ProposalPreparation(
                    meeting_agenda=[
                        "주관기관과 회사의 역할을 확정합니다.",
                        "목표 KPI와 측정 방법을 확정합니다.",
                        "사업비와 투입 인력을 확정합니다.",
                    ],
                    eligibility_checklist=[
                        PreparationChecklistItem(
                            title="산업 AI 공급기업 자격",
                            detail="회사 서비스는 관련되지만 공식 증빙 확인이 필요합니다.",
                            next_action="관련 수행 실적 증빙을 확인합니다.",
                            requirement_level=RequirementLevel.MANDATORY,
                            stage=RequirementStage.APPLICATION,
                            applies_to="국내 참여기업",
                            source=RequirementSource(
                                origin=EvidenceOrigin.ATTACHMENT,
                                attachment_name="신청서식.hwp",
                                section_title="신청 자격",
                                location="지원 조건",
                                excerpt="산업 AI 공급기업이 신청할 수 있습니다.",
                            ),
                            company_evidence_level=CompanyEvidenceLevel.PUBLIC_INFORMATION,
                        )
                    ],
                    submission_documents=[
                        PreparationChecklistItem(
                            title="사업계획서",
                            detail="공고 첨부파일에서 제출 양식이 확인되었습니다.",
                            next_action="담당자를 지정해 작성을 시작합니다.",
                            requirement_level=RequirementLevel.MANDATORY,
                            stage=RequirementStage.APPLICATION,
                            applies_to="주관기관",
                            source=RequirementSource(
                                origin=EvidenceOrigin.ATTACHMENT,
                                attachment_name="신청서식.hwp",
                                section_title="제출 서류",
                                location="제출서류 표 1번",
                                excerpt="사업계획서를 제출해야 합니다.",
                            ),
                            company_evidence_level=CompanyEvidenceLevel.UNKNOWN,
                        )
                    ],
                    company_inputs=[
                        PreparationChecklistItem(
                            title="세부 목표 KPI",
                            detail="회사 내부 목표 수치가 확인되지 않았습니다.",
                            next_action="사업 책임자가 목표 수치를 확정합니다.",
                        )
                    ],
                    strategy=StrategyOnePage(
                        decision=StrategyDecision.CONDITIONAL_GO,
                        decision_reason="실증 파트너를 확보하는 조건으로 지원을 권장합니다.",
                        recommended_project="제조 현장 산업 AI 에이전트 실증",
                        recommended_participation=(
                            "회사는 AI 공급기업으로 참여하고 제조기업과 공동 수행합니다."
                        ),
                        alternative_participation=(
                            "총괄 역량이 부족하면 공동연구개발기관으로 참여합니다."
                        ),
                        capability_matches=[
                            StrategyCapabilityMatch(
                                confirmed_fact="회사 공개정보에서 제조 AI 사례가 확인됩니다.",
                                strategic_interpretation=(
                                    "공고의 산업 AI 실증 분야와 연결할 수 있습니다."
                                ),
                            )
                        ],
                        critical_gaps=[
                            StrategyGap(
                                gap="실증 제조기업이 확인되지 않았습니다.",
                                next_action="사업담당자가 제조기업의 참여 의사를 확인합니다.",
                                owner="사업담당자",
                                target_timing="접수마감 전 컨소시엄 확정 시점",
                            )
                        ],
                        stop_criteria=[
                            StrategyStopCriterion(
                                type=StopCriterionType.OFFICIAL_REQUIREMENT,
                                condition="접수마감까지 필수 실증 파트너를 확보하지 못합니다.",
                                rationale="공고의 컨소시엄 구성요건을 충족할 수 없습니다.",
                            )
                        ],
                    ),
                ),
        )


def test_generates_outline_then_draft_only_for_matching_proposal_request() -> None:
    proposal_runner = ProposalRunner()
    workflow = TwoStageAnalysisWorkflow(BaseWorkflow(result()), proposal_runner)

    generated = asyncio.run(workflow.analyze(document()))

    assert proposal_runner.call_count == 1
    assert generated.proposal.draft_status == ProposalDraftStatus.READY
    assert generated.proposal.template_sections == CORE_PROPOSAL_SECTION_TITLES
    assert generated.proposal.preparation is not None
    assert generated.proposal.preparation.strategy.recommended_project.startswith("제조 현장")
    assert generated.proposal.source_attachment_names == ["신청서식.hwp"]
    assert generated.proposal.preparation_schema_version == 11
    assert "map_proposal_sources" in generated.used_tools
    assert "build_proposal_preparation" in generated.used_tools


def test_skips_second_stage_for_non_proposal_notice() -> None:
    proposal_runner = ProposalRunner()
    workflow = TwoStageAnalysisWorkflow(
        BaseWorkflow(result(document_type="BUSINESS_NOTICE")),
        proposal_runner,
    )

    generated = asyncio.run(workflow.analyze(document()))

    assert proposal_runner.call_count == 0
    assert generated.proposal.draft_status == ProposalDraftStatus.NOT_APPLICABLE


def test_skips_second_stage_when_company_fit_is_below_generation_threshold() -> None:
    proposal_runner = ProposalRunner()
    workflow = TwoStageAnalysisWorkflow(
        BaseWorkflow(result(company_fit=60)),
        proposal_runner,
    )

    generated = asyncio.run(workflow.analyze(document()))

    assert proposal_runner.call_count == 0
    assert generated.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED
    assert generated.proposal.draft_reason.startswith(
        "회사 적합도는 60점으로 사업 제안 준비안 생성 기준인 61점에 미달했습니다."
    )
    assert "REVIEW_REQUIRED" not in generated.proposal.draft_reason


def test_skip_reason_lists_missing_attachment_and_eligibility_confirmation() -> None:
    proposal_runner = ProposalRunner()
    workflow = TwoStageAnalysisWorkflow(
        BaseWorkflow(result(company_fit=55)),
        proposal_runner,
    )

    generated = asyncio.run(workflow.analyze(document(with_attachment=False)))

    assert proposal_runner.call_count == 0
    assert "회사 적합도는 55점" in generated.proposal.draft_reason
    assert "내용 분석이 완료된 첨부 양식이 없어" in generated.proposal.draft_reason
    assert "신청 자격은 회사의 공식 증빙으로 추가 확인해야 합니다." in (
        generated.proposal.draft_reason
    )
    assert generated.proposal.draft_reason.endswith(
        "따라서 현재 확인된 조건으로는 사업 제안 준비안을 생성할 수 없습니다."
    )


def test_proposal_stage_failure_preserves_base_analysis() -> None:
    proposal_runner = ProposalRunner(fail=True)
    workflow = TwoStageAnalysisWorkflow(BaseWorkflow(result()), proposal_runner)

    generated = asyncio.run(workflow.analyze(document()))

    assert generated.summary.startswith("산업 AI 실증")
    assert generated.proposal.draft_status == ProposalDraftStatus.REVIEW_REQUIRED
    assert generated.proposal.draft_sections == []
    assert generated.proposal.preparation_schema_version == 11
    assert "사업 제안 생성 제한 시간을 초과했습니다." in generated.proposal.draft_reason


def test_keeps_valid_items_and_accepts_zip_inner_document_name() -> None:
    proposal_runner = ProposalRunner()
    proposal = asyncio.run(proposal_runner.generate(document(), result()))
    proposal.preparation.eligibility_checklist.append(
        proposal.preparation.eligibility_checklist[0].model_copy(
            update={
                "title": "원문에 없는 조건",
                "source": RequirementSource(
                    origin=EvidenceOrigin.ATTACHMENT,
                    attachment_name="없는문서.hwp",
                    section_title="지원 조건",
                    excerpt="원문에 존재하지 않는 조건입니다.",
                ),
            }
        )
    )
    for item in [
        *proposal.preparation.eligibility_checklist[:1],
        *proposal.preparation.submission_documents,
    ]:
        item.source.attachment_name = "내부신청서식.hwp"
    zipped_document = document()
    zipped_document.attachments[0].file_name = "공고서식.zip"
    zipped_document.attachments[0].extracted_text = (
        "[파일: 내부신청서식.hwp]\n"
        "산업 AI 공급기업이 신청할 수 있습니다. 사업계획서를 제출해야 합니다."
    )

    _retain_verified_source_references(proposal, zipped_document)

    assert [item.title for item in proposal.preparation.eligibility_checklist] == [
        "산업 AI 공급기업 자격"
    ]
    assert len(proposal.preparation.submission_documents) == 1


def test_builds_bounded_proposal_context_with_relevant_evidence() -> None:
    request = document()
    request.content_text = "일반 안내 " * 3_000 + "신청 자격은 국내 기업입니다."
    request.attachments[0].file_name = "붙임2. 제출서류 양식.zip"
    request.attachments[0].extracted_text = (
        "일반 서식 설명 " * 3_000 + "사업계획서와 참여확인서를 제출해야 합니다."
    )

    context = _build_proposal_source_context(request, 8_000)

    assert len(context) < 8_500
    assert "신청 자격은 국내 기업입니다." in context
    assert "사업계획서와 참여확인서를 제출해야 합니다." in context
    assert "붙임2. 제출서류 양식.zip" in context


def test_removes_repeated_notice_blocks_from_attachment_model_context() -> None:
    request = document()
    repeated = "신청기관은 기업부설연구소 인정서를 보유하여야 합니다."
    request.content_text = repeated
    request.attachments[0].extracted_text = (
        f"{repeated}\n사업계획서와 참여확인서를 제출해야 합니다."
    )

    context = json.loads(_build_proposal_source_context(request, 8_000))

    assert repeated in context["noticeExcerpt"]
    assert repeated not in context["attachmentExcerpts"][0]["excerpt"]
    assert "사업계획서와 참여확인서를 제출해야 합니다." in (
        context["attachmentExcerpts"][0]["excerpt"]
    )


def test_sanitizes_model_usage_details_from_generation_error() -> None:
    error = RuntimeError(
        "Could not parse response content as the length limit was reached "
        "- CompletionUsage(completion_tokens=5000)"
    )

    assert _safe_error(error) == "모델 출력 한도에 도달했습니다."


def test_model_output_schema_excludes_deterministic_calculation_fields() -> None:
    schema = str(ProposalModelOutput.model_json_schema())

    assert "readinessScore" not in schema
    assert "conditionScore" not in schema
    assert "evidenceScore" not in schema
    assert "scheduleScore" not in schema
    assert "estimatedBusinessDays" not in schema
    assert "targetDate" not in schema
    assert "scheduleBasis" not in schema
    assert "scoreBasis" not in schema


def test_compact_model_output_restores_api_defaults() -> None:
    full = asyncio.run(ProposalRunner().generate(document(), result()))
    payload = full.model_dump()
    preparation = payload["preparation"]
    for section_name in (
        "eligibility_checklist",
        "submission_documents",
        "company_inputs",
    ):
        for item in preparation[section_name]:
            for field_name in (
                "readiness_score",
                "condition_score",
                "evidence_score",
                "schedule_score",
                "estimated_business_days",
                "score_basis",
            ):
                item.pop(field_name)
    for gap in preparation["strategy"]["critical_gaps"]:
        for field_name in (
            "estimated_business_days",
            "target_date",
            "schedule_basis",
        ):
            gap.pop(field_name)

    compact = ProposalModelOutput.model_validate(payload)
    restored, raw = _parse_model_response(
        {"parsed": compact, "raw": None, "parsing_error": None}
    )

    assert raw is None
    assert restored.preparation.eligibility_checklist[0].readiness_score == 0
    assert restored.preparation.strategy.critical_gaps[0].target_date is None


def test_normalizes_internal_terms_and_strategy_tone() -> None:
    normalized = _normalize_strategy_sentence(
        "MoU/LOI를 확인한다; 조건을 충족하면 GO로 전환을 검토한다."
    )

    assert ";" not in normalized
    assert "GO" not in normalized
    assert "한다" not in normalized
    assert "협력의향서 또는 참여확인서" in normalized
    assert normalized.endswith("검토합니다.")


def test_normalizes_checklist_title_and_user_sentence() -> None:
   item = PreparationChecklistItem(
       title="(양식10) 외부기술도입비 현물산정 신청서",
       detail="재무팀 · 최신 증빙을 확인한다.",
       next_action="사업담당자 · 제출 여부를 확인한다.",
   )

   assert item.title == "외부기술도입비 현물산정 신청서"
   assert "·" not in item.detail
   assert item.detail.endswith("확인합니다.")
   assert "·" not in item.next_action
   assert item.next_action.endswith("확인합니다.")


   item = PreparationChecklistItem(
       title="(양식10) 외부기술도입비 현물산정 신청서",
       detail="재무팀 · 최신 증빙을 확인한다.",
       next_action="사업담당자 · 제출 여부를 확인한다.",
   )

   assert item.title == "외부기술도입비 현물산정 신청서"
   assert "·" not in item.detail
   assert item.detail.endswith("확인합니다.")
   assert "·" not in item.next_action
   assert item.next_action.endswith("확인합니다.")
   item = PreparationChecklistItem(
       title="(양식10) 외부기술도입비 현물산정 신청서",
       detail="재무팀 · 최신 증빙을 확인한다.",
       next_action="사업담당자 · 제출 여부를 확인한다.",
   )

   assert item.title == "외부기술도입비 현물산정 신청서"
   assert "·" not in item.detail
   assert item.detail.endswith("확인합니다.")
   assert "·" not in item.next_action
   assert item.next_action.endswith("확인합니다.")
def test_supplements_submission_forms_omitted_by_model() -> None:
    proposal_runner = ProposalRunner()
    proposal = asyncio.run(proposal_runner.generate(document(), result()))
    request = document()
    request.attachments[0].file_name = "붙임2. 제출서류 양식.zip"
    request.attachments[0].extracted_text = (
        "[파일: 사업계획서.hwp]\n사업계획서를 제출해야 합니다.\n"
        "[파일: 참여확인서.hwp]\n참여확인서를 제출해야 합니다.\n"
        "산업 AI 공급기업이 신청할 수 있습니다."
    )
    proposal.preparation.eligibility_checklist[0].source.attachment_name = "사업계획서.hwp"
    proposal.preparation.submission_documents[0].source.attachment_name = "사업계획서.hwp"

    _retain_verified_source_references(proposal, request)

    titles = [item.title for item in proposal.preparation.submission_documents]
    assert "사업계획서" in titles
    assert "참여확인서" in titles
    supplemented = next(
        item
        for item in proposal.preparation.submission_documents
        if item.title == "참여확인서"
    )
    assert supplemented.source.location == "붙임2. 제출서류 양식.zip"


def test_supplements_high_confidence_eligibility_requirements_from_source() -> None:
    proposal = asyncio.run(ProposalRunner().generate(document(), result()))
    request = document()
    request.content_text = (
        "국내 2개 기관과 독일 2개 기관이 참여하여 최소 4개 기관으로 "
        "컨소시엄을 구성하여야 합니다. "
        "국내기관과 해외기관은 양국 정부에 동시에 과제를 신청해야 합니다. "
        "국내기업은 창업 1년 이상 경과하여야 합니다. "
        "기업부설연구소 인정서를 보유하여야 합니다."
    )

    _retain_verified_source_references(proposal, request)

    titles = [item.title for item in proposal.preparation.eligibility_checklist]
    assert "공고에서 요구하는 컨소시엄 구성 여부" in titles
    assert "복수 기관 또는 국가의 동시 신청 여부" in titles
    assert "공고에서 요구하는 설립 또는 사업 운영 기간 충족 여부" in titles
    assert "공고에서 요구하는 인정서 또는 인증서 보유 여부" in titles
    consortium = next(
        item
        for item in proposal.preparation.eligibility_checklist
        if item.title == "공고에서 요구하는 컨소시엄 구성 여부"
    )
    assert "2개 기관" in consortium.source.excerpt
    assert "최소 4개 기관" in consortium.detail


def test_does_not_duplicate_requirement_already_linked_to_source_excerpt() -> None:
    proposal = asyncio.run(ProposalRunner().generate(document(), result()))
    request = document()
    requirement = "국내 2개 기관과 독일 2개 기관으로 최소 4개 기관을 구성하여야 합니다."
    request.content_text = requirement
    proposal.preparation.eligibility_checklist.append(
        proposal.preparation.eligibility_checklist[0].model_copy(
            update={
                "title": "한독 컨소시엄 구성 요건",
                "source": RequirementSource(
                    origin=EvidenceOrigin.NOTICE_BODY,
                    section_title="신청자격",
                    excerpt=requirement,
                ),
            }
        )
    )

    _retain_verified_source_references(proposal, request)

    matching_items = [
        item
        for item in proposal.preparation.eligibility_checklist
        if "컨소시엄" in item.title
    ]
    assert len(matching_items) == 1


def test_detects_international_requirement_without_specific_country_rule() -> None:
    assert _is_international_requirement(
        "한국 2개 기관과 프랑스 2개 기관으로 컨소시엄을 구성합니다."
    )
    assert _is_international_requirement(
        "일본기관과 캐나다기관이 공동으로 과제에 참여합니다."
    )
    assert not _is_international_requirement(
        "국내 3개 기관으로 컨소시엄을 구성합니다."
    )


def test_merges_near_duplicate_requirements_across_notice_and_attachment() -> None:
    proposal = asyncio.run(ProposalRunner().generate(document(), result()))
    request = document()
    request.content_text = (
        "한국 2개 기관과 프랑스 2개 기관이 참여하여 최소 4개 기관으로 "
        "컨소시엄을 구성하여야 합니다."
    )
    request.attachments[0].extracted_text = (
        "한국 2개 기관 및 프랑스 2개 기관이 참여하여 최소 4개 기관의 "
        "컨소시엄을 구성하여야 합니다. 사업계획서를 제출해야 합니다."
    )

    _retain_verified_source_references(proposal, request)

    consortium_items = [
        item
        for item in proposal.preparation.eligibility_checklist
        if item.title == "공고에서 요구하는 컨소시엄 구성 여부"
    ]
    assert len(consortium_items) == 1


def test_normalizes_checklist_roles_and_removes_duplicates() -> None:
    proposal = asyncio.run(ProposalRunner().generate(document(), result()))
    preparation = proposal.preparation
    document_item = preparation.submission_documents[0].model_copy(
        update={
            "title": "법인등기부등본 및 사업자등록증 사본 준비",
        }
    )
    preparation.eligibility_checklist.extend(
        [
            document_item,
            preparation.eligibility_checklist[0].model_copy(
                update={"title": "접수 마감일 확인"}
            ),
        ]
    )
    preparation.company_inputs.append(
        document_item.model_copy(update={"title": "법인등기부등본 및 사업자등록증 사본"})
    )

    _normalize_preparation_structure(proposal)

    eligibility_titles = [item.title for item in preparation.eligibility_checklist]
    document_titles = [item.title for item in preparation.submission_documents]
    assert "접수 마감일 확인" not in eligibility_titles
    assert "법인등기부등본 및 사업자등록증 사본 준비" not in eligibility_titles
    assert sum("법인등기부등본" in title for title in document_titles) == 1
    next(
        item
        for item in preparation.submission_documents
        if "법인등기부등본" in item.title
    )
    assert all("법인등기부등본" not in item.title for item in preparation.company_inputs)


def test_holds_strategy_until_sandbox_approval_is_officially_verified() -> None:
    proposal = asyncio.run(ProposalRunner().generate(document(), result()))
    proposal.preparation.eligibility_checklist.append(
        proposal.preparation.eligibility_checklist[0].model_copy(
            update={
                "title": "산업융합 규제샌드박스 실증특례 승인 및 사업 개시 여부",
                "company_evidence_level": CompanyEvidenceLevel.UNKNOWN,
            }
        )
    )

    _apply_strategy_eligibility_guardrails(proposal)

    strategy = proposal.preparation.strategy
    assert strategy.decision == StrategyDecision.HOLD
    assert strategy.recommended_project.startswith("규제특례 승인 제품 또는 서비스 확인 후")
    assert "별도의 영업 기회" in strategy.alternative_participation
    assert "대안 역할" not in strategy.alternative_participation
