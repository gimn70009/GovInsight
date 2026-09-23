# ruff: noqa: E501
from dataclasses import dataclass


@dataclass(frozen=True)
class CompanyProfile:
    company_name: str
    description: str
    verified_facts: tuple[str, ...]
    business_areas: tuple[str, ...]
    services: tuple[str, ...]
    technologies: tuple[str, ...]
    target_industries: tuple[str, ...]
    relevant_project_types: tuple[str, ...]
    case_studies: tuple[str, ...]
    location: str
    evidence_limitations: tuple[str, ...]
    unknown_fields: tuple[str, ...]


BISTELLIGENCE_PROFILE = CompanyProfile(
    company_name="BISTelligence(비스텔리젼스)",
    description=(
        "반도체·디스플레이·철강·조선·중공업 현장의 제조 데이터와 맥락을 연결해 "
        "산업 AI 솔루션·에이전트를 설계·개발·구축하는 기업"
    ),
    verified_facts=(
        "제조 현장 경험 25년을 보유한 산업 AI 기업",
        "기업 형태는 중소기업",
        "설립일은 2021년 7월 27일",
        "임직원 수는 120명",
        "2024년 매출액은 112억 원",
        "대표자는 이한주",
    ),
    business_areas=(
        "제조 AI 에이전트", "산업 AI", "반도체·디스플레이 제조 AI", "철강 제조 AI",
        "매뉴팩처링 인텔리전스(Manufacturing Intelligence)",
    ),
    services=(
        "제조 AI 에이전트 설계·개발", "AI 엔지니어링 컨설팅", "산업 AI 솔루션 구축",
        "데이터 컨설팅", "제조 데이터 수집·통합", "AI 학습용 데이터 정제(Data Ready)",
        "제조 데이터 기반 AI·ML 모델링",
        "AI 기반 설비 예지보전", "AI 기반 품질·공정 최적화", "제조 의사결정 자동화",
        "제조 시스템 모니터링·자동화", "시스템 통합 및 운영",
        "고객과 문제 정의·데이터 요건 설계·모델 학습·현장 적용·운영 결과 기반 개선 공동 수행",
    ),
    technologies=(
        "Artificial Intelligence", "Machine Learning", "Large Language Model", "AI Agent",
        "데이터 분석·모델링", "Feature Engineering", "Edge Data Collection",
        "Engineering Data Integration", "공정 예측·제어 모델", "대용량 제조 데이터 시각화",
        "센서·공정·제품·설비·레시피·운전 조건·외부 환경을 연결한 맥락(Context) 기반 원인 분석",
    ),
    target_industries=("반도체", "디스플레이", "철강", "조선", "중공업"),
    relevant_project_types=(
        "제조 AI 에이전트 기술개발·사업화", "AI 기반 자율제조",
        "AI 기반 공정·품질 개선", "AI 기반 설비 예지보전",
        "제조 의사결정 자동화", "AI 기술개발·사업화", "스마트공장·자율제조",
        "제조 데이터 플랫폼", "공정·품질 개선", "설비 예지보전", "생산 모니터링",
        "공공 스마트 인프라", "제조 시스템 통합·운영",
        "제조 AI 학습을 위한 데이터 레디 구축",
        "숙련 엔지니어 경험·노하우의 디지털 자산화(지향 과제)",
    ),
    case_studies=(
        "반도체 장비 OEE 시스템에서 로그 분석 자동화와 맞춤형 차트를 구축해 관리 효율 17% 증가",
        "Wafer 제조 공정 관리 시스템을 웹 기반으로 전환해 데이터 조회 속도 5배 개선",
        "철강 AI 예측·제어 모델 컨설팅과 분석 보고 시스템 구축",
        "이차전지 SPC 시스템에서 대용량 데이터 100만 건을 2초 내 시각화",
        "지자체 수도 사업소에 AI 기반 펌프 관제와 잔여 수명 예측 시스템 구축",
        "자동차 부품 생산 현장에 설비 상태 원격 모니터링과 생산량 관리 시스템 구축",
        "2026년 7월 기준 국내 대형 제조 현장의 핵심 설비에서 진동·압력·운전 조건·외부 환경을 결합해 이상 징후와 유지보수 시점을 예측하는 AI 개발 중. 고객명·성과 수치는 미공개",
    ),
    location="서울특별시 서초구 바우뫼로 128 비스텔타워",
    evidence_limitations=(
        "중소기업, 임직원 수와 매출액은 외부 기업정보 출처이므로 공고 신청 시 공식 증빙을 별도로 확인해야 함",
        "공개 수행 사례의 공고별 실적 인정 여부는 별도 확인하며, 개발 중인 과제와 지향 과제는 완료 실적·검증된 성과로 간주하지 않음",
    ),
    unknown_fields=(
        "중소기업확인서 및 공고별 기업 규모 기준 충족 여부", "보유 인증", "보유 특허",
        "자본금", "공고별 요구 실적을 증명할 계약서·확인서 보유 여부",
        "SK하이닉스·삼성·세아와의 업무 관련성(사용자 언급): 정확한 법인, 고객·파트너 관계, 수행 내용·기간 미확인. 계약·완료 실적·파트너 확보 근거로 사용하지 않음",
    ),
)
