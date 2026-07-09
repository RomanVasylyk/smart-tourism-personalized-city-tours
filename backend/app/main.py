from app.api.routes import router
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.core.rate_limit import limiter
from fastapi import FastAPI
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

configure_logging(get_settings().log_level)

app = FastAPI(title="Smart Tourism API")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.include_router(router)
