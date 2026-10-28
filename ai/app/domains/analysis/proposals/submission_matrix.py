"""Read explicit obligation matrices by row shape and header paths, not document names."""

import re

from .submission_lists import GroupedSubmissionRow

YES = re.compile(r"[○◯Oo●◎✓√]")
NO = re.compile(r"[Xx×✕✗-]")
PREFIX = re.compile(r"^\[([^\]\n]{1,60})\]\s*(\S.*)$")


def compact(value):
    return re.sub(r"\s+|⏎", "", value)


def obligation(value):
    match = PREFIX.fullmatch(value.strip())
    if not match:
        return None
    label, title = match.groups()
    key = compact(label)
    if "해당" in key or re.search(r"(?:시|경우)(?:필수)?$", key):
        marker = "해당 시"
    elif key in ("필수", "필수제출"):
        marker = "필수"
    elif key in ("선택", "선택제출"):
        marker = "선택"
    else:
        return None
    if not 2 <= len(title) <= 150:
        return None
    return title, marker, label if marker == "해당 시" else ""


def matrix_rows(rows, context, after):
    """None means unsupported; [] means a recognized matrix with no safe rows."""
    if len(rows) < 3 or len({len(r) for r in rows}) != 1:
        return None
    for first, row in enumerate(rows[:8]):
        names = [c for c, value in enumerate(row) if obligation(value)]
        roles = [
            c
            for c, value in enumerate(row)
            if YES.fullmatch(value.strip()) or NO.fullmatch(value.strip())
        ]
        if len(names) != 1 or not roles or first == 0:
            continue
        name = names[0]
        headers = rows[:first]
        # Complete the role group even when the first row contains a blank mark.
        role_set = set(roles)
        for head in headers:
            if len(set(head)) <= 1:
                continue
            branches = {head[c] for c in roles if head[c].strip()}
            role_set.update(c for c, label in enumerate(head) if label in branches and c != name)
        role_set.update(
            c
            for data in rows[first:]
            for c, value in enumerate(data)
            if YES.fullmatch(value.strip()) or NO.fullmatch(value.strip())
        )
        roles = sorted(role_set)
        # A submission context is required; a bare evaluation/applicability grid is not enough.
        envelope = context + "\n" + "\n".join("\t".join(r) for r in headers)
        if not re.search(r"(?:제출|신청|접수|구비)\s*(?:서류|자료)", envelope):
            continue
        labels = []
        for column in roles:
            path = []
            for head in headers:
                if len(set(head)) <= 1:
                    continue  # full-width table caption
                label = compact(head[column])
                if label and (not path or label != path[-1]):
                    path.append(label)
            if not path:
                return []
            labels.append(path[-1])
        if len(set(labels)) != len(labels):
            return []  # merged role group without individual column labels
        # Shared exceptions cannot be silently dropped from an obligation.
        if re.match(r"\s*[*※]", after) or re.search(
            r"(?m)^\s*[*※].*(?:면제|생략|대체|대신|제출하지)", context
        ):
            return []
        result = []
        for row in rows[first:]:
            parsed = obligation(row[name])
            if parsed is None:
                continue
            title, marker, qualification = parsed
            # An extra prose column may contain an exception; never silently discard it.
            if any(
                value.strip()
                and not re.fullmatch(r"(?:(?:별첨|붙임|첨부|서식|별지)\s*)?\d+[.)]?", value.strip())
                for column, value in enumerate(row)
                if column not in roles and column != name
            ):
                continue
            include, exclude = [], []
            for column, label in zip(roles, labels, strict=True):
                value = row[column].strip()
                if YES.fullmatch(value):
                    include.append(label)
                elif NO.fullmatch(value):
                    exclude.append(label)
                else:
                    break
            else:
                if not include:
                    continue
                conditions = ["제출 대상: " + " · ".join(include)]
                if qualification:
                    conditions.append(qualification)
                if exclude:
                    conditions.append("제출 대상 아님: " + " · ".join(exclude))
                condition = " / ".join(conditions)
                if len(condition) <= 200:
                    result.append(
                        GroupedSubmissionRow(
                            title,
                            "\t".join(row),
                            "\n".join(
                                line
                                for line in envelope.splitlines()
                                if not re.match(r"\s*[*※]", line)
                            ),
                            marker,
                            condition,
                        )
                    )
        return result
    return None


def unresolved_matrix_rows(text):
    from app.domains.monitoring.parsers.table_text import tables

    count = 0
    for start, end, rows in tables(text):
        parsed = matrix_rows(rows, text[max(0, start - 600) : start], text[end:])
        if parsed is not None:
            explicit = sum(any(obligation(cell) is not None for cell in row) for row in rows)
            count += max(0, explicit - len(parsed))
    return count
