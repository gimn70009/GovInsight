# ruff: noqa: E501
from dataclasses import dataclass


@dataclass(frozen=True)
class CompanyProfile:
    company_name: str
    description: str
    business_areas: tuple[str, ...]
    services: tuple[str, ...]
    technologies: tuple[str, ...]
    target_industries: tuple[str, ...]
    relevant_project_types: tuple[str, ...]
    location: str
    unknown_fields: tuple[str, ...]
    source_url: str


BISTELLIGENCE_PROFILE = CompanyProfile(
    company_name="BISTelligence(비스텔리젼스)",
    description="제조 현장 데이터를 분석해 문제를 진단하고 산업 AI 해결책을 설계·구축·운영하는 기업",
    business_areas=("산업 AI", "스마트팩토리", "자율제조", "제조 데이터 분석"),
    services=(
        "AI 엔지니어링 컨설팅",
        "산업 AI 솔루션 구축",
        "데이터 컨설팅",
        "제조 데이터 수집·통합",
        "AI·ML 모델링",
        "설비 예지보전",
        "품질·공정 최적화",
        "제조 시스템 모니터링·자동화",
    ),
    technologies=(
        "Artificial Intelligence",
        "Machine Learning",
        "Large Language Model",
        "데이터 분석·모델링",
        "Feature Engineering",
        "Edge Data Collection",
        "Engineering Data Integration",
    ),
    target_industries=(
        "반도체·디스플레이",
        "철강",
        "제약·바이오",
        "공공",
        "자동차",
        "이차전지",
        "조선·해양",
    ),
    relevant_project_types=(
        "AI 기술개발·사업화",
        "스마트공장·자율제조",
        "제조 데이터 플랫폼",
        "공정·품질 개선",
        "설비 예지보전",
        "생산 모니터링",
        "공공 스마트 인프라",
    ),
    location="서울특별시 서초구 바우뫼로 128 비스텔타워",
    unknown_fields=(
        "기업 규모",
        "중소기업·중견기업 여부",
        "설립연도",
        "임직원 수",
        "매출액",
        "보유 인증",
        "보유 특허",
        "자본금",
    ),
    source_url="https://www.bistelligence.ai/",
)
