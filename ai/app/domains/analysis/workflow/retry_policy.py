"""Timeout classification and evidence-preserving guidance for bounded retries."""

from httpx import TimeoutException
from openai import APITimeoutError

TIMEOUT_FEEDBACK = "AI 모델 응답 시간이 초과되었습니다."


def is_timeout_error(exception: BaseException) -> bool:
    """Recognize native, transport and SDK timeouts, including explicitly wrapped causes."""
    seen = set()
    current = exception
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, (TimeoutError, TimeoutException, APITimeoutError)):
            return True
        current = current.__cause__ or (
            None if current.__suppress_context__ else current.__context__
        )
    return False


def needs_compact_retry(feedback: str | None) -> bool:
    return bool(
        feedback
        and (
            TIMEOUT_FEEDBACK in feedback
            or "recursion limit" in feedback.casefold()
            or "tool call limit" in feedback.casefold()
        )
    )


COMPACT_RETRY_INSTRUCTIONS = """재시도 작성 기준: 필수 정보 보존과 중복 설명 축소
이전 호출이 제한에 도달해 입력 분량을 조정했습니다. 출력은 아래 기준으로 간결하게 작성합니다.
간결함보다 신청 판단에 필요한 정보의 완전성과 근거 정확도를 우선합니다.
- 기존 응답 스키마의 모든 필수 항목과 두 공고 해석 항목을 유지합니다.
자격·유불리·중요도·네 기회 점수와 각각의 판단 근거, 공고 분류·제안 상태·그 이유를 빠뜨리지 않습니다.
- 사업 목적·수행 범위, 지원 대상과 제외 대상, 필수 자격·파트너, 접수 기간과 정확한 마감 시각,
지원 금액·단위·비율·자부담·조건, 필수 제출 서류·평가 기준 중
원문에서 확인한 신청 판단 정보를 보존합니다.
해당 정보가 공고에 없거나 축소 입력에서 확인되지 않으면 이를 구분해 명시하고 추측하지 않습니다.
- comparison_summary의 purpose, support_scale, application_deadline,
eligibility, required_partner를 모두 작성합니다. 날짜 범위·연도·시간, 금액 단위·상한,
예외·부정·제한 조건을 간결하게 쓰는 과정에서 없애거나 바꾸지 않습니다.
- summary는 보통 300~600자, proposal.sections의 각 body는 보통 400~700자로
핵심 판단과 근거·회사 영향·한계를 충분히 설명합니다. 이는 권장 분량이며 정보가 복잡하면
기존 필드 상한 안에서 더 설명합니다. 단순한 공고는 억지로 늘리지 않습니다.
key_points는 기존 최대 8개 범위에서 핵심을 빠짐없이 담되 각 항목의 반복·부연을 줄입니다.
- 수정 공고의 두 번째 해석은 변경 전 → 변경 후 → 회사 영향 순서를 유지합니다.
확인된 이전·현재 조건을 구체적으로 비교하고, 중요한 변경이 여러 개이면
분량을 줄이기 위해 누락하지 않습니다.
접수 종료 사실과 비교 자료 부족·읽기 실패·길이 제한도 유지합니다.
- 회사의 실제 관련 역량과 자격 충족을 구분하고, 미확인 사항·조건부 판단·
확정 사실과 계획의 차이를 유지합니다.
확인된 사실을 단순히 '원문 확인 필요'로 대체하지 않습니다.
- 줄일 것은 항목 간 반복, 장황한 회사 소개, 수사적 표현과 일반론입니다.
항목별 설명은 핵심 판단부터 쓰고 근거와 영향을 연결합니다.
다른 필드에 정확한 수치가 있으면 부연을 반복하지 않되
판단에 중요한 조건은 찾을 수 있게 남깁니다.
- 모든 문장과 JSON을 완결합니다. 글자 수를 맞추려고 문장을 자르거나 필수 필드를 비우지 않습니다.
시간 절약을 이유로 검증을 생략하거나 근거 없는 값을 채우지 않습니다.
""".strip()
