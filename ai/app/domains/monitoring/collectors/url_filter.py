from collections.abc import Iterable
from urllib.parse import parse_qs, urljoin, urlparse, urlunparse


def select_document_urls(
    hrefs: Iterable[str | None],
    list_url: str,
    include_pattern: str | None,
    limit: int,
) -> list[str]:
    selected: list[str] = []
    seen: set[str] = set()
    list_origin = _origin(list_url)

    for href in hrefs:
        if not href:
            continue

        url = _normalize_url(urljoin(list_url, href))
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or _origin(url) != list_origin:
            continue
        if include_pattern and include_pattern not in url:
            continue
        if not include_pattern and url == _normalize_url(list_url):
            continue
        if url in seen:
            continue

        seen.add(url)
        selected.append(url)
        if len(selected) == limit:
            break

    return selected


def extract_external_document_id(url: str) -> str | None:
    parsed = urlparse(url)
    query = parse_qs(parsed.query)
    for key in ("id", "seq", "no", "idx", "newsId", "articleId", "boardSeq"):
        value = query.get(key)
        if value and value[0]:
            return value[0]

    path_parts = parsed.path.rstrip("/").split("/")
    path_part = path_parts[-1]
    if path_part == "view" and len(path_parts) > 1:
        path_part = path_parts[-2]
    return path_part or None


def _normalize_url(url: str) -> str:
    parsed = urlparse(url)
    return urlunparse(parsed._replace(params="", fragment=""))


def _origin(url: str) -> tuple[str, str]:
    parsed = urlparse(url)
    return parsed.scheme.lower(), parsed.netloc.lower()
