FROM python:3.13-slim

WORKDIR /app

COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/app ./app
COPY backend/migrations ./backend/migrations
COPY backend/scripts ./scripts
COPY alembic.ini ./alembic.ini
COPY pipeline/config ./pipeline/config

CMD ["sh", "-c", "python -m alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000"]
