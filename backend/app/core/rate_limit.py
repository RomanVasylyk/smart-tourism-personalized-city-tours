from app.core.config import get_settings
from slowapi import Limiter
from slowapi.util import get_remote_address

_settings = get_settings()


def rate_limit_client_key(request) -> str:
    if get_settings().rate_limit_trust_proxy_headers:
        forwarded_for = request.headers.get("X-Forwarded-For", "")
        first_hop = forwarded_for.split(",")[0].strip()
        if first_hop:
            return first_hop
    return get_remote_address(request)


limiter = Limiter(key_func=rate_limit_client_key, enabled=_settings.rate_limit_enabled)

ROUTE_GENERATE_RATE_LIMIT = _settings.rate_limit_route_generate
ROUTE_LEG_RATE_LIMIT = _settings.rate_limit_route_leg
