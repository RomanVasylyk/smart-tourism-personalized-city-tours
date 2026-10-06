from app.core.config import get_settings
from slowapi import Limiter
from slowapi.util import get_remote_address

_settings = get_settings()


def rate_limit_client_key(request) -> str:
    settings = get_settings()
    if settings.rate_limit_trust_proxy_headers:
        forwarded_for = request.headers.get("X-Forwarded-For", "")
        hops = [hop.strip() for hop in forwarded_for.split(",") if hop.strip()]
        trusted_hops = max(1, int(getattr(settings, "rate_limit_trusted_proxy_hops", 1)))
        if len(hops) >= trusted_hops:
            return hops[-trusted_hops]
    return get_remote_address(request)


limiter = Limiter(key_func=rate_limit_client_key, enabled=_settings.rate_limit_enabled)

ROUTE_GENERATE_RATE_LIMIT = _settings.rate_limit_route_generate
ROUTE_LEG_RATE_LIMIT = _settings.rate_limit_route_leg
CATALOG_RATE_LIMIT = _settings.rate_limit_catalog
