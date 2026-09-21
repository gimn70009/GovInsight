"""Detect explicit drafting instructions without matching arbitrary substrings."""

import re

from app.domains.analysis.proposal_korean import _sentences

_PARTICLES = r"(?:에서는|에게|에서|께서|께|으로|의|은|는|이|가|을|를|에|로|와|과|도|만)?"
_COMPANY_SUBJECT = re.compile(
    r"^\s*(?:당사|저희(?:\s*회사)?|우리\s*회사)(?:는|가|에서)(?!\w)"
)
_RULES = (
    ("reader_address", re.compile(r"(?<!\w)(?:귀사|귀하)" + _PARTICLES + r"(?!\w)")),
    ("writing_instruction", re.compile(
        r"(?<!\w)(?:작성|기재)(?:하세요|하십시오|해\s*주세요|해\s*주십시오)(?!\w)"
    )),
    # A relative clause about a deliverable ('작성해야 하는 보고서') is not an instruction.
    ("writing_obligation", re.compile(
        r"(?<!\w)(?:작성|기재)(?:해야|하여야)\s*(?:합니다|됩니다|한다|함)"
        r"(?=\s*[.!?。]|$)"
    )),
    ("internal_profile", re.compile(r"회사\s*프로필")),
    # Do not bridge separate brackets across ordinary prose containing '확인'.
    ("confirmation_placeholder", re.compile(r"\[[^\[\]\n]*확인[^\[\]\n]*\]")),
)


class ProposalGuidanceError(ValueError):
    def __init__(self, issues):
        super().__init__("작성 안내 대신 회사의 제안 본문을 작성해야 합니다.")
        self.section_id = None
        # Generated snippets are for model repair input only, never logs or user errors.
        self.issues = issues

    def safe_hint(self):
        first = self.issues[0]
        return (
            f"{self} section={self.section_id} sentence={first['sentence_number']} "
            f"rule={first['rule']} issues={len(self.issues)}"
        )


def guidance_issues(body: str) -> list[dict]:
    issues = []
    sentence_number = 0
    for line in body.splitlines():
        for sentence in _sentences(line):
            sentence_number += 1
            matches = []
            for rule, pattern in _RULES:
                # A stated company obligation is proposal content, not a reader instruction.
                if rule == "writing_obligation" and _COMPANY_SUBJECT.search(sentence):
                    continue
                match = pattern.search(sentence)
                if match:
                    matches.append((match.start(), rule, match.group()))
            for _position, rule, matched in sorted(matches):
                issues.append({
                    "rule": rule,
                    "sentence_number": sentence_number,
                    "sentence_text": sentence,
                    "matched_text": matched,
                })
    return issues


def verify_proposal_guidance(body: str) -> None:
    issues = guidance_issues(body)
    if issues:
        raise ProposalGuidanceError(issues)
