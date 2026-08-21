from fastapi import APIRouter

from app.domains.analysis.api import router as analysis_router
from app.domains.monitoring.api import router as monitoring_router

api_router = APIRouter()
api_router.include_router(analysis_router)
api_router.include_router(monitoring_router)
