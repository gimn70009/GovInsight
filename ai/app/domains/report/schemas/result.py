from pydantic import BaseModel, Field, field_validator


class ReportDraft(BaseModel):
    title: str = Field(min_length=5, max_length=500)
    summary: str = Field(min_length=20, max_length=20_000)

    @field_validator("title", "summary")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        return " ".join(value.split()) if "\n" not in value else value.strip()
