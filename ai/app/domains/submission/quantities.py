"""Exclude proven row ordinals and repeated quantity cells from numeric comparison."""

import re


def comparison_quote(quote: str, title: str, row: str) -> str:
    if quote != row or "\n" in row or "\r" in row:
        return quote
    cells = [cell.strip() for cell in row.split("\t")]
    if len(cells) != 4 or not cells[0].isdigit() or cells[1] != title:
        return quote
    # The catalog already proves this is a document row: its first cell is an ordinal.
    cells = cells[1:]
    quantity = re.fullmatch(r"(?:각\s*)?(\d+\s*(?:부|매))", cells[1])
    if quantity and re.fullmatch(
        r"[가-힣A-Za-z()·ㆍ]+별\s*(?:각\s*)?" + re.escape(quantity[1]), cells[2]
    ):
        cells.pop(1)
    return "\t".join(cells)
