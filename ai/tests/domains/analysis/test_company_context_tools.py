import json

from app.domains.analysis.context_tools import read_company_profile, read_previous_analysis
from app.domains.analysis.schemas.request import AnalysisDocumentRequest
from app.domains.analysis.tools import AnalysisToolContext


def test_company_profile_and_previous_analysis_are_available() -> None:
    document = AnalysisDocumentRequest.model_validate(
        {
            "detectionId": 1,
            "documentId": 2,
            "versionId": 3,
            "changeType": "UPDATED_DOCUMENT",
            "organizationName": "산업통상부",
            "boardName": "사업공고",
            "title": "수정 공고",
            "contentText": "지원 조건이 변경되었습니다.",
            "originalUrl": "https://example.go.kr/3",
            "previousAnalysis": {
                "summary": "기존 지원사업 분석 결과입니다.",
                "keyPoints": "[\"기존 조건\"]",
                "eligibility": "REVIEW_REQUIRED",
                "favorableOrNot": "NOT_APPLICABLE",
                "proposalDirection": "기존 제조 AI 제안 방향입니다.",
            },
        }
    )
    context = AnalysisToolContext(document=document, max_text_chars=10_000)

    profile = json.loads(read_company_profile(context))
    previous = json.loads(read_previous_analysis(context))

    assert profile["companyName"] == "BISTelligence(비스텔리젼스)"
    assert "산업 AI" in profile["businessAreas"]
    assert any("중소기업" in fact for fact in profile["verifiedFacts"])
    assert any("반도체" in case for case in profile["caseStudies"])
    assert any("중소기업확인서" in field for field in profile["unknownFields"])
    assert any(
        url.startswith("https://www.jobplanet.co.kr/companies/397061")
        for url in profile["sourceUrls"]
    )
    assert previous["available"] is True
    assert previous["eligibility"] == "REVIEW_REQUIRED"
