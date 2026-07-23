from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = Field(
        default="postgresql://smart_tourism:smart_tourism@localhost:5432/smart_tourism",
        validation_alias="DATABASE_URL",
    )
    db_pool_min_size: int = Field(default=1, validation_alias="DB_POOL_MIN_SIZE")
    db_pool_max_size: int = Field(default=10, validation_alias="DB_POOL_MAX_SIZE")
    db_pool_timeout_seconds: float = Field(default=10.0, validation_alias="DB_POOL_TIMEOUT_SECONDS")
    db_pool_max_idle_seconds: float = Field(default=300.0, validation_alias="DB_POOL_MAX_IDLE_SECONDS")
    routing_base_url: str = Field(default="https://router.project-osrm.org", validation_alias="ROUTING_BASE_URL")
    routing_profile: str = Field(default="foot", validation_alias="ROUTING_PROFILE")
    routing_timeout_seconds: float = Field(default=0.75, validation_alias="ROUTING_TIMEOUT_SECONDS")
    routing_enabled: bool = Field(default=True, validation_alias="ROUTING_ENABLED")
    routing_table_enabled: bool = Field(default=True, validation_alias="ROUTING_TABLE_ENABLED")
    rate_limit_enabled: bool = Field(default=True, validation_alias="RATE_LIMIT_ENABLED")
    rate_limit_route_generate: str = Field(default="30/minute", validation_alias="RATE_LIMIT_ROUTE_GENERATE")
    rate_limit_route_leg: str = Field(default="60/minute", validation_alias="RATE_LIMIT_ROUTE_LEG")
    rate_limit_trust_proxy_headers: bool = Field(default=False, validation_alias="RATE_LIMIT_TRUST_PROXY_HEADERS")
    log_level: str = Field(default="INFO", validation_alias="LOG_LEVEL")

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
