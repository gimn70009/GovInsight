"""HTTP routes for monitoring jobs."""

from fastapi import APIRouter

router = APIRouter(prefix="/internal/monitoring", tags=["internal-monitoring"])
