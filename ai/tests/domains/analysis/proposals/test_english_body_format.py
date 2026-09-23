import asyncio
import json
import logging
import textwrap
from unittest.mock import patch

import pytest

from app.domains.analysis.proposals.language import (
    EnglishBodyFormatError,
    normalize_english_body,
    verify_english_body,
)
from app.domains.analysis.proposals.writer import normalize_draft_body, validation_hint
from tests.domains.analysis.proposals.test_language import ENGLISH_BODY, KOREAN_BODY
from tests.domains.analysis.proposals.test_reliability import compose, selection, written


@pytest.mark.parametrize(
    "body",
    [
        ENGLISH_BODY.rstrip("."),
        "\n".join(textwrap.wrap(ENGLISH_BODY, 64)),
        "\u2028".join(textwrap.wrap(ENGLISH_BODY, 64)),
        "**" + ENGLISH_BODY + "**",
        "_" + ENGLISH_BODY + "_",
        "`" + ENGLISH_BODY + "`",
        "```text\n" + ENGLISH_BODY + "\n```",
        "### Business Area\n\n" + ENGLISH_BODY,
        "Business Area:\n" + ENGLISH_BODY,
        ENGLISH_BODY + "\u200b\ufeff",
        ENGLISH_BODY.rstrip(".") + "。",
        "1. " + ENGLISH_BODY,
    ],
)
def test_formatting_changes_preserve_words_and_need_no_additional_model_call(body):
    assert normalize_english_body(body, "Business Area") == ENGLISH_BODY
    value = written()
    value["sections"][0]["body"] = body
    result, outline, writer = asyncio.run(compose([selection(30, 32)], [value]))
    assert result.status == "COMPLETED"
    assert result.sections[0].body == ENGLISH_BODY
    assert outline.ainvoke.await_count == writer.ainvoke.await_count == 1


def test_blank_lines_are_paragraphs_but_a_single_line_break_can_wrap_a_sentence():
    text = "We will develop and\nevaluate the model\n\nWe will document the results"
    result = normalize_english_body(text)
    assert result == "We will develop and evaluate the model.\n\nWe will document the results."
    verify_english_body(result)
    invalid = normalize_english_body("We will develop and\n\nWe will document the results.")
    with pytest.raises(EnglishBodyFormatError):
        verify_english_body(invalid)


@pytest.mark.parametrize(
    "note",
    [
        " [company_evidence_ids: [1, 2]]",
        " [evidence_ids=1,2]",
        " (Evidence: [1, 2])",
        " (company evidence ids: 1, 2)",
        " (source_ids: [1, 2])",
    ],
)
def test_numeric_metadata_variants_keep_separate_evidence_and_body(note):
    result, _, writer = asyncio.run(compose([selection(30, 32)], [written(ENGLISH_BODY + note)]))
    assert result.status == "COMPLETED"
    assert all(section.body == ENGLISH_BODY for section in result.sections)
    assert all(section.company_evidence for section in result.sections)
    assert writer.ainvoke.await_count == 1


@pytest.mark.parametrize(
    "ending",
    [
        "\n\nImplementation plan",
        " We will develop and",
        " We will develop and.",
        " We will compare the following:",
        " We will evaluate,",
        " We will evaluate...",
        "\n\n## An invented heading",
        " (company_evidence_ids: pending)",
        " (Evidence: pending)",
        " [source_ids: pending]",
        " [Insert company name].",
        " You must fill in this section.",
    ],
)
def test_incomplete_text_instructions_and_unresolved_metadata_are_not_punctuated_into_success(
    ending,
):
    with pytest.raises(ValueError):
        verify_english_body(normalize_english_body(normalize_draft_body(ENGLISH_BODY + ending)))


def test_english_formatting_does_not_bypass_wrong_language_or_korean_style_checks():
    with pytest.raises(ValueError, match="영어"):
        verify_english_body(normalize_english_body("**" + KOREAN_BODY + "**"))


def test_abbreviations_decimals_parentheses_quotes_and_end_prepositions_are_preserved():
    text = (
        "We use Example Inc. tools (version 2.5) and assess U.S. requirements.\n\n"
        'Our partners know what we stand for. We agree: "We will comply."'
    )
    assert normalize_english_body(text) == text
    verify_english_body(text)


def test_genuine_body_error_identifies_section_and_paragraph_without_logging_the_text(caplog):
    body = ENGLISH_BODY + "\n\nWe will develop PRIVATE_COMPANY_VALUE and"
    with patch.object(logging.getLogger("app"), "propagate", True):
        result, _, writer = asyncio.run(compose([selection(30, 32)], [written(body), written()]))
    assert result.status == "COMPLETED"
    assert writer.ainvoke.await_count == 2
    assert "section=1 paragraph=2" in caplog.text
    assert "PRIVATE_COMPANY_VALUE" not in caplog.text
    messages = writer.ainvoke.call_args.args[0]
    assert json.loads(messages[-2][1])["sections"][0]["body"] == body
    error = EnglishBodyFormatError(2, "PRIVATE_COMPANY_VALUE and")
    error.section_id = 1
    assert "section=1 paragraph=2" in validation_hint(error)
    assert "PRIVATE_COMPANY_VALUE" not in validation_hint(error)


def test_unknown_heading_error_counts_paragraphs_instead_of_soft_wrapped_lines():
    body = "\n".join(textwrap.wrap(ENGLISH_BODY, 64)) + "\n\n## Invented heading"
    with pytest.raises(EnglishBodyFormatError) as raised:
        normalize_english_body(body, "Business Area")
    assert raised.value.paragraph_number == 2
