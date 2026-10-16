"""Validate classification evidence without an additional model or external request."""

import logging
import re
from dataclasses import dataclass

from app.domains.analysis.schemas.business_type import BusinessTypeAssessment, BusinessTypeReview
from app.domains.analysis.schemas.request import AnalysisDocumentRequest

logger = logging.getLogger(__name__)

VALIDATOR_VERSION = "business-type-v6-contest-support"

BUSINESS_TYPE_INSTRUCTIONS = """
comparison_summary.business_type은 현재 공고의 대표 사업 유형입니다. 확인되지 않으면 null입니다.
다음 코드 중 실제 핵심 지원 활동 하나만 primary_type으로 선택합니다.
RND=연구개발(R&D), DEMONSTRATION=실증·시범, COMMERCIALIZATION=제품화·사업화,
ADOPTION=설비·시스템 도입, CERTIFICATION=시험·인증, MARKET=판로·해외진출,
FINANCE=자금·금융, WORKFORCE=인력·고용·교육, ADVISORY=조사·컨설팅·전문지원, SPACE=공간·입주.
- 공고가 지금 무엇을 요청하는지 먼저 구분합니다. 선정 결과 발표, 규정 개정·고시,
  포상·표창 후보 모집은 신규 지원사업 모집과 다릅니다.
  지정·인증 등의 신청이 이미 접수되어 이해관계인의 의견서를 받는 공고는 일반 공지입니다.
  제목의 신청이라는 단어와 의견 제출 기간을 신규 지정 신청 모집으로 읽지 않습니다.
  실제 신규 신청·참여기업 모집이 함께 있으면 해당 모집은 별도로 유지합니다.
  규정 속 신청서와 예시를 현재 모집으로 읽지 않습니다.
  지정·선정 모집도 현재 지원 활동과 구별하며
  별도 심사나 향후 연계 혜택을 확정 지원으로 바꾸지 않습니다.
- 공모·입찰이라는 단어가 있어도 우선협상 대상자 등의 선정 결과를 공고하는 글은 결과 안내입니다.
  선정 공고라는 제목만으로 확정하지 않고 본문의 현재 결과 발표를 확인합니다.
  실제 추가 모집·신청 요청이 함께 있으면 해당 모집을 보존합니다.
- 제안요청서의 목차에 적힌 신청서·서식 목록은 실제 서식 본문과 구별합니다.
  사업 예시 표와 작성 예시는 현재 확정된 과업·지원 항목의 근거로 쓰지 않습니다.
  정부인증 서비스·인증기관이라는 수식어만으로 CERTIFICATION을 만들지 않습니다.
  상담사의 역량 강화 코칭·교육훈련 운영은 WORKFORCE이며 홈페이지 운영은 부대 과업입니다.
- 참가자가 아이디어·성과물·시제품을 출품하고 심사·시상하는 공모전·경진대회는
  지원 유형과 별개의 공고 성격입니다. 제목만으로 판단하지 않고 현재 대회 개최와
  상금·시상 내용을 확인합니다. 공모전 운영기관 모집이나 수상기업 대상 후속 지원은
  해당 사업의 실제 과업·지원 활동으로 분류합니다.
- 지원 규모 표의 감축설비 투자비 보조는 ADOPTION이며, 투자라는 말만으로 FINANCE가 아닙니다.
  해외 진출이라는 목적보다 현재 제공하는 설비·조사 등 구체적인 지원 항목을 우선합니다.
  수요조사라는 제목이어도 선정기업에 수출기회·수출상담회를 제공하면 MARKET입니다.
  접수 기간에 나열된 사업명은 활동 근거로 쓰지 않습니다.
- 개요·목적·지원 내용을 문단 단위로 함께 읽습니다.
  제도에 시범적으로 참여하는 활동은 DEMONSTRATION입니다.
  결과 안내의 사업명이나 설비 도입 가능성만으로 ADOPTION을 만들지 않습니다.
  인용이 원문에 있다는 사실만으로 충분하지 않습니다.
  선택 유형의 실제 지원 활동을 입증하는 근거가 필요합니다.
- 제목·기관·업종·회사 프로필만으로 분류하지 않습니다.
  현재 모집의 사업 목적과 지원 내용·과업을 확인합니다.
- 수출기업의 인증 비용 지원은 CERTIFICATION, 설비 도입 보조금은 ADOPTION입니다.
  사업비·보조금·바우처가 있다는 이유만으로 FINANCE로 정하지 않습니다.
  FINANCE는 융자·보증·투자 등 자금 조달입니다.
- 창업기업 대상이라는 사실은 유형이 아닙니다. 실증 과제를 지원하면 DEMONSTRATION입니다.
  인증 보유 자격, 연구개발 실적, 과거 지원, 향후 연계, 타 사업·작성 예시는 지원 내용이 아닙니다.
- 수행·공급·운영기관 모집은 그 기관의 실제 과업을 기준으로 합니다.
  제안요청서의 과업 범위·주요 내용을 확인하고 explanation에 모집 역할을 밝힙니다.
  조사·현황 분석·적용방안 검토가 주된 연구용역은 ADVISORY이며,
  제목에 연구가 있다는 이유만으로 기술개발을 뜻하는 RND로 정하지 않습니다.
  생산설비의 설치·교체를 지원하는 사업은 ADOPTION입니다.
  공모전 입상작·수상자·당선작 발표는 신규 공모가 아닌 결과 안내입니다.
- 복합 사업은 공고가 명시한 주된 목적·대표 과업을 우선합니다.
  동등하면 지원 항목 순서로 하나를 택합니다.
  나머지 실제 지원 활동이나 유형1·유형2의 차이는 details에 최대 5개 짧은 문장으로 설명합니다.
- explanation은 '어떤 일을 지원하는 사업인지' 쉬운 말로 1~2문장 설명합니다.
  코드·유형명·일반적인 사전 정의만 반복하거나 장문의 원문을 복사하지 않습니다.
- sources에는 그 설명을 뒷받침하는 현재 원문 발췌 1~4개를 그대로 넣습니다.
  본문은 origin=NOTICE_BODY, attachment_name=null로 씁니다.
  첨부는 origin=ATTACHMENT와 정확한 파일명을 씁니다.
  서로 떨어진 문장을 하나의 발췌로 합치거나 요약·말바꾸기하지 않습니다.
  활동이 확인되는 짧고 연속된 원문 구간을 고르고 문장부호도 그대로 보존합니다.
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


def _is_notice_heading(line: str) -> bool:
    # A numbered attachment can be the actual notice, not an application form.
    return bool(re.search(r"공고|모집\s*안내|사업\s*안내", line)) and not bool(
        _NON_NOTICE.search(line) or re.search(r"작성\s*예시", line)
    )


def _without_reference_sections(text: str) -> str:
    contents = re.search(r"(?m)^[ \t【\[(]*목[ \t]*차[ \t】\])]*$", text)
    if contents:
        body = re.search(
            r"(?m)^[ \t]*(?:[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[. \t]+|[1-9]\d?(?:\.[ \t]*|[ \t]+))"
            r"(?:사업|과업|연구|지원)[ \t]*(?:개요|목적|내용|범위|목표|안내)[ \t]*$",
            text[contents.end():],
        )
        if body and body.start() <= 10_000 and len(re.findall(
            r"(?m)^.{1,120}\d+[ \t]*$", text[contents.end():contents.end() + body.start()],
        )) >= 3:
            text = (
                text[:contents.start()] + "\n[참고 구간 제외]\n"
                + text[contents.end() + body.start():]
            )
    example = re.compile(
        r"(?m)^(?:[^\r\n]{0,80}[（(]예시[）)]"
        r"|[<〈《【\[][^\r\n]{0,80}예시[ \t]*[>〉》】\]])[ \t]*$",
    )
    boundary = re.compile(r"(?m)^[ \t]*(?:[□■]|[0-9]+[.)][ \t]*|\[파일:)")
    parts, cursor = [], 0
    for match in example.finditer(text):
        if match.start() < cursor:
            continue
        end = boundary.search(text, match.end())
        parts.extend((text[cursor:match.start()], "\n[참고 구간 제외]\n"))
        cursor = end.start() if end else len(text)
    return "".join(parts) + text[cursor:]


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
    text = _without_reference_sections(text)
    for marker in _FORM.finditer(text):
        line_end = text.find("\n", marker.start())
        line = text[marker.start() : line_end if line_end >= 0 else len(text)]
        if not _is_notice_heading(line):
            return text[: marker.start()]
    return text


_ACTIVITY = {
    "RND": r"R&D|연구개발(?!기업|인력|실적)|기술개발|공동연구",
    "DEMONSTRATION": r"실증|테스트베드|PoC|시범(?:사업|운영|적용|적으로참여)|현장검증",
    "COMMERCIALIZATION": r"시제품|제품화|사업화|양산|제품고도화|제품디자인",
    "ADOPTION": (
        r"인프라구축|시스템(?:을)?구축|시스템(?:을)?도입|솔루션도입|(?:설비|시설)(?:를|을|의)?(?:도입|구축|개선|교체|구매|설치|지원|투자비)"
        r"|장비도입|장비구축|시설개선|환경개선|환경조성|스마트공장|공정개선"
    ),
    "CERTIFICATION": (
        r"인증(?:을|의)?(?:취득|획득|비용|수수료|시험|지원)|(?<!정부)인증(?!기업|기관|업체|보유|실적)|시험평가|시험비"
        r"|제3자검증|배출량검증|성능시험|규격시험"
    ),
    "MARKET": (
        r"판로|마케팅|해외진출|수출(?:지원|마케팅|상담|바우처|판로|촉진|기회)"
        r"|전시회|박람회|구매상담|시장진출"
    ),
    "FINANCE": r"융자|이차보전|이자지원|신용보증|기술보증|보증지원|직접투자|투자유치|정책자금",
    "WORKFORCE": (
        r"채용지원|고용지원|고용장려|인건비지원"
        r"|인력양성|인재양성|교육지원|직무교육|훈련지원|교육훈련|훈련과정|역량강화코칭"
    ),
    "ADVISORY": (
        r"컨설팅|정책연구|조사용역|시장조사|기술지도|(?:기초자료|현황|실태|사례|타당성)조사|현황분석|(?:기본구상|기본계획|개선방안)(?:안)?(?:수립|검토)"
        r"|전문자문|법률지원|회계지원|(?:법률|노무|회계)상담|고충상담|특허(?!보유)|지식재산|출원"
    ),
    "SPACE": r"입주|사무공간|보육공간|공간지원|공간제공|연구공간",
}


def _supports_activity(code: str, text: str) -> bool:
    value = re.sub(r"\s+", "", text)
    if not re.search(_ACTIVITY[code], value, re.I) or _UNSUPPORTED.search(text):
        return False
    if re.search(
        r"지원대상|신청자격|보유자격|접수마감|접수기간|제출기한|접수기한|제출마감|신청마감", value
    ) and not re.search(
        r"(?:비용|취득비|제작비|개발비|구축비)(?:를|을)?지원|지원합니다|제공합니다|수행합니다",
        value,
    ):
        return False
    return bool(re.search(r"지원|제공|수행|구축|도입|모집|융자|입주|개발|실증|컨설팅", value)) or (
        code == "DEMONSTRATION" and bool(re.search(r"시범.{0,80}(?:참여|적용|허용|유지)", value))
    ) or (
        code == "ADOPTION" and bool(re.search(
            r"설치|교체|개선|구매|(?:설비|시설)(?:의)?투자비", value,
        ))
    ) or (
        code == "WORKFORCE" and bool(re.search(r"코칭|교육|훈련", value))
    ) or (
        code == "ADVISORY" and bool(re.search(r"조사|분석|검토|수립", value))
    )


def _filed_application_opinion(heading: str, sources: list[str]) -> bool:
    if not re.search(r"(?:지정|인증|허가|승인).{0,16}신청", heading) or re.search(
        r"모집|공모|접수|입찰|(?:신규|추가)신청", heading
    ):
        return False
    # Do not suppress new applications merely because the same paragraph mentions opinions.
    new_application = (
        r"(?:모집|공모)(?:합니다|한다|하고자|하오니|공고|안내|대상|기간)"
        r"|(?:지정|인증|허가|승인|지원|참여)신청(?:서)?(?:을|를)?(?:받|접수)"
        r"|(?:제안서|사업계획서|(?<!이의)(?<!의견)신청서)(?:를|을)?(?:작성하여)?제출"
        r"|(?<!의견)(?<!이의)신청(?:기간|기한|방법|대상|하세요|하시기|바랍니다)"
    )
    if any(re.search(new_application, re.sub(r"\s+", "", text)) for text in sources):
        return False
    introductions = [re.sub(r"\s+", "", text[:6000]) for text in sources]
    return any(
        not re.search(
            r"전년도|지난해|과거|예시|견본|참고자료|모집하지않|공모하지않"
            r"|(?:신청|접수|제출).{0,25}(?:받지않|요구하지않|필요없|필요하지않|없습니다)"
            r"|(?:모집|접수|신청).{0,20}(?:종료되|마감되|완료되|마감하였|종료하였)",
            text,
        )
        and re.search(r"(?:지정|인증|허가|승인)신청이(?:있어|있으므로|접수되어|접수되었)", text)
        and re.search(
            r"(?:이해관계인|이해관계자|주민).{0,220}(?:의견서?|이의신청).{0,160}(?:제출|접수)",
            text,
        )
        for text in introductions
    )


def _contest_notice(title: str, sources: list[str]) -> bool:
    title_pattern = (
        r"(?:공모전|경진대회|챌린지)(?:참가자|참가팀|참여자|참여팀)?"
        r"(?:모집|접수|공고|개최|안내)(?:수정|연장|추가)?(?:공고)?$"
    )
    action = (
        r"(?:공모전|경진대회|대회)(?:를|을)?(?:(?:다음|아래)과같이)?"
        r"(?:개최합니다|개최하오니|개최하고자|공고하오니|진행합니다)"
    )
    prize = r"시상(?:내역|계획|규모)|(?:상금|장관상|상품)(?:을|은|의|과|\(|형태|지급)|상금$"
    if not re.search(title_pattern, title):
        return False
    for text in sources:
        introduction = text[:6000]
        sentences = (
            re.sub(r"\s+", "", sentence)
            for sentence in re.split(r"[。!?]|(?<=\.)\s+|\n\s*\n", introduction)
        )
        current = any(
            re.search(action, sentence)
            and not re.search(r"전년도|지난해|과거|예시|견본|참고자료|개최하지않", sentence)
            for sentence in sentences
        )
        if current and re.search(prize, re.sub(r"\s+", "", introduction)):
            return True
    return False


def _notice_kind(title: str, sources: list[str]) -> str | None:
    heading = re.sub(r"\s+", "", title)
    if _contest_notice(heading, sources):
        return "CONTEST"
    if _filed_application_opinion(heading, sources):
        return "GENERAL"
    introductions = [re.sub(r"\s+", "", text[:6000]) for text in sources]
    current_title = bool(re.search(r"추가모집|재공모|신규모집", heading)) or (
        bool(re.search(r"모집|공모|신청|접수|입찰", heading))
        and not re.search(
            r"결과|종료|마감|취소|(?:입상작|입상자|수상작|수상자|당선작|당선자)(?:발표|공고|명단)"
            r"|(?:의견|이의)(?:서)?(?:제출|신청|접수)",
            heading,
        )
    )
    if (
        re.search(
            r"(?:포상|표창)(?:후보자|대상자|유공자)?(?:모집|신청|추천|안내|공고|계획|추가|수정|연장)",
            heading,
        )
        and not re.search(
            r"결과|종료|마감|취소|(?:입상작|입상자|수상작|수상자|당선작|당선자)(?:발표|공고|명단)",
            heading,
        )
        and any(
            re.search(r"장관표창|포상(?:대상|목적|계획|훈격|규모)|훈격", text)
            for text in introductions
        )
    ):
        return "AWARD"
    if re.search(r"선정계획|지정.{0,20}(?:모집|공모|신청)", heading) and any(
        re.search(
            r"지정되더라도.{0,25}지원.{0,25}(?:확정되는것은아님|확정되지않)"
            r"|지정신청(?:서)?(?:을|를)?(?:받습니다|접수합니다)|지정을희망하는.{0,20}(?:신청|제출)",
            text,
        )
        for text in introductions
    ):
        return "DESIGNATION_RECRUITMENT"
    affirmative = [
        text for text in introductions if not re.search(r"전년도|지난해|과거|작성예시", text)
    ]
    if (
        not current_title
        and not re.search(r"행정예고|입법예고", heading)
        and re.search(r"규정|규칙|지침|고시", heading)
        and any(
            re.search(
                r"(?:규정|규칙|지침|고시).{0,40}(?:제정|개정)하여고시|(?:제정|개정)(?:하여)?고시합니다",
                text,
            )
            for text in affirmative
        )
    ):
        return "REGULATION"
    recruiting = any(
        re.search(
            r"(?:모집|공모)(?:합니다|한다|하고자|하오니|대상|기간)|(?:신청|접수)(?:기간|기한|방법|대상|하세요|바랍니다)"
            r"|신청(?:을)?받(?:습니다|고있)",
            text,
        )
        for text in affirmative
    )
    selected = (
        bool(re.search(r"(?:선정|선발|확정)(?:결과)?(?:공고|공개|알림|안내)$", heading))
        and not re.search(r"추가모집|재공모|신규모집", heading)
        and any(re.search(
            r"(?:선정|선발|확정)결과(?:를|을).{0,25}"
            r"(?:공고합니다|공고하였습니다|공개합니다|발표합니다|알려드립니다)", text,
        ) for text in affirmative)
    )
    if (
        (not current_title or selected)
        and not recruiting
        and (
            re.search(r"(?:선정|평가|심사|합격|모집|공모).{0,10}결과", heading)
            or any(
                re.search(
                    r"(?:선정|평가|심사|합격).{0,12}결과(?:를|을).{0,25}(?:공고|알립|안내|발표|통보)"
                    r"|(?:입상작|입상자|수상작|수상자|당선작|당선자)(?:을|를|은|는)?(?:다음|아래|붙임)?(?:(?:과|와)같이)?(?:발표합니다|공고합니다|알립니다|발표하였습니다|선정하였습니다)",
                    text,
                )
                for text in affirmative
            )
        )
    ):
        return "RESULT"
    return None


@dataclass(frozen=True)
class BusinessTypeValidation:
    assessment: BusinessTypeAssessment | None
    reason: str
    source_index: int | None = None
    notice_kind: str | None = None


@dataclass(frozen=True)
class _Source:
    origin: str
    name: str | None
    raw: str
    text: str
    eligible: bool = True


def _sources(document: AnalysisDocumentRequest) -> list[_Source]:
    body = document.content_text or ""
    result = [_Source("NOTICE_BODY", None, body, _notice_part(body))]
    for file in document.attachments:
        raw = file.extracted_text or ""
        eligible = not _NON_NOTICE.search(file.file_name) or bool(
            re.search(r"공고|모집|안내", file.file_name)
        )
        result.append(_Source("ATTACHMENT", file.file_name, raw, _notice_part(raw), eligible))
    return result


def diagnose_business_type(
    assessment: BusinessTypeAssessment | None,
    document: AnalysisDocumentRequest,
) -> BusinessTypeValidation:
    current = _sources(document)
    nature = _notice_kind(document.title, [source.text for source in current if source.eligible])
    if assessment is None and nature:
        return BusinessTypeValidation(None, "NOTICE_" + nature, notice_kind=nature)
    if assessment is None:
        reason = (
            "MODEL_UNCLASSIFIED"
            if any(source.eligible and source.text.strip() for source in current)
            else "NO_USABLE_SOURCE"
        )
        return BusinessTypeValidation(None, reason)
    for index, source in enumerate(assessment.sources):
        matches = [
            item
            for item in current
            if item.origin == source.origin and item.name == source.attachment_name
        ]
        reason = None
        excerpt = re.sub(r"\s+", "", source.excerpt)
        if not matches:
            reason = "SOURCE_NOT_FOUND"
        elif len(matches) != 1:
            reason = "AMBIGUOUS_SOURCE"
        elif not matches[0].eligible:
            reason = "EXCLUDED_SOURCE"
        elif not matches[0].raw.strip():
            reason = "SOURCE_TEXT_UNAVAILABLE"
        elif len(excerpt) < 5:
            reason = "QUOTE_TOO_SHORT"
        elif _UNSUPPORTED.search(source.excerpt):
            reason = "UNSUPPORTED_EVIDENCE"
        elif excerpt not in re.sub(r"\s+", "", matches[0].text):
            reason = (
                "EXCLUDED_SOURCE_SECTION"
                if excerpt in re.sub(r"\s+", "", matches[0].raw)
                else "QUOTE_MISMATCH"
            )
        if not reason and excerpt not in re.sub(r"\s+", "", matches[0].raw):
            reason = "QUOTE_MISMATCH"
        if reason:
            return BusinessTypeValidation(None, reason, index)
    if nature:
        return BusinessTypeValidation(None, "NOTICE_" + nature, notice_kind=nature)
    if re.search(
        r"수반될가능성|가능성이있어|가능성을시사|유형으로추정",
        re.sub(r"\s+", "", assessment.explanation),
    ):
        return BusinessTypeValidation(None, "SPECULATIVE_CLASSIFICATION")
    if not any(
        _supports_activity(assessment.primary_type, source.excerpt) for source in assessment.sources
    ):
        return BusinessTypeValidation(None, "TYPE_ACTIVITY_MISMATCH")
    return BusinessTypeValidation(assessment, "VALIDATED")


def reviewed_business_type(
    assessment: BusinessTypeAssessment | None,
    document: AnalysisDocumentRequest,
) -> tuple[BusinessTypeAssessment | None, BusinessTypeReview]:
    result = diagnose_business_type(assessment, document)
    logger.info(
        "Business type validation. detection_id=%s version_id=%s reason=%s "
        "type_code=%s source_index=%s attachment_count=%s empty_attachment_count=%s",
        document.detection_id,
        document.version_id,
        result.reason,
        assessment.primary_type.value if assessment else None,
        result.source_index,
        len(document.attachments),
        sum(not (file.extracted_text or "").strip() for file in document.attachments),
    )
    return result.assessment, BusinessTypeReview(
        validator_version=VALIDATOR_VERSION,
        candidate_type=assessment.primary_type if assessment else None,
        reason=result.reason,
        source_index=result.source_index,
        notice_kind=result.notice_kind,
    )


def validated_business_type(
    assessment: BusinessTypeAssessment | None,
    document: AnalysisDocumentRequest,
) -> BusinessTypeAssessment | None:
    return reviewed_business_type(assessment, document)[0]
