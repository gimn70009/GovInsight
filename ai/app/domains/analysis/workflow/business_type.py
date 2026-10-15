"""Validate classification evidence without an additional model or external request."""

import re

from app.domains.analysis.schemas.business_type import BusinessTypeAssessment
from app.domains.analysis.schemas.request import AnalysisDocumentRequest

BUSINESS_TYPE_INSTRUCTIONS = """
comparison_summary.business_type은 현재 공고의 대표 사업 유형입니다. 확인되지 않으면 null입니다.
다음 코드 중 실제 핵심 지원 활동 하나만 primary_type으로 선택합니다.
RND=연구개발(R&D), DEMONSTRATION=실증·시범, COMMERCIALIZATION=제품화·사업화,
ADOPTION=설비·시스템 도입, CERTIFICATION=시험·인증, MARKET=판로·해외진출,
FINANCE=자금·금융, WORKFORCE=인력·고용·교육, ADVISORY=조사·컨설팅·전문지원, SPACE=공간·입주.
- 제목·기관·업종·회사 프로필만으로 분류하지 않습니다.
  현재 모집의 사업 목적과 지원 내용·과업을 확인합니다.
- 수출기업의 인증 비용 지원은 CERTIFICATION, 설비 도입 보조금은 ADOPTION입니다.
  사업비·보조금·바우처가 있다는 이유만으로 FINANCE로 정하지 않습니다.
  FINANCE는 융자·보증·투자 등 자금 조달입니다.
- 창업기업 대상이라는 사실은 유형이 아닙니다. 실증 과제를 지원하면 DEMONSTRATION입니다.
  인증 보유 자격, 연구개발 실적, 과거 지원, 향후 연계, 타 사업·작성 예시는 지원 내용이 아닙니다.
- 수행·공급·운영기관 모집은 그 기관의 실제 과업을 기준으로 합니다.
  explanation에 모집 역할을 밝힙니다.
- 복합 사업은 공고가 명시한 주된 목적·대표 과업을 우선합니다.
  동등하면 지원 항목 순서로 하나를 택합니다.
  나머지 실제 지원 활동이나 유형1·유형2의 차이는 details에 최대 5개 짧은 문장으로 설명합니다.
- explanation은 '어떤 일을 지원하는 사업인지' 쉬운 말로 1~2문장 설명합니다.
  코드·유형명·일반적인 사전 정의만 반복하거나 장문의 원문을 복사하지 않습니다.
- sources에는 그 설명을 뒷받침하는 현재 원문 발췌 1~4개를 그대로 넣습니다.
  본문은 origin=NOTICE_BODY, attachment_name=null로 씁니다.
  첨부는 origin=ATTACHMENT와 정확한 파일명을 씁니다.
  이전 버전, 검색결과, 요약, 생략 표시, 신청 양식의 작성 예시는 근거로 쓰지 않습니다.
- 선정 결과·일반 안내·직원 채용·설명회 공지만 있거나
  이 10개 유형에 해당하는 활동이 확인되지 않으면 null입니다.
  일반 공지임이 확인된 경우 proposal.document_type=GENERAL_NOTICE로 그 판단을 보존합니다.
  유형이 null이더라도 실제 사업 모집이면 해당 사업 문서 분류를 유지합니다.
"""

_FORM = re.compile(
    r"(?m)^[ \t\[<(【]*(?:붙임|별첨|별지|서식)\s*(?:제\s*)?\d"
    r"|^[ \t\[<(【]*작성\s*예시"
)
_NON_NOTICE = re.compile(r"예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|협약서", re.I)
_UNSUPPORTED = re.compile(
    r"전년도|지난해|타\s*사업|다른\s*사업|작성\s*예시|지원\s*(?:제외|없음|불가|하지)"
    r"|미지원|후속|향후|추후|연계\s*가능|참고용|일부 원문 생략"
)


def _notice_part(text: str) -> str:
    text = text[:200_000]
    members = list(re.finditer(r"(?m)^\[파일: (.+?)\]\s*$", text))
    if members:
        selected = []
        for index, member in enumerate(members):
            name = member.group(1)
            if _NON_NOTICE.search(name) and not re.search(r"공고|모집|안내", name):
                continue
            end = members[index + 1].start() if index + 1 < len(members) else len(text)
            selected.append(member.group(0) + "\n" + _notice_part(text[member.end() : end]))
        return "\n".join(selected)
    marker = _FORM.search(text)
    return text[: marker.start()] if marker else text


def validated_business_type(
    assessment: BusinessTypeAssessment | None,
    document: AnalysisDocumentRequest,
) -> BusinessTypeAssessment | None:
    if assessment is None:
        return None
    current = [("NOTICE_BODY", None, _notice_part(document.content_text or ""))]
    current.extend(
        ("ATTACHMENT", file.file_name, _notice_part(file.extracted_text or ""))
        for file in document.attachments
        if not _NON_NOTICE.search(file.file_name) or re.search(r"공고|모집|안내", file.file_name)
    )
    for source in assessment.sources:
        matches = [
            text
            for origin, name, text in current
            if origin == source.origin and name == source.attachment_name
        ]
        excerpt = re.sub(r"\s+", "", source.excerpt)
        if (
            len(matches) != 1
            or len(excerpt) < 5
            or _UNSUPPORTED.search(source.excerpt)
            or excerpt not in re.sub(r"\s+", "", matches[0])
        ):
            return None
    return assessment
