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
    source_urls: tuple[str, ...]


BISTELLIGENCE_PROFILE = CompanyProfile(
    company_name="BISTelligence(비스텔리젼스)",
    description="제조 현장 데이터를 분석해 문제를 진단하고 산업 AI 해결책을 설계·구축·운영하는 기업",
    verified_facts=(
        "공식 홈페이지 기준 제조 현장 경험 25년을 보유한 산업 AI 기업",
        "잡플래닛 공개 기업정보 기준 기업 형태는 중소기업",
        "잡플래닛 공개 기업정보 기준 설립일은 2021년 7월 27일",
        "잡플래닛 공개 기업정보 기준 임직원 수는 120명",
        "잡플래닛 공개 기업정보 기준 2024년 매출액은 112억 원",
        "잡플래닛 공개 기업정보 기준 대표자는 이한주",
    ),
    business_areas=("산업 AI", "스마트팩토리", "자율제조", "제조 데이터 분석"),
    services=(
        "AI 엔지니어링 컨설팅", "산업 AI 솔루션 구축", "데이터 컨설팅",
        "제조 데이터 수집·통합", "AI·ML 모델링", "설비 예지보전",
        "품질·공정 최적화", "제조 시스템 모니터링·자동화", "시스템 통합 및 운영",
    ),
    technologies=(
        "Artificial Intelligence", "Machine Learning", "Large Language Model",
        "데이터 분석·모델링", "Feature Engineering", "Edge Data Collection",
        "Engineering Data Integration", "공정 예측·제어 모델", "대용량 제조 데이터 시각화",
    ),
    target_industries=(
        "반도체·디스플레이", "철강", "제약·바이오", "공공",
        "자동차", "이차전지", "조선·해양",
    ),
    relevant_project_types=(
        "AI 기술개발·사업화", "스마트공장·자율제조", "제조 데이터 플랫폼",
        "공정·품질 개선", "설비 예지보전", "생산 모니터링",
        "공공 스마트 인프라", "제조 시스템 통합·운영",
    ),
    case_studies=(
        "반도체 장비 OEE 시스템에서 로그 분석 자동화와 맞춤형 차트를 구축해 관리 효율 17% 증가",
        "Wafer 제조 공정 관리 시스템을 웹 기반으로 전환해 데이터 조회 속도 5배 개선",
        "철강 AI 예측·제어 모델 컨설팅과 분석 보고 시스템 구축",
        "이차전지 SPC 시스템에서 대용량 데이터 100만 건을 2초 내 시각화",
        "지자체 수도 사업소에 AI 기반 펌프 관제와 잔여 수명 예측 시스템 구축",
        "자동차 부품 생산 현장에 설비 상태 원격 모니터링과 생산량 관리 시스템 구축",
    ),
    location="서울특별시 서초구 바우뫼로 128 비스텔타워",
    evidence_limitations=(
        "중소기업, 임직원 수와 매출액은 외부 기업정보 출처이므로 공고 신청 시 공식 증빙을 별도로 확인해야 함",
        "공식 홈페이지 구축 사례는 회사가 공개한 사례이며 개별 공고의 실적 인정 여부는 발주기관 기준으로 다시 확인해야 함",
    ),
    unknown_fields=(
        "중소기업확인서 및 공고별 기업 규모 기준 충족 여부", "보유 인증", "보유 특허",
        "자본금", "공고별 요구 실적을 증명할 계약서·확인서 보유 여부",
    ),
    source_urls=(
        "https://www.bistelligence.ai/",
        "https://www.jobplanet.co.kr/companies/397061/landing",
    ),
)
