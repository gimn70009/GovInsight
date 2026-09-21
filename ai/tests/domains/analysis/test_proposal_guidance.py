"""Regressions for precise guidance validation and bounded, scoped body repairs."""

import asyncio
import copy
import json
import logging
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.domains.analysis.proposal_guidance import (
    ProposalGuidanceError,
    guidance_issues,
    verify_proposal_guidance,
)
from app.domains.analysis.proposal_writer import (
    ProposalWriter,
    ProposalWriteRequest,
    validation_hint,
    verify_outline,
    verify_writing,
)
from tests.domains.analysis.test_proposal_section_contract import slots
from tests.domains.analysis.test_proposal_writer import (
    BODY,
    TEXT,
    outline_data,
    request,
    settings,
    writing,
)

PROFILE = {"service": "제조 현장의 데이터 통합과 AI 모델 운영 서비스를 제공합니다."}
PRIVATE_GUIDANCE = "비공개사업의 추진 내용을 작성하세요."


@pytest.fixture(autouse=True)
def propagate_application_logs(monkeypatch):
    # App startup disables propagation; restore it for caplog and undo after each test.
    monkeypatch.setattr(logging.getLogger("app"), "propagate", True)


@pytest.mark.parametrize("sentence", [
    "당사는 현장으로 복귀하는 작업자의 교육을 지원합니다.",
    "당사는 기준값으로 회귀하는 설비의 상태를 분석합니다.",
    "당사는 과제 수행 중 작성해야 하는 시험 보고서를 자동 생성합니다.",
    "당사는 협약에 따라 매월 점검 보고서를 작성해야 합니다.",
    "우리 회사는 점검 결과를 기재해야 합니다.",
    "[설비 A]의 상태를 확인하고 [설비 B]와 비교합니다.",
])
def test_legitimate_proposal_sentences_are_not_guidance(sentence):
    assert guidance_issues(sentence) == []
    verify_proposal_guidance(sentence)
    output = writing()
    output["sections"][0]["body"] += " " + sentence
    result = verify_writing(
        output, verify_outline(outline_data(), TEXT),
        ["service: 제조 AI 운영"], "양식", False,
    )
    assert result.status == "COMPLETED"
    assert sentence in result.sections[0].body


@pytest.mark.parametrize("sentence", [
    "사업 내용을 작성하세요.",
    "대표자 이름을 기재하세요.",
    "귀사는 내용을 작성해야 합니다.",
    "사업 내용을 작성해야 합니다.",
    "본 항목에는 당사가 보유한 기술을 작성해야 합니다.",
    "해당 작성란에 우리 회사가 수행할 사업을 기재해야 합니다.",
    "귀사의 사업 역량을 제시합니다.",
    "귀하께 필요한 사항을 안내합니다.",
    "귀하는 사업 실적을 제시합니다.",
    "회사 프로필을 참고하여 작성합니다.",
    "[담당자 확인 필요]",
])
def test_direct_guidance_internal_references_and_placeholders_are_rejected(sentence):
    body = "당사는 제조 현장에 솔루션을 제공합니다. " + sentence
    with pytest.raises(ProposalGuidanceError) as failure:
        verify_proposal_guidance(body)
    error = failure.value
    assert error.section_id is None
    assert error.issues == guidance_issues(body)
    issue = next(item for item in error.issues if sentence in item["sentence_text"])
    assert issue["sentence_number"] == 2
    assert issue["rule"]
    assert issue["matched_text"] in sentence


def test_writer_attaches_section_and_safe_hint_omits_generated_text():
    output = writing()
    output["sections"][2]["body"] += " " + PRIVATE_GUIDANCE
    with pytest.raises(ProposalGuidanceError) as failure:
        verify_writing(
            output, verify_outline(outline_data(), TEXT),
            ["service: 제조 AI 운영"], "양식", False,
            check_korean_style=False,
        )
    error = failure.value
    assert error.section_id == 3
    assert any(PRIVATE_GUIDANCE in item["sentence_text"] for item in error.issues)
    hint = validation_hint(error)
    assert hint == error.safe_hint()
    assert "3" in hint
    assert all(str(item["sentence_number"]) in hint for item in error.issues)
    assert all(item["rule"] in hint for item in error.issues)
    assert PRIVATE_GUIDANCE not in hint
    assert BODY not in hint
    assert "비공개사업" not in hint
    assert "sentence_text" not in hint and "matched_text" not in hint


async def run_public(responses, *, regeneration=False, repeats=1):
    """Use public caching/error handling and validate each actual response schema."""
    calls = []
    responses = iter(responses)

    def structured(schema):
        async def invoke(messages):
            calls.append((schema, copy.deepcopy(messages)))
            response = next(responses)
            if isinstance(response, Exception):
                raise response
            return schema.model_validate(response)
        return SimpleNamespace(ainvoke=invoke)

    writer = ProposalWriter()
    req = request()
    outline = verify_outline(outline_data(), req.template_text)
    if regeneration:
        data = req.model_dump(by_alias=True)
        data.update(
            generationId="guidance-regeneration",
            feedback="현장 수행 계획을 강조해 주세요.",
            previousSections=[dict(item, body=BODY) for item in outline_data()["sections"]],
        )
        req = ProposalWriteRequest.model_validate(data)
    outline_mock = AsyncMock(return_value=(outline, 0))
    if regeneration:
        outline_mock.side_effect = AssertionError("Saved sections must not be reselected")
    with (
        patch("app.domains.analysis.proposal_writer.ChatOpenAI",
              return_value=SimpleNamespace(with_structured_output=structured)),
        patch.object(writer, "_template_outline", outline_mock),
        patch("app.domains.analysis.proposal_writer.AnalysisSettings.from_env",
              return_value=settings()),
        patch("app.domains.analysis.proposal_writer.serialize_company_profile",
              return_value=json.dumps(PROFILE)),
    ):
        results = [await writer.write(req) for _ in range(repeats)]
    if regeneration:
        outline_mock.assert_not_awaited()
    return results, calls, writer


def repair_payload(messages):
    """Find the structured target data appended to the repair instruction."""
    decoder = json.JSONDecoder()
    for role, content in reversed(messages):
        if role != "human":
            continue
        for index, char in enumerate(content):
            if char != "{":
                continue
            try:
                value, _ = decoder.raw_decode(content[index:])
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict) and "repair_targets" in value:
                return value
    raise AssertionError("Repair instruction must include structured repair_targets")


@pytest.mark.parametrize("regeneration", [False, True])
def test_public_guidance_repair_targets_only_failures_and_preserves_metadata(
    regeneration, caplog,
):
    caplog.set_level(logging.INFO, logger="app.domains.analysis.proposal_writer")
    initial = slots()
    initial["section_2"]["body"] += " " + PRIVATE_GUIDANCE
    initial["section_4"]["body"] += " 회사 프로필을 참고하여 작성합니다."
    for index in range(1, 5):
        initial[f"section_{index}"]["confirmation_items"] = [
            f"{index}번 항목의 데이터 권한을 확인합니다."
        ]
    repair = slots([2, 4])
    for section in repair.values():
        section["company_evidence_ids"] = [999]
        section["confirmation_items"] = ["모델이 임의 변경한 확인 사항"]
    results, calls, writer = asyncio.run(run_public(
        [initial, repair], regeneration=regeneration,
    ))
    result = results[0]
    assert result.status == "COMPLETED"
    assert len(calls) == 2
    assert list(calls[0][0].model_fields) == [f"section_{index}" for index in range(1, 5)]
    assert list(calls[1][0].model_fields) == ["section_2", "section_4"]
    assert [section.body for section in result.sections] == [BODY] * 4
    for index, section in enumerate(result.sections, 1):
        assert section.confirmation_items == initial[f"section_{index}"]["confirmation_items"]
        assert section.company_evidence == [PROFILE["service"]]
    targets = repair_payload(calls[1][1])["repair_targets"]
    assert {target["section_id"] for target in targets} == {2, 4}
    for target in targets:
        assert target["body"] == initial[f'section_{target["section_id"]}']["body"]
        assert target["issues"]
        assert all(item["rule"] and item["sentence_number"] > 0 for item in target["issues"])
        assert all(item["matched_text"] in item["sentence_text"] for item in target["issues"])
    assert PRIVATE_GUIDANCE in json.dumps(targets, ensure_ascii=False)
    assert "mode=guidance_repair" in caplog.text
    assert "비공개사업" not in caplog.text and BODY not in caplog.text
    assert len(writer.cache) == (0 if regeneration else 1)
    assert not writer.inflight
    if regeneration:
        original_payload = json.loads(calls[0][1][1][1])
        assert original_payload["rewrite_feedback"] == "현장 수행 계획을 강조해 주세요."
        assert len(original_payload["previous_sections"]) == 4


def test_guidance_and_style_errors_in_different_sections_share_one_scoped_repair():
    initial = slots()
    initial["section_2"]["body"] += " " + PRIVATE_GUIDANCE
    initial["section_3"]["body"] += " 추진 일정 확인 필요함."
    results, calls, _ = asyncio.run(run_public([initial, slots([2, 3])]))
    assert results[0].status == "COMPLETED"
    assert len(calls) == 2
    assert list(calls[1][0].model_fields) == ["section_2", "section_3"]
    assert {item["section_id"] for item in repair_payload(calls[1][1])["repair_targets"]} == {2, 3}
    assert [item.body for item in results[0].sections] == [BODY] * 4


@pytest.mark.parametrize("regeneration", [False, True])
def test_repeated_guidance_failure_is_not_published_or_cached(regeneration, caplog):
    initial = slots()
    initial["section_2"]["body"] += " " + PRIVATE_GUIDANCE
    # A simultaneous style issue must not enable fallback to a guidance-invalid draft.
    initial["section_2"]["body"] += " 수행 조건 확인 필요함."
    still_invalid = {"section_2": copy.deepcopy(initial["section_2"])}
    results, calls, writer = asyncio.run(run_public(
        [initial, still_invalid, initial, still_invalid],
        regeneration=regeneration, repeats=2,
    ))
    assert all(result.status == "UNAVAILABLE" and not result.sections for result in results)
    assert len(calls) == 4
    assert not writer.cache and not writer.inflight
    assert PRIVATE_GUIDANCE not in caplog.text and BODY not in caplog.text
    assert all("비공개사업" not in result.message for result in results)


@pytest.mark.parametrize("failure_kind", ["too_short", "missing_target", "model_error"])
def test_guidance_repair_failure_never_falls_back_to_guidance_invalid_original(failure_kind):
    initial = slots()
    initial["section_2"]["body"] += " " + PRIVATE_GUIDANCE
    repaired = slots([2])
    if failure_kind == "too_short":
        repaired["section_2"]["body"] = "당사는 제조 현장의 개선 계획을 실행합니다."
    elif failure_kind == "missing_target":
        repaired = {}
    else:
        repaired = RuntimeError("private model failure")
    results, calls, writer = asyncio.run(run_public([initial, repaired]))
    assert results[0].status == "UNAVAILABLE" and not results[0].sections
    assert len(calls) == 2
    assert not writer.cache


def test_guidance_detection_does_not_hide_other_sections_invalid_evidence():
    initial = slots()
    initial["section_1"]["body"] += " " + PRIVATE_GUIDANCE
    initial["section_4"]["company_evidence_ids"] = [999]
    results, calls, writer = asyncio.run(run_public([initial, initial]))
    assert results[0].status == "UNAVAILABLE" and not results[0].sections
    assert list(calls[1][0].model_fields) == [f"section_{index}" for index in range(1, 5)]
    assert not writer.cache


def test_guidance_error_does_not_hide_other_sections_short_body():
    initial = slots()
    initial["section_2"]["body"] += " " + PRIVATE_GUIDANCE
    initial["section_4"]["body"] = "당사는 현장 개선을 추진합니다."
    results, calls, writer = asyncio.run(run_public([initial, slots()]))
    assert results[0].status == "COMPLETED"
    assert len(calls) == 2
    assert list(calls[1][0].model_fields) == [f"section_{index}" for index in range(1, 5)]
    assert [section.body for section in results[0].sections] == [BODY] * 4
    assert len(writer.cache) == 1