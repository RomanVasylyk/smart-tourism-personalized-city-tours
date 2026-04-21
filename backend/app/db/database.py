import os

from psycopg import connect
from psycopg.rows import dict_row

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://smart_tourism:smart_tourism@localhost:5432/smart_tourism",
)


def get_connection():
    return connect(DATABASE_URL, row_factory=dict_row)
