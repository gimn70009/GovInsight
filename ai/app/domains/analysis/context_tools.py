import json

from app.domains.analysis.company_profile import CompanyProfile
from app.domains.analysis.schemas.request import AnalysisDocumentRequest


class ContextToolProtocol:
    document: AnalysisDocumentRequest
    company_profile: CompanyProfile


def read_company_profile(context: ContextToolProtocol) -> str:
    return serialize_company_profile(context.company_profile)


def serialize_company_profile(profile: CompanyProfile) -> str:
    payload = {
        "companyName": profile.company_name,
        "description": profile.description,
        "verifiedFacts": profile.verified_facts,
        "businessAreas": profile.business_areas,
        "services": profile.services,
        "technologies": profile.technologies,
        "targetIndustries": profile.target_industries,
        "relevantProjectTypes": profile.relevant_project_types,
        "caseStudies": profile.case_studies,
        "location": profile.location,
        "evidenceLimitations": profile.evidence_limitations,
        "unknownFields": profile.unknown_fields,
    }
    return json.dumps(payload, ensure_ascii=False)


def read_previous_analysis(context: ContextToolProtocol) -> str:
    previous = context.document.previous_analysis
    if previous is None:
        return json.dumps({"available": False}, ensure_ascii=False)
    return json.dumps(
        {"available": True, **previous.model_dump(by_alias=True)}, ensure_ascii=False
    )


COMPANY_CONTEXT_INSTRUCTIONS = """
회사 사실은 제공된 회사 프로필에서 확인되는 내용만 사용합니다.
공공기관 공고의 실제 수요와 회사의 확인된 사업·역량·수행 사례를 연결합니다.
evidenceLimitations와 unknownFields는 확인이 필요한 정보이며 미보유·미충족을 뜻하지 않습니다.
공개 기업정보와 수행 사례만으로 공고별 자격·인증·실적 증빙을 충족한다고 확정하지 않습니다.
확인되지 않은 고객 계약·장비·예산·인력 여력·서류 보유 상태·지원 이력을 만들어내지 않습니다.
이전 분석이나 저장 초안의 회사 상황은 현재 회사 프로필에 없는 사실의 근거로 사용하지 않습니다.
회사 정보와 공고의 대상·금액·기한·요구사항을 구분하고, 공고 원문 인용에 회사 가정을 섞지 않습니다.
""".strip()


NOTICE_APPLICABILITY_INSTRUCTIONS = """
회사에 적용할 조건을 설명하기 전에 공고의 접수 상태와 참여 역할을 구분합니다.
접수가 종료되면 회사 관련성은 남아 있어도 현재 신청·서류 제출·해당 회차 발표 준비를 권하지
않습니다.
지난 평가 일정을 '예정'으로 표현하지 않습니다. 후속 모집·참여 기회는 별도 공고가 확인돼야
합니다.
공급기업·수요기업·주관기관·용역사업자의 자격과 제출서류를 구분하고 요구되는 대상까지 명시합니다.
수요기업의 중견기업 확인서를 AI 공급기업인 우리 회사의 필수 자격이나 부족한 서류로 옮기지
않습니다.
비영리 주관기관 조건은 회사의 주관 역할을 제한합니다. 공급기업·용역 참여가 허용되면 그 경로와
구분합니다.
우리 회사가 주관기관이 될 수 없다는 이유만으로 전체 사업 참여가 불가능하거나 부적합하다고
결론내리지 않습니다.
총사업비 기준 컨소시엄 부담 비율을 우리 회사 단독 부담액이나 모든 기관의 동일 비율로 해석하지
않습니다.
회사에 자원 수요가 있어도 공고의 지원 방식·비용 인정 범위가 맞지 않으면 수요 해결에
부적합하다고 설명합니다.
확인된 회사 자산과 내부 참여 여력을 공고의 인정 비용·법정 인건비계상률로 자동 환산하지
않습니다.
""".strip()


EVIDENCE_COVERAGE_INSTRUCTIONS = """
원문 coverage.truncated가 true이면 일부 구간만 확인한 것입니다.
sourceRanges는 해당 파일 원문 문자열의 0부터 시작하는 문자 구간(start 포함, end 제외)입니다.
생략 표시는 원문 문장이 아니며, 서로 떨어진 구간을 연결해 하나의 조건으로 해석하지 않습니다.
본문에 없는 조건이 첨부에도 없다고 단정하지 않습니다. 확인 범위를 넘는 결론은 확인 필요로 남깁니다.
duplicateOfAttachmentId는 같은 추출 텍스트의 다른 파일이며 새로운 독립 근거가 아닙니다.
파싱된 원문이 없거나 선택된 구간이 비어 있는 파일은 내용 확인 완료로 간주하지 않습니다.
날짜·금액·자격에는 대상/국가/차수/단계와 예외·제외 조건을 함께 확인합니다.
서로 다른 기한·금액을 발견하면 역할과 단계가 다른지 먼저 구분합니다. 적용 범위가 불명확하면
임의로 하나를 선택하거나 평균내지 말고 원문 간 차이와 확인 필요를 설명합니다.
각 핵심 사실은 현재 본문 또는 해당 첨부의 실제 구절로 확인한 경우만 작성합니다.
표의 열 제목, 빈 서식의 예시, 작성요령을 실제 신청 대상이나 확정된 값으로 사용하지 않습니다.
""".strip()
