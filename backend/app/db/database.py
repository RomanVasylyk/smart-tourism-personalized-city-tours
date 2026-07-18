import atexit
from functools import lru_cache

from app.core.config import get_settings
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


@lru_cache(maxsize=1)
def _get_pool() -> ConnectionPool:
    settings = get_settings()
    pool = ConnectionPool(
        conninfo=settings.database_url,
        min_size=settings.db_pool_min_size,
        max_size=settings.db_pool_max_size,
        timeout=settings.db_pool_timeout_seconds,
        max_idle=settings.db_pool_max_idle_seconds,
        kwargs={"row_factory": dict_row},
        check=ConnectionPool.check_connection,
        open=False,
    )
    pool.open(wait=False)
    atexit.register(pool.close)
    return pool


def get_connection():
    return _get_pool().connection()
