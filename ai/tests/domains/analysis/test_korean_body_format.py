import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.domains.analysis.proposal_korean import (
    KoreanBodyFormatError,
    normalize_korean_body,
    verify_korean_body,
)
from app.domains.analysis.proposal_writer import (
    ProposalWriter,
    ProposalWriteRequest,
    TemplateSection,
    validation_hint,
    verify_writing,
)


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("당사는 고객의 데이터를\n검토하겠습니다.", "당사는 고객의 데이터를 검토하겠습니다."),
        ("조건을 확인하고\n협력하겠습니다.", "조건을 확인하고 협력하겠습니다."),
        ("확인하겠습니다\n추진하겠습니다", "확인하겠습니다.\n추진하겠습니다."),
        ("확인하겠습니다\n\n추진하겠습니다", "확인하겠습니다.\n\n추진하겠습니다."),
        ("확인하겠습니다。 추진하겠습니다．", "확인하겠습니다. 추진하겠습니다."),
        ("확인하겠습니다 .", "확인하겠습니다."),
        ("확인하겠습\u200b니다.\r\n\r\n추진하겠습니다.", "확인하겠습니다.\n\n추진하겠습니다."),
        (
            "예산은 3.5억 원이며 아직 확정되지 않았습니다.",
            "예산은 3.5억 원이며 아직 확정되지 않았습니다.",
        ),
        (
            "U.S. 기준과 Example Inc. 자료를 검토하겠습니다.",
            "U.S. 기준과 Example Inc. 자료를 검토하겠습니다.",
        ),
        ("2026. 9. 11. 기준으로 확인하겠습니다.", "2026. 9. 11. 기준으로 확인하겠습니다."),
        ("검토하겠습니다(승인 이후).", "검토하겠습니다(승인 이후)."),
    ],
)
def test_only_presentation_is_repaired(raw, expected):
    body = normalize_korean_body(raw)
    assert body == expected
    assert normalize_korean_body(body) == body
    verify_korean_body(body)


@pytest.mark.parametrize(
    "bad",
    [
        "현장 적용을 권장함.",
        "현장 적용을 권장함\n추진하겠습니다.",
        "확인이 필요하다. 추진하겠습니다.",
        "확인이 필요하다.추진하겠습니다.",
        "확인할 예정입니다. 그러나",
        "확인할 예정입니다...",
        "확인하겠습니다!",
        "확인하겠습니다?",
        "사업 목표\n추진하겠습니다.",
        "# 사업 목표\n추진하겠습니다.",
        "1. 추진하겠습니다.",
        "- 추진하겠습니다.",
        "조건을 확인하고\n- 추진하겠습니다.",
        "조건을 확인하고\n\n추진하겠습니다.",
        "Our company will comply. 추진하겠습니다.",
        "목표는 123. 추진하겠습니다.",
        "",
    ],
)
def test_real_style_errors_are_not_hidden_by_normalization(bad):
    with pytest.raises(KoreanBodyFormatError):
        verify_korean_body(normalize_korean_body(bad))


BODY = ("당사는 고객의 데이터를 검토하고 승인 이후에만 협력 범위를 정하겠습니다. " * 12).strip()
SECTION = TemplateSection(
    title="사업 목표", source_quote="사업 목표", selection_reason="목표를 확인합니다."
)


def output(body=BODY):
    return {
        "sections": [
            {
                "section_id": 1,
                "body": body,
                "company_evidence_ids": [1],
                "confirmation_items": ["데이터 접근 승인을 확인합니다."],
            }
        ]
    }


@pytest.mark.parametrize("failure", ["evidence", "short", "missing", "instruction"])
def test_presentation_repair_keeps_other_validations(failure):
    data = output(BODY.rstrip("."))
    if failure == "evidence":
        data["sections"][0]["company_evidence_ids"] = [99]
    elif failure == "short":
        data["sections"][0]["body"] = "추진하겠습니다"
    elif failure == "missing":
        data["sections"] = []
    else:
        data["sections"][0]["body"] += " 귀사는 내용을 작성해야 합니다."
    with pytest.raises(ValueError):
        verify_writing(data, [SECTION], ["service: 제조 AI 운영"], "양식", False)


def test_error_identifies_section_and_sentence_without_logging_body():
    data = output(BODY + " PRIVATE_CUSTOMER 적용 권장함.")
    with pytest.raises(KoreanBodyFormatError) as caught:
        verify_writing(data, [SECTION], ["service: 제조 AI 운영"], "양식", False)
    hint = validation_hint(caught.value)
    assert "section=1 sentence=13 ending=non_formal_or_incomplete" in hint
    assert "PRIVATE_CUSTOMER" not in hint
    assert "적용 권장함" not in hint


@pytest.mark.parametrize("mode", ["formatting", "repair", "persistent_failure"])
def test_generation_avoids_format_retry_and_bounds_real_failure(mode):
    async def scenario():
        selector = AsyncMock()
        selector.ainvoke.return_value = {
            "is_writing_template": True,
            "sections": [{"heading_line_ids": [1], "selection_reason": "사업 목표를 확인합니다."}],
        }
        writer = AsyncMock()
        bad = output(BODY + " PRIVATE_CUSTOMER 적용 권장함.")
        if mode == "formatting":
            writer.ainvoke.side_effect = [
                output(BODY.replace("데이터를 검토", "데이터를\n검토").rstrip("."))
            ]
        else:
            writer.ainvoke.side_effect = [bad, bad if mode == "persistent_failure" else output()]
        model = SimpleNamespace(
            with_structured_output=lambda schema: (
                selector if schema.__name__ == "TemplateSelectionOutput" else writer
            )
        )
        settings = SimpleNamespace(
            proposal_model_name="test", api_key="test", proposal_timeout_seconds=180
        )
        request = ProposalWriteRequest(
            title="제조 AI",
            notice_text="실증을 지원합니다.",
            file_name="양식",
            template_text="사업 목표\n목표를 작성합니다.",
        )
        with (
            patch("app.domains.analysis.proposal_writer.ChatOpenAI", return_value=model),
            patch("app.domains.analysis.proposal_writer.logger") as logger,
        ):
            response = await ProposalWriter()._generate(
                "test",
                request,
                {"service": "제조 현장 데이터를 검토하고 AI 모델을 운영하는 서비스를 제공합니다."},
                settings,
                "2026-09-11",
            )
        assert response.status == ("UNAVAILABLE" if mode == "persistent_failure" else "COMPLETED")
        assert selector.ainvoke.await_count == 1
        assert writer.ainvoke.await_count == (1 if mode == "formatting" else 2)
        if response.status == "COMPLETED":
            assert response.sections[0].body == BODY
            assert response.sections[0].confirmation_items == ["데이터 접근 승인을 확인합니다."]
        if mode != "formatting":
            messages = writer.ainvoke.call_args.args[0]
            assert "section=1 sentence=13" in messages[-2][1]
            assert "계획과 확정 사실의 구분을 유지" in messages[-1][1]
            logged = str(logger.warning.call_args_list)
            assert "section=1 sentence=13" in logged
            assert "PRIVATE_CUSTOMER" not in logged

    asyncio.run(scenario())
