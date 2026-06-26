from app.api.routes import router
from app.core.config import get_settings
from app.core.logging import configure_logging
from fastapi import FastAPI

configure_logging(get_settings().log_level)

app = FastAPI(title="Smart Tourism API")
app.include_router(router)
