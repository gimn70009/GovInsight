"""Separate a document-name paragraph from explicit notes in that same cell."""

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class NameCell:
    title: str
    notes: tuple[str, ...]


def name_cell(value: str) -> NameCell | None:
    parts = [part.strip() for part in value.split("⏎")]
    title = re.sub(r"^(?:[•▪◦●○-]|\d+[.)])\s*", "", parts[0]).strip()
    # A second list item is another document, not a note belonging to the first.
    if not title or any(part and not re.match(r"^[*※]", part) for part in parts[1:]):
        return None
    return NameCell(title, tuple(part for part in parts[1:] if part))
