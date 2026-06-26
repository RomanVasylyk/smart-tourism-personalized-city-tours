from app.core.config import get_settings
from psycopg import connect
from psycopg.rows import dict_row


def get_connection():
    return connect(get_settings().database_url, row_factory=dict_row)
