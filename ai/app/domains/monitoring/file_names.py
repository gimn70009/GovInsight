import re
from pathlib import Path

FILE_NAME_WITH_EXTENSION = re.compile(
    r"(?i)^(.+?\.([a-z0-9]{1,10}))(?=\s*(?:[\[(,]|$))"
)
FILE_SIZE_SUFFIX = re.compile(
    r"(?i)\s*(?:[\[(]\s*)?\d+(?:[.,]\d+)?\s*(?:bytes?|kb|mb|gb)\s*[\])]?[\s,]*$"
)


def normalize_attachment_file_name(file_name: str) -> str:
    cleaned = file_name.replace("\u200b", "").replace("\ufeff", "").strip()
    match = FILE_NAME_WITH_EXTENSION.match(cleaned)
    if match:
        return match.group(1).strip()
    return FILE_SIZE_SUFFIX.sub("", cleaned).strip(" ,")


def attachment_file_extension(file_name: str) -> str:
    normalized = normalize_attachment_file_name(file_name)
    return Path(normalized).suffix.lower()
