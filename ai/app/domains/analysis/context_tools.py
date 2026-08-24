import json

from app.domains.analysis.company_profile import CompanyProfile
from app.domains.analysis.schemas.request import AnalysisDocumentRequest


class ContextToolProtocol:
    document: AnalysisDocumentRequest
    company_profile: CompanyProfile


def read_company_profile(context: ContextToolProtocol) -> str:
    profile = context.company_profile
    return json.dumps(
        {
            "companyName": profile.company_name,
            "description": profile.description,
            "businessAreas": profile.business_areas,
            "services": profile.services,
            "technologies": profile.technologies,
            "targetIndustries": profile.target_industries,
            "relevantProjectTypes": profile.relevant_project_types,
            "location": profile.location,
            "unknownFields": profile.unknown_fields,
            "sourceUrl": profile.source_url,
        },
        ensure_ascii=False,
    )


def read_previous_analysis(context: ContextToolProtocol) -> str:
    previous = context.document.previous_analysis
    if previous is None:
        return json.dumps({"available": False}, ensure_ascii=False)
    return json.dumps(
        {"available": True, **previous.model_dump(by_alias=True)}, ensure_ascii=False
    )
