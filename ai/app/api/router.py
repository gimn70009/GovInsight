from fastapi import APIRouter

from app.domains.monitoring.api import router as monitoring_router

api_router = APIRouter()
api_router.include_router(monitoring_router)
