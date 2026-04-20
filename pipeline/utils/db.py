import os
from contextlib import contextmanager

import psycopg


DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://smart_tourism:smart_tourism@localhost:5432/smart_tourism",
)


@contextmanager
def get_connection():
    conn = psycopg.connect(DATABASE_URL)
    try:
        yield conn
    finally:
        conn.close()
