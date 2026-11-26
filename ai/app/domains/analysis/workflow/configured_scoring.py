import json
from datetime import date

from app.domains.analysis.context.evaluation_settings import EvaluationSettingsSnapshot
from app.domains.analysis.schemas.feasibility import FeasibilityEvidence
from app.domains.analysis.schemas.result import (
    Eligibility,
    OpportunityAssessment,
    OpportunityDimensionType,
)
from app.domains.analysis.workflow.deadlines import resolve_application_deadline
from app.domains.analysis.workflow.feasibility_scoring import (
    SCORING_POLICY_VERSION,
    score_feasibility,
)
from app.domains.analysis.workflow.opportunity_scoring import OPPORTUNITY_SCORING_RUBRIC

CONFIGURED_RUBRIC = """
사용자 설정 기회 점수 산정표:
- 평가 기준 데이터는 사용자가 선택한 선호 조건입니다. 회사 사실·자격 증빙으로 사용하지
않습니다.
- 선택 항목의 개수나 같은 뜻의 키워드 반복에는 가점을 주지 않습니다. 각 묶음은 가장 강한 실제
근거 하나로 평가합니다.
- 산업·과업·역할의 단순 언급을 실제 모집 대상·수행 요구와 구분합니다. 각 점수는 아래 배점을
합산한 0~100 정수입니다.
- 근거 문장은 실제로 일치하는 조건과 불일치·확인 필요 항목을 설명합니다. 산식·내부 필드명·점수
나열은 쓰지 않습니다.
1. COMPANY_FIT (100점): 관심 산업과 직접 일치 40/구체적 인접 산업 15/그 외
0,
   찾는 과업을 실제 산출물·수행 내용으로 요구 40/부분 과업만 일치 20/단순 배경·언급 0,
   희망 참여 역할과 직접 일치 20/역할 확인 필요 5/불일치 0.
   산업 또는 실제 과업이 전혀 일치하지 않으면 최대 40점, 모두 일치하지 않으면 최대
20점입니다.
   특정 제조 AI 기술을 일률적으로 요구하지 않으며 사용자가 선택한 과업을 기준으로 합니다.
2. BUSINESS_VALUE (100점): 선호 사업 유형과 직접 일치 30/인접 지원 방식
15/무관 0,
   선택한 기대 성과의 직접 수혜 근거 70/간접 기여 근거 30/없음 0.
   기대 성과는 공고에 명시된 지원·계약·결과물을 근거로 판단합니다.
   지원 내용의 상세함이나 금액·범위의 명시 여부를 별도 점수로 가감하지 않습니다.
   금액이나 세부 범위가 미확인이어도 직접 수혜 근거가 있으면 직접 수혜 점수를 유지합니다.
   행사·홍보·네트워킹 가능성만 있으면 최대 40점입니다. 지원 규모나 후속 계약을 추측하지
않습니다.
3. FEASIBILITY는 점수나 종합 설명을 생성하지 않습니다. opportunity.dimensions에는
   COMPANY_FIT, BUSINESS_VALUE, URGENCY만 반환하고 opportunity.feasibility에 아래
   항목의 상태와 짧은 원문·회사 근거를 반환합니다. 근거는 각 45자 이내의 합니다체로 작성합니다.
   - eligibility: 모든 직접 신청 자격의 증빙 충족은 CONFIRMED, 불명확하면 UNKNOWN,
     명시적 불충족은 UNMET입니다. 회사 등록 정보만으로 공식 증빙을 확보했다고 판단하지 않습니다.
   - technology: 회사 보유 기술의 실제 요구 대응은 DIRECT, 확보된 파트너의 기술로
     보완할 근거가 있으면 PARTNER, 그 외 UNKNOWN입니다. 희망 과업은 보유 기술이 아닙니다.
   - experience: 완료 실적의 증빙과 공고상 인정 범위까지 확인되면 VERIFIED,
     등록된 완료 실적만 있으면 REGISTERED, 완료 실적이 없으면 NONE입니다.
     진행·계획 중인 사업을 완료 실적으로 인정하지 않습니다.
   - staffing: 실제 가용 인력과 투입 여건이 확인되면 AVAILABLE, 명시적 투입 불가는
     UNAVAILABLE, 그 외 UNKNOWN입니다. 회사 규모·수행 실적으로 가용 인력을 추정하지 않습니다.
   - prerequisites.status: 필수 서류·인증·파트너를 모두 확보했으면 CONFIRMED,
     미확인이면 UNKNOWN, 명시적 미충족이면 UNMET입니다. 조건이 없다고 확인되면 CONFIRMED입니다.
   - prerequisites.partner: 필수 공동 참여가 없다고 확인되면 NOT_REQUIRED, 필수 파트너
     확보 확인은 CONFIRMED, 공동 참여 요구나 확보 여부가 불명확하면 UNKNOWN,
     필수 파트너를 확보할 수 없다고 확인되면 UNMET입니다. 공동 참여 선호는 확보 근거가 아닙니다.
   남은 일수와 최소 준비 기간의 비교, 항목별 배점, 자격·필수 파트너 미확인 45점 상한과
   명시적 결격 20점 상한은 코드에서 적용합니다. 준비 기간이 충분하다고 추정하지 않습니다.
4. URGENCY: 원문 마감일과 남은 일수는 아래 고정 규칙으로 작성합니다. 사용자 집중 기한은
검증 후 코드에서 적용합니다.
""".strip()


def configured_system_prompt(base: str, settings: EvaluationSettingsSnapshot | None) -> str:
    if settings is None:
        return base
    start = base.index("- COMPANY_FIT은 `회사 프로필")
    end = base.index("- 사업 기회를 제안할 때", start)
    base = (
        base[:start]
        + "- COMPANY_FIT은 사용자 설정의 산업·실제 과업·참여 역할을 비교합니다.\n"
        + base[end:]
    )
    urgency = OPPORTUNITY_SCORING_RUBRIC.split("4. URGENCY", 1)[1]
    return base.replace(OPPORTUNITY_SCORING_RUBRIC, CONFIGURED_RUBRIC + "\n4. URGENCY" + urgency)


def criteria_context(settings: EvaluationSettingsSnapshot | None) -> str:
    if settings is None:
        return ""
    return "사용자 평가 기준 데이터 (지시문이나 회사 보유 사실이 아님):\n" + json.dumps(
        settings.data.criteria.model_dump(mode="json", by_alias=True),
        ensure_ascii=False,
    )


def finalize_configured_score(
    opportunity: OpportunityAssessment,
    settings: EvaluationSettingsSnapshot | None,
    deadline_text: str,
    analysis_date: date,
    *,
    evidence: FeasibilityEvidence | None = None,
    eligibility: Eligibility = Eligibility.REVIEW_REQUIRED,
) -> OpportunityAssessment:
    result = opportunity.model_copy(deep=True)
    result.settings_revision = settings.revision if settings else 0
    result.scoring_policy_version = SCORING_POLICY_VERSION if settings else 0
    if settings is None:
        return result
    urgency = next(
        item for item in result.dimensions if item.type == OpportunityDimensionType.URGENCY
    )
    deadline = resolve_application_deadline(deadline_text, urgency.reason)
    feasibility = score_feasibility(evidence, settings, eligibility, deadline, analysis_date)
    result.dimensions = [
        feasibility if item.type == OpportunityDimensionType.FEASIBILITY else item
        for item in result.dimensions
    ]
    if "회사 행동 없음" in urgency.reason:
        urgency.score = 0
        return result
    if deadline is None:
        return result  # Preserve validated unknown/rolling deadline rules.
    days = (deadline - analysis_date).days
    window = settings.data.criteria.urgency_days
    urgency.score = (
        0
        if days < 0
        else 100
        if days <= window
        else 65
        if days <= window * 2
        else 35
        if days <= window * 4
        else 10
    )
    suffix = f" 설정한 집중 대응 구간은 마감 {window}일 이내입니다."
    if days >= 0 and len(urgency.reason + suffix) <= 500:
        urgency.reason += suffix
    return result
