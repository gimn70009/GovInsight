"""Evidence and scope contracts for submission notes, independent of OCR."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Source(StrictModel):
    id: str = Field(min_length=1, max_length=40)
    text: str = Field(min_length=1, max_length=200000)


class Document(StrictModel):
    id: str = Field(min_length=1, max_length=40)
    title: str = Field(max_length=1000)
    sourceId: str
    excerpt: str = Field(max_length=8000)
    stage: str | None
    appliesTo: str | None


class NotesRequest(StrictModel):
    sources: list[Source] = Field(max_length=16)
    documents: list[Document] = Field(max_length=100)
    incomplete: bool

    @model_validator(mode="after")
    def bounded(self):
        ids = {s.id for s in self.sources}
        if len(ids) != len(self.sources) or sum(len(s.text) for s in self.sources) > 600000:
            raise ValueError("Invalid source catalog")
        if len({d.id for d in self.documents}) != len(self.documents):
            raise ValueError("Duplicate document IDs")
        if any(d.sourceId not in ids for d in self.documents):
            raise ValueError("Unknown document source")
        return self


class Note(StrictModel):
    sourceId: str
    quote: str = Field(min_length=1, max_length=2000)
    text: str = Field(min_length=1, max_length=800)
    scope: Literal["COMMON", "DOCUMENT", "UNRESOLVED"]
    documentId: str | None
    scopeQuote: str = Field(min_length=1, max_length=2000)
    appliesTo: str | None


class NotesDraft(StrictModel):
    notes: list[Note] = Field(max_length=30)
    complete: bool


class NotesResponse(StrictModel):
    notes: list[Note] = Field(default_factory=list, max_length=30)
    status: Literal["READY", "PARTIAL", "UNAVAILABLE"] = "UNAVAILABLE"
