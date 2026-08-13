from fastapi import FastAPI

from app.api.router import api_router
from app.core.logging import configure_logging

configure_logging()

app = FastAPI(
    title="GovInsight AI API",
    version="0.1.0",
)

app.include_router(api_router)


@app.get("/health", tags=["health"])
def health_check() -> dict[str, str]:
    return {"status": "UP"}
