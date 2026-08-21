import asyncio
import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

from app.domains.analysis.config import AnalysisSettings
from app.domains.report.schemas.request import ReportJobRequest
from app.domains.report.schemas.result import ReportDraft

SYSTEM_PROMPT = """
당신은 기업 담당자가 공공기관의 신규·변경 공고를 빠르게 파악하도록 돕는
GovInsight 보고서 작성자입니다.
입력된 문서별 분석 결과만 근거로 사용하고 입력에 없는 사실을 만들지 마세요.
보고서 제목에는 주요 기관·게시판과 감지 문서의 성격을 짧게 나타내세요.
전체 요약에는 중요도가 높은 문서를 먼저 배치하고 대상, 기한, 지원·규제 내용과
대응 필요성을 정리하세요.
중복 표현을 줄이고 문서별 원문 URL을 임의로 변경하지 마세요.
내부 추론 과정은 출력하지 말고 요청된 구조화 결과만 반환하세요.
""".strip()


class ReportRunner(Protocol):
    async def generate(self, request: ReportJobRequest) -> ReportDraft: ...


class LangChainReportRunner:
    def __init__(self, settings: AnalysisSettings) -> None:
        self._timeout_seconds = settings.timeout_seconds
        model = ChatOpenAI(
            model=settings.model_name,
            api_key=settings.api_key,
            timeout=settings.timeout_seconds,
            max_retries=0,
        )
        self._model = model.with_structured_output(ReportDraft)

    async def generate(self, request: ReportJobRequest) -> ReportDraft:
        payload = request.model_dump(mode="json", by_alias=True)
        prompt = (
            "다음 모니터링 실행의 문서별 분석 결과를 하나의 보고서로 작성하세요.\n"
            + json.dumps(payload, ensure_ascii=False)
        )
        async with asyncio.timeout(self._timeout_seconds):
            response = await self._model.ainvoke(
                [SystemMessage(content=SYSTEM_PROMPT), HumanMessage(content=prompt)]
            )
        return ReportDraft.model_validate(response)
