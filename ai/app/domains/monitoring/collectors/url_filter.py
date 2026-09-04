import re
from collections.abc import Iterable
from urllib.parse import parse_qs, urljoin, urlparse, urlunparse

from app.domains.monitoring.collectors.site_profiles import get_site_profile

VALID_EXTERNAL_ID = re.compile(r"^[A-Za-z0-9_-]+$")


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
    profile = get_site_profile(url)
    query = {key.lower(): values for key, values in parse_qs(parsed.query).items()}

    for key in profile.document_id_query_keys:
        values = query.get(key.lower())
        if not values:
            continue
        candidate = values[0].strip()
        if VALID_EXTERNAL_ID.fullmatch(candidate):
            return candidate

    if profile.document_id_path_pattern:
        match = re.search(profile.document_id_path_pattern, parsed.path, re.IGNORECASE)
        if match and VALID_EXTERNAL_ID.fullmatch(match.group(1)):
            return match.group(1)
    return None


def _normalize_url(url: str) -> str:
    parsed = urlparse(url)
    return urlunparse(parsed._replace(params="", fragment=""))


def _origin(url: str) -> tuple[str, str]:
    parsed = urlparse(url)
    return parsed.scheme.lower(), parsed.netloc.lower()
