from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

CriterionCode = Literal[
    "BUSINESS_FORM",
    "COMPANY_SIZE",
    "BUSINESS_AGE",
    "LOCATION",
    "LEGAL_FORM",
    "REPRESENTATIVE",
    "REVENUE",
    "CAPITAL",
    "CREDIT",
    "INVESTMENT",
    "EXPORT",
    "IP",
    "CERTIFICATION",
    "TRL",
    "QUALITY",
    "TCB",
    "SECTOR",
    "PRODUCT",
    "EXPERIENCE",
    "BUSINESS_ITEM",
    "HEADCOUNT",
    "EXPERTS",
    "EMPLOYMENT",
    "DUPLICATE",
    "COMPLETION",
    "CLOSURE",
    "TAX",
    "DEFAULT",
    "SANCTIONS",
    "EXCLUDED_SECTOR",
    "PLAN",
    "MATCHING",
    "CONSORTIUM",
    "LINKAGE",
    "PREF_REGION",
    "PREF_SOCIAL",
    "PREF_REPRESENTATIVE",
    "PREF_INVESTMENT_EXPORT",
    "PREF_AWARDS",
    "PREF_CLUSTER",
]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Criterion(StrictModel):
    code: str
    label: str
    category: str


class Source(StrictModel):
    id: str
    excerpt: str
    group_label: str = ""
    criteria: list[str] = Field(default_factory=list)


class RewriteRequest(StrictModel):
    sources: list[Source] = Field(max_length=160)
    criteria: list[Criterion] = Field(min_length=1, max_length=40)
    business_types: list[str] = Field(default_factory=list, max_length=40)


class PreparedRow(StrictModel):
    source_id: str
    raw_excerpt: str
    text: str
    group_label: str
    headers: list[str]
    cells: list[str]
    structure: Literal["text", "table_row", "ambiguous_table"]
    criteria: list[Criterion]


class Statement(StrictModel):
    source_ids: list[str]
    criterion: CriterionCode
    text: str
    group_label: str
    supporting_quotes: list[Annotated[str, Field(max_length=600)]] = Field(max_length=4)
    kind: Literal["ELIGIBILITY", "EXCLUSION", "PREFERENCE", "DEDUCTION", "FUNDING"]


class Draft(StrictModel):
    statements: list[Statement]
    unrecoverable_ids: list[str]
    irrelevant_source_ids: list[str] = Field(default_factory=list)


class Verdict(StrictModel):
    index: int
    approved: bool
    errors: list[
        Literal[
            "out_of_scope",
            "incomplete",
            "wrong_criterion",
            "unsupported_fact",
            "missing_qualification",
            "wrong_group",
            "ambiguous_table",
            "duplicate",
            "unnatural_sentence",
        ]
    ]


class Review(StrictModel):
    verdicts: list[Verdict]
    uncovered_source_ids: list[str]


class RewriteResponse(StrictModel):
    statements: list[Statement]
    evidence_only_ids: list[str]
    status: Literal["READY", "UNAVAILABLE"]
    irrelevant_source_ids: list[str] = Field(default_factory=list)
