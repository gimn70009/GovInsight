import json
import re
from dataclasses import asdict

from app.domains.analysis.company_profile import CompanyProfile
from app.domains.analysis.schemas.request import AnalysisDocumentRequest


class ContextToolProtocol:
    document: AnalysisDocumentRequest
    company_profile: CompanyProfile
    include_demo_profile: bool = False


def read_company_profile(
    context: ContextToolProtocol, *, include_demo: bool | None = None,
) -> str:
    if include_demo is None:
        include_demo = getattr(context, "include_demo_profile", False)
    return serialize_company_profile(context.company_profile, include_demo=include_demo)


def uses_demo_profile(context: ContextToolProtocol) -> bool:
    return bool(
        getattr(context, "include_demo_profile", False)
        and context.company_profile.demo_profile is not None
    )


def serialize_company_profile(profile: CompanyProfile, *, include_demo: bool = False) -> str:
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
        "sourceUrls": profile.source_urls,
    }
    if include_demo and profile.demo_profile is not None:
        payload["demoProfile"] = {
            "dataType": "SYNTHETIC_DEMO",
            "isVerifiedCompanyFact": False,
            "usagePolicy": (
                "아래는 전부 테스트용 가상 시나리오이며 "
                "실제 회사의 사실·서류·지원 이력이 아닙니다. "
                "사용자는 이 시나리오를 회사의 현재 운영 상황으로 설명하도록 "
                "요청했습니다. 본문은 우리 회사의 자원·업무·서류 상태로 자연스럽게 "
                "서술하고 가상·데모 접두어를 반복하지 않습니다. "
                "입력된 운영 정보와 원본 증빙 검증을 구분하며, "
                "공고 자격·법률 위반·제출 증빙 검증을 자동 확정하지 않습니다. "
                "AVAILABLE은 가상 보유, MISSING은 가상 미보유, UNKNOWN은 미확인입니다. "
                "누락은 UNKNOWN으로 취급합니다. 유효기간은 assumed_as_of 기준 가정이며 "
                "신청일에도 유효하다고 단정하지 않습니다. 실제 unknownFields를 해소하지 않습니다."
            ),
            **asdict(profile.demo_profile),
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
회사 소개와 demoProfile은 출처 성격이 다른 자료입니다.
demoProfile이 있으면 가상 고객 업무·자원 수요·인력 여력·서류 상태를 함께 참고합니다.
공공기관 공고의 실제 수요와 회사의 공급 역량 또는 필요한 지원을 연결합니다.
사용자가 선택한 demoProfile은 이번 분석에서 우리 회사의 현재 운영 상황으로 적용합니다.
본문에서는 '데모 가정에서는', '가상으로', '시나리오상' 같은 접두어 없이 '우리 회사는'으로
자연스럽게 설명합니다.
데모 여부는 별도 화면 안내와 usesDemoProfile 메타데이터가 표시하므로 본문마다
반복하지 않습니다.
GPU 공유·인력 배정·예산 초안·서류 보유 상태가 명시돼 있으면 구체적으로 연결하고 정보가
없다고 반복하지 않습니다.
자원·서류 상태는 demoProfile의 명시 정보를 우선하되, 보유 정보가 공고별 유효성·공식
검증 완료를 의미하지는 않습니다.
AVAILABLE은 회사 프로필에 보유로 등록된 상태입니다. 원본 증빙 확인과 해당 공고 기준
충족 여부는 별도입니다.
실제 unknownFields를 해소하거나 공식 사실 목록을 덮어쓰지는 않습니다.
MISSING과 UNKNOWN을 구분하고 미확인을 미보유·미충족으로 단정하지 않습니다.
고객 업무·GPU 수요·희망 사양·예산·내부 목표일은 공고의 요구사항·지원금·신청 마감이
아닙니다.
GPU 공급업체 모집과 GPU 이용 지원을 구분합니다.
원문에 없는 모집·고객 수요·지원 절차를 만들지 않습니다.
실제 공고일·신청일과 데모 기준일·희망 기간을 대조합니다.
지난 수요를 현재의 긴급 수요로 단정하지 않습니다.
자료의 가상 출처 표시는 내부적으로 유지합니다. 원본 검증 없이
OFFICIAL_DOCUMENT·USER_CONFIRMED로 격상하지 않습니다.
가상 계약을 실제 고객사와 연결하거나 비교표·원문 인용·법률 조항에 회사 가정을 섞지 않습니다.
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
회사에 GPU 수요가 있어도 공고의 지원 방식·비용 인정 범위가 맞지 않으면 수요 해결에
부적합하다고 설명합니다.
회사 보유 GPU와 내부 참여 여력을 공고의 인정 비용·법정 인건비계상률로 자동 환산하지
않습니다.
""".strip()


def normalize_company_narrative(text: str) -> str:
    """Remove scenario preambles from generated prose, never from quoted evidence."""
    return re.sub(
        r"(?:데모\s*(?:가정|시나리오|프로필)|가상\s*(?:가정|시나리오))"
        r"(?:에서는|에서|에\s*따르면|상으로는|상으로|상에서는|상)\s*[,，]?\s*",
        "",
        text,
    ).strip()
