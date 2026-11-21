from app.admin.api import STATIC_DIR
from app.admin.api import router as admin_router
from app.api.auth_routes import router as auth_router
from app.api.routes import router
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.core.rate_limit import limiter
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

configure_logging(get_settings().log_level)

app = FastAPI(title="Smart Tourism API")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.include_router(router)
app.include_router(auth_router)
app.include_router(admin_router)
app.mount("/admin/static", StaticFiles(directory=STATIC_DIR), name="admin-static")
