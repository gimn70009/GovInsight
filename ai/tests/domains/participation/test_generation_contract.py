import pytest
from openai import pydantic_function_tool
from pydantic import ValidationError

from app.domains.participation import focused
from app.domains.participation.schemas import Criterion, RewriteRequest, Source


def request():
    return RewriteRequest(
        sources=[Source(id="s1", group_label="신청자격", excerpt="대학 및 연구기관")],
        criteria=[Criterion(code="BUSINESS_FORM", label="주체", category="자격")],
    )


@pytest.mark.parametrize("missing", [None, ""])
def test_required_main_text_cannot_be_null_or_empty_on_the_wire(missing):
    fields = dict(source_id="s1", criterion="BUSINESS_FORM", kind="ELIGIBILITY", quote="L1")
    with pytest.raises(ValidationError):
        focused.ExtractedCondition(**fields, text=missing)
    with pytest.raises(ValidationError):
        focused.ExtractedCondition(**fields)
    schema = pydantic_function_tool(focused.Extraction)["function"]["parameters"]
    text = schema["$defs"]["ExtractedCondition"]["properties"]["text"]
    assert text["type"] == "string" and text["minLength"] == 1


def test_empty_eligibility_result_is_not_reported_as_success():
    req = request()
    result = focused.project(
        focused.Extraction(items=[], unresolved_source_ids=[]), req.sources, req
    )
    assert result.status == "UNAVAILABLE"
    assert result.statements == [] and result.evidence_only_ids == ["s1"]
    assert result.diagnostics == {"candidates": 0, "accepted": 0, "unresolved": 1,
                                  "REQUIREMENT_UNCOVERED": 1}


def test_rejection_counts_survive_without_storing_provider_text():
    req = request()
    item = focused.ExtractedCondition(
        source_id="recruitments",
        criterion="BUSINESS_FORM",
        kind="ELIGIBILITY",
        quote="L1",
        text="지원할 수 있습니다.",
    )
    result = focused.project(
        focused.Extraction(items=[item], unresolved_source_ids=[]), req.sources, req
    )
    assert result.status == "UNAVAILABLE"
    assert result.diagnostics["SOURCE_NOT_SELECTED"] == 1
    assert result.diagnostics["candidates"] == 1
    assert "지원할 수" not in str(result.diagnostics)
