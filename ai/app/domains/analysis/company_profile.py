# ruff: noqa: E501
from dataclasses import dataclass
from typing import Literal

# 사용자가 요청한 실행 기본값: 실제 소개와 데모 가정을 함께 읽습니다.
USE_DEMO_COMPANY_PROFILE = True


@dataclass(frozen=True)
class DemoDocument:
    """A fictional document inventory entry, never an actual company credential."""

    name: str
    status: Literal["AVAILABLE", "MISSING", "UNKNOWN"]
    detail: str
    valid_until: str | None = None
    owner: str = "미정"
    target_date: str | None = None


@dataclass(frozen=True)
class DemoStaffCapacity:
    role: str
    committed_percent: int
    available_percent: int
    reserve_percent: int


@dataclass(frozen=True)
class DemoResourceNeed:
    need_id: str
    name: str
    current_state: str
    intended_use: str
    requirements: tuple[str, ...]
    desired_start: str
    desired_end: str
    max_self_payment_won: int | None
    owner: str


@dataclass(frozen=True)
class DemoCompanyProfile:
    """Fictional company context for insights from monitored public notices."""

    scenario_name: str
    assumed_as_of: str
    monitoring_purpose: str
    operating_context: tuple[str, ...]
    customer_projects: tuple[str, ...]
    supply_capabilities: tuple[str, ...]
    resource_needs: tuple[DemoResourceNeed, ...]
    staff_capacity: tuple[DemoStaffCapacity, ...]
    participation_constraints: tuple[str, ...]
    documents: tuple[DemoDocument, ...]
    reusable_assets: tuple[str, ...]
    support_history: tuple[str, ...]
    notice_evaluation_criteria: tuple[str, ...]
    open_questions: tuple[str, ...]


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
    demo_profile: DemoCompanyProfile | None = None


BISTELLIGENCE_PROFILE = CompanyProfile(
    company_name="BISTelligence(비스텔리젼스)",
    description=(
        "반도체·디스플레이·철강 제조 현장을 중심으로 산업 AI 에이전트를 "
        "설계·개발·구축하는 기업"
    ),
    verified_facts=(
        "공식 홈페이지 기준 제조 현장 경험 25년을 보유한 산업 AI 기업",
        "잡플래닛 공개 기업정보 기준 기업 형태는 중소기업",
        "잡플래닛 공개 기업정보 기준 설립일은 2021년 7월 27일",
        "잡플래닛 공개 기업정보 기준 임직원 수는 120명",
        "잡플래닛 공개 기업정보 기준 2024년 매출액은 112억 원",
        "잡플래닛 공개 기업정보 기준 대표자는 이한주",
    ),
    business_areas=(
        "제조 AI 에이전트", "산업 AI", "반도체·디스플레이 제조 AI", "철강 제조 AI",
    ),
    services=(
        "제조 AI 에이전트 설계·개발", "AI 엔지니어링 컨설팅", "산업 AI 솔루션 구축",
        "데이터 컨설팅", "제조 데이터 수집·통합", "제조 데이터 기반 AI·ML 모델링",
        "AI 기반 설비 예지보전", "AI 기반 품질·공정 최적화", "제조 의사결정 자동화",
        "제조 시스템 모니터링·자동화", "시스템 통합 및 운영",
    ),
    technologies=(
        "Artificial Intelligence", "Machine Learning", "Large Language Model", "AI Agent",
        "데이터 분석·모델링", "Feature Engineering", "Edge Data Collection",
        "Engineering Data Integration", "공정 예측·제어 모델", "대용량 제조 데이터 시각화",
    ),
    target_industries=("반도체", "디스플레이", "철강"),
    relevant_project_types=(
        "제조 AI 에이전트 기술개발·사업화", "AI 기반 자율제조",
        "AI 기반 공정·품질 개선", "AI 기반 설비 예지보전",
        "제조 의사결정 자동화", "AI 기술개발·사업화", "스마트공장·자율제조",
        "제조 데이터 플랫폼", "공정·품질 개선", "설비 예지보전", "생산 모니터링",
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
    # 아래 블록 전체는 테스트용 가상 정보입니다. 실제 회사 사실/증빙이 아닙니다.
    # 실제 소개와 구분된 demoProfile로 전달하며 실행 기본값은 두 프로필을 함께 사용합니다.
    demo_profile=DemoCompanyProfile(
        scenario_name="제조 고객 업무를 수행하며 공공기관 공고에서 사업 기회와 필요한 지원을 찾는 기업 (가상)",
        assumed_as_of="2026-09-08",
        monitoring_purpose=(
            "공공기관에서 모니터링한 실제 공고를 출발점으로, 당사가 수행할 사업·"
            "새 고객과 만날 기회·현재 부족한 자원을 해결할 지원을 찾고 필요성·참여 조건·다음 행동을 판단"
        ),
        operating_context=(
            "반도체·디스플레이·철강 고객의 제조 데이터·AI 소프트웨어 업무를 수행하는 기업 안의 사업개발·개발팀 상황을 가정",
            "관심 경로: 공공기관 발주·용역, 공공기관이 주관하는 수요기업 매칭·실증·공급기업 모집, GPU·클라우드·시험시설·전문가 지원",
            "고객사 A·B·C는 익명 가상 기업이며 특정 실제 고객사와 연결하지 않음. 기존 회사 프로필의 공개 사업·역량을 배경으로 삼되 가상 계약·자원 상태를 실제 사실에 합치지 않음",
            "신규 정부 R&D 과제·선정 이력·신청 예산은 미리 정하지 않음. 공고의 대상·목적·지원 내용과 현재 회사 상황을 대조한 뒤 참여 방향을 검토",
            "공공기관이 공개한 근거가 있는 기회만 평가. 일반 기업의 설비 보유나 산업 분야만으로 구매 수요·영업 연락처·협업 의사를 만들지 않음",
        ),
        customer_projects=(
            "DEMO-SI-A | 반도체 고객사 A | 2026-04~2027-03 | 유상 설비 로그 통합·알람 조회 시스템 구축. 30대 중 22대 연결 완료, 8대는 로그 형식 협의 중. 현장 데이터 연계·운영 화면 경험을 보여줄 수 있으나 계약 종료·최종 검수 전이라 완료 실적 인정은 미확인",
            "DEMO-SI-A 관련 내부 개발: 승인된 공개·합성 자료로 알람 원인 설명 기능을 시험 중. 고객에게 납품할 AI 기능이나 고객 데이터 학습 허락이 확정된 상태는 아님. 기존 고객 업무를 수행하며 공공기관의 시험 자원 지원 활용 가능성을 검토",
            "DEMO-SI-B | 디스플레이 고객사 B | 2026-07~2027-06 | 유상 검사 결과·설비 이벤트·불량 이력 분석 화면 개발. 분석팀은 회사가 사용 권리를 가진 시험 이미지로 분류 모델을 검토 중. 고객 검사 이미지의 외부 반출·AI 학습은 미승인",
            "DEMO-POC-C | 철강 고객사 C | 2026-08~2026-11 | 유상 압연 이력·품질 지표 예측 개념검증. 운전조건 누락과 평가 방법 정리가 과제이며 달성 성능은 미확정. 독립 시험·전문가 검토 지원이 실제 문제 해결에 도움이 되는지 검토",
            "위 고객 업무는 일부 가상 사례이며 회사 전체의 계약·매출 목록이 아님. 민간 유상 계약을 정부지원사업 수령 이력으로 분류하지 않음",
        ),
        supply_capabilities=(
            "제공 가능 업무: 제조 설비 데이터 수집·통합, 이상탐지·품질 예측 모델 검토, 엔지니어 분석 화면, 기존 제조 시스템 연계·운영",
            "공공 발주 접점: 위 업무를 실제 요구하는 시스템 구축·분석 용역에 대해 유사 수행 범위와 투입 인력을 설명할 수 있음. 특정 공고의 입찰 자격·필수 실적 충족은 증빙 대조 전",
            "새 고객 접점: 공공기관의 수요기업 매칭·실증·공급기업 모집에 실제 소개·상담·실증 연결 절차가 있을 때, 해당 수요와 관련 경험을 연결해 제안 방향을 검토",
            "역량 경계: 반도체 칩·디스플레이 패널 제조기업, GPU 장비 판매·대여업체로 설정하지 않음. 소프트웨어 공급 경험이 특정 공고의 제조업 수요기업 자격이나 장비 공급 자격을 뜻하지 않음",
            "고객별 데이터·공정 차이가 있으므로 반도체 결과를 디스플레이·철강의 검증 성능으로 확대하지 않음. 과거 고객명·로고·개선율은 공개 허락과 사실 확인 후 활용",
        ),
        resource_needs=(
            DemoResourceNeed(
                need_id="DEMO-NEED-GPU",
                name="두 개발팀의 모델 실험을 위한 추가 GPU 이용",
                current_state="가상 사내 GPU 24GB급 1대를 반도체 원인 설명 실험팀과 디스플레이 분석팀이 공유. 최근 2주 업무 기록에서 실험 시작이 1~2일 밀린 경우가 3회 있었다는 가정. 장비 이용률·메모리 사용량의 계측 자료는 아직 없음",
                intended_use="공개·합성 자료와 회사가 사용 권리를 가진 시험 자료로 모델 비교·배치 실험을 진행해 개발 대기를 줄임. 고객 현장 납품 데이터의 외부 학습을 전제로 하지 않음",
                requirements=(
                    "희망 자원: 서로 독립된 GPU 작업 슬롯 2개, 슬롯당 GPU 메모리 48GB급을 초기 가정. 실제 최소 사양·총 이용시간은 작업별 메모리·실행시간을 측정한 뒤 확정",
                    "희망 방식: 사내 장비 임차 또는 접근 통제가 가능한 원격 GPU 서비스. 장비 배송·설치형과 원격 이용형은 보안·운영·설치 조건을 각각 검토",
                    "외부 환경에는 사용 권리가 확인된 공개·합성·자체 시험 자료만 우선 검토. 고객 원본·비식별 고객 데이터도 별도 승인 없이 반출·학습 가능하다고 가정하지 않음",
                    "기존 1대의 예약 조정으로 지연이 해결되면 지원 필요성을 낮춰 판단. 지원 기간·대기 순번·작업량이 맞지 않으면 GPU라는 이유만으로 우선 추천하지 않음",
                    "회사 자체 지출 검토 상한은 두 슬롯 전체 4개월 합계 800만 원. 시장 견적이나 승인 예산이 아니며 지원 후 자부담·저장·통신·설치 비용을 함께 계산해야 함",
                ),
                desired_start="2026-10-01", desired_end="2027-01-31",
                max_self_payment_won=8_000_000, owner="AI 개발 책임자·보안 담당",
            ),
            DemoResourceNeed(
                need_id="DEMO-NEED-TEST",
                name="제조 모델의 독립 성능 평가·시험 환경",
                current_state="철강 예측 개념검증과 디스플레이 분석 실험에 내부 결과는 있지만 별도 평가 방법·외부 시험 성적서는 없는 가정",
                intended_use="기관의 시험시설·전문가 지원이 시계열 예측 또는 영상 분류 평가를 실제 제공할 때, 평가 설계와 결과 재현성을 검토",
                requirements=(
                    "장비 목록만 있는 시설 소개와 실제 이용 가능한 모집 공고를 구분. 시험 항목·접수 기간·자료 반출·비용·결과물 확인 필요",
                    "시험 성적서가 특정 발주 실적으로 인정된다고 단정하지 않음. 고객 데이터 이용 동의와 결과 공개 범위는 별도 확인",
                    "시험기관 비용 견적과 내부 지출 상한은 미정. 무료 또는 전액 지원으로 추측하지 않음",
                ),
                desired_start="2026-10-01", desired_end="2026-12-31",
                max_self_payment_won=None, owner="기술책임자",
            ),
        ),
        staff_capacity=(
            DemoStaffCapacity("기술책임자", 70, 20, 10),
            DemoStaffCapacity("AI 개발자 1", 80, 10, 10),
            DemoStaffCapacity("AI 개발자 2", 70, 20, 10),
            DemoStaffCapacity("데이터 엔지니어", 80, 10, 10),
            DemoStaffCapacity("서비스 개발자", 70, 20, 10),
        ),
        participation_constraints=(
            "위 5명의 여력은 가상 기준일 현재 기존 고객·내부 개발 업무를 반영한 배정 초안이며 회사 전체 인력이 아님. 추가 검토·소규모 시험에 쓸 수 있는 참여율 합계는 80%, 상근 인원 환산 0.8명",
            "대규모 신규 구축 사업이나 장기 상주 인력 요구는 고객 업무 이관·추가 배정 승인 없이는 바로 수행 가능하다고 판단하지 않음. 고객 일정 종료 후 여력은 재확인 필요",
            "자원 지원의 신청·이용 비용으로 검토 가능한 현금은 총 2천만 원의 가상 한도이며 최종 지출 승인은 별도. GPU 상한 800만 원은 이 범위 안의 수요별 초안으로 다시 더하지 않음",
            "신규 발주 사업의 수행 원가·보증·선집행 자금은 공고별 산정 전. 특정 총사업비·정부지원 비율·현물 인정 금액을 고정하지 않음",
            "기존 회사 소재지는 서울. 지역 제한 공고에서 다른 지역 사업장이나 이전 계획이 있다고 가정하지 않음. 제조 AI 공급기업을 제조공장 보유 기업으로 판단하지 않음",
            "공고별 신청 주체·공급기업/수요기업 역할·업종·실적·인증·부담금·사용 목적 제한을 확인. 민간 유상 업무에 지원 자원을 활용할 수 있는지도 공고와 고객 계약에 따라 확인",
        ),
        documents=(
            DemoDocument("사업자등록증", "AVAILABLE", "가상 전자 사본 보유. 공고의 업종·소재지·신청 주체 조건과 대조 필요", owner="경영지원"),
            DemoDocument("중소기업확인서", "AVAILABLE", "가상 기준일 유효한 증빙 보유. 해당 공고가 요구하는 기준일과 유효성 확인", "2027-03-31", "경영지원", "2027-03-15"),
            DemoDocument("최근 결산 재무제표", "AVAILABLE", "2025년 결산 가상 사본 보유. 공고별 재무 요건 충족 여부는 미확인", owner="재무 담당"),
            DemoDocument("국세·지방세 납세증명서", "UNKNOWN", "현재 유효 발급본 보유 여부 미확인. 체납 또는 자격 미충족을 뜻하지 않음", owner="재무 담당", target_date="2026-09-18"),
            DemoDocument("유사 프로젝트 실적증명서", "UNKNOWN", "고객 계약별 검수·완료 여부와 실적증빙 발급 가능성을 확인해야 함. 진행 중 작업은 완료 실적으로 간주하지 않음", owner="사업개발", target_date="2026-09-18"),
            DemoDocument("공공 발주 참여 등록·인증", "UNKNOWN", "공고가 요구하는 입찰 등록·직접생산·보안 등 증빙의 적용 여부와 보유 상태를 확인할 목록만 존재", owner="경영지원", target_date="2026-09-18"),
            DemoDocument("GPU 작업량·이용 계획서", "MISSING", "두 팀의 작업·메모리·실행시간을 측정한 이용 계획서 미작성. 관찰된 대기만으로 GPU 최소 사양을 확정하지 않음", owner="AI 개발 책임자", target_date="2026-09-15"),
            DemoDocument("외부 연산 환경 데이터 사용 승인", "MISSING", "고객 데이터의 외부 전송·학습 승인 문서 없음. 공개·합성·자체 시험 자료도 선택한 서비스의 이용 조건과 보안 확인 필요", owner="보안 담당·고객 담당 PM", target_date="2026-09-18"),
            DemoDocument("실증·기업 매칭용 회사 소개 자료", "AVAILABLE", "가상 일반 역량 소개 초안 보유. 공고 수요와 연결되는 사례를 선정하고 고객명·화면 공개 허락은 별도 확인", owner="사업개발"),
            DemoDocument("신규 실증 참여확인서", "MISSING", "아직 특정 공고·수요기업을 정하지 않아 서명본 없음. 공고가 요구할 때 파트너 협의 필요", owner="사업개발", target_date="2026-09-18"),
        ),
        reusable_assets=(
            "자체 시험용 데이터 연결 모듈·분석 화면 구성: 타 고객·공공 사업 적용 후보. 코드별 소유권·라이선스·납품 계약 범위 확인 전에는 전부 자유 재사용 가능하다고 판단하지 않음",
            "반도체 고객 로그·디스플레이 검사 이미지·철강 운전조건: 고객별 승인 범위 안에서만 사용. 기존 고객 관계가 공공 지원 신청서 공개·타 고객 실증·외부 AI 학습 동의를 뜻하지 않음",
            "공개·합성·자체 시험 자료: 외부 GPU의 1차 이용 후보. 권리·서비스 약관·실험 목적이 맞는지 확인한 뒤 사용",
            "공공 발주·매칭에 활용할 실적: 공고 요구와 실제 수행 범위를 연결하고 계약서·검수서·고객 확인서로 입증 가능한 범위를 확인. 추정 개선율이나 미공개 고객 정보를 만들지 않음",
        ),
        support_history=(
            "정부 R&D·GPU·클라우드·바우처·시험지원 수혜 이력은 미확인. 민간 고객 계약에서 지원금 수령을 추정하지 않음",
            "신규 공고가 중복 수혜나 같은 비용 지원을 제한하면 기존 지원 내역과 비용별 사용 현황을 확인. 이력 누락을 미수혜 또는 중복수혜로 단정하지 않음",
        ),
        notice_evaluation_criteria=(
            "판단 출발점은 모니터링한 공공기관 공고의 실제 내용. 공고에서 확인된 수요·대상·모집 상태·기한·비용·신청 절차에 회사 상황을 연결",
            "공공 발주·용역: 우리가 공급할 업무, 유사 수행 근거, 요구 자격·실적, 수행 기간·인력과 대조. GPU 구매·임차 입찰의 납품업체 모집을 우리가 GPU를 지원받는 공고로 해석하지 않음",
            "기업 매칭·실증: 공공기관이 실제 제공하는 수요기업 소개·상담·실증 연결 경로를 확인하고 공급기업으로 제안할 내용과 준비 자료를 도출. 모집에 없는 잠재 고객명·연락처·구매 의사는 생성하지 않음",
            "자원·지원: 현재 부족한 자원과 사용 목적에 실익이 있는지, 기존 자원으로 해결 가능한지, 지원 사양·기간·방식·자부담·보안·사용 목적 제한이 맞는지 판단. GPU 장비를 보유한 기관 소개만으로 신청 가능한 지원을 만들지 않음",
            "수혜기업 모집인지 공급기업 모집인지 원문에서 확인. 제조기업용 지원에 제조 소프트웨어 기업이 신청할 수 있는지 별도 확인. 보도자료·선정 결과·행사를 진행 중 신청 공고로 표시하지 않음",
            "실행 정보: 원문에 있는 신청 경로·기한·제출 서류를 회사의 보유 자료·담당 역할과 대조. 없는 절차·서류·기관 답변을 만들어 채우지 않고 확인할 질문으로 구분",
            "결과는 공고 근거, 현재 업무 또는 필요 자원과의 연결, 기대 실익과 제약, 참여에 필요한 확인 사항, 다음 행동 순서로 구체화. 기술명 일치만으로 필요성이 높거나 신청 가능하다고 단정하지 않음",
            "관계가 약한 공고·기간이 맞지 않는 지원·이미 충분한 자원·충족할 수 없는 조건에는 우선순위를 낮추거나 제외 이유를 설명. 모든 공고에 신규 R&D 과제와 예산을 만들어 붙이지 않음",
        ),
        open_questions=(
            "GPU 실험별 메모리·시간·동시 실행 수요를 측정하면 기존 1대의 예약 조정으로도 해결 가능한가",
            "모니터링한 지원은 GPU를 이용할 기업을 모집하는가, 장비를 공급할 업체를 모집하는가",
            "지원 사용기간·시작 시점·작업량·자부담이 현재 개발 수요와 맞는가",
            "고객 데이터 없이 가능한 실험 범위는 어디까지이며 외부 연산 승인에 필요한 조건은 무엇인가",
            "발주·매칭 공고의 수요에 제안할 업무와 증빙 가능한 고객 실적은 무엇인가",
            "필수 파트너·실적·인증·현장 상주 조건을 현재 인력과 자료로 충족할 수 있는가",
            "원문에 있는 신청 방법·담당 창구·기한을 기준으로 누구에게 어떤 확인·준비를 맡길 것인가",
        ),
    ),
)
