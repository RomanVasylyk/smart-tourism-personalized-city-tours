from app.core.config import get_settings
from slowapi import Limiter
from slowapi.util import get_remote_address

_settings = get_settings()

limiter = Limiter(key_func=get_remote_address, enabled=_settings.rate_limit_enabled)

ROUTE_GENERATE_RATE_LIMIT = _settings.rate_limit_route_generate
ROUTE_LEG_RATE_LIMIT = _settings.rate_limit_route_leg
