from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = Field(
        default="postgresql://smart_tourism:smart_tourism@localhost:5432/smart_tourism",
        validation_alias="DATABASE_URL",
    )
    routing_base_url: str = Field(default="https://router.project-osrm.org", validation_alias="ROUTING_BASE_URL")
    routing_profile: str = Field(default="foot", validation_alias="ROUTING_PROFILE")
    routing_timeout_seconds: float = Field(default=0.75, validation_alias="ROUTING_TIMEOUT_SECONDS")
    routing_enabled: bool = Field(default=True, validation_alias="ROUTING_ENABLED")
    routing_table_enabled: bool = Field(default=True, validation_alias="ROUTING_TABLE_ENABLED")
    log_level: str = Field(default="INFO", validation_alias="LOG_LEVEL")

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
