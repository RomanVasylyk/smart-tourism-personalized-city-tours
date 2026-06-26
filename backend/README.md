# Backend

## Database migrations

Run schema migrations from the repository root:

```bash
python -m alembic upgrade head
```

Alembic migrations are the single source of truth for the database schema.
Future schema changes should be added as revisions under `backend/migrations/versions`.
The Docker backend container runs `alembic upgrade head` before starting the API.

## Runtime configuration

Backend settings are loaded through `app.core.config.Settings` from environment
variables or a local `.env` file:

- `DATABASE_URL`
- `ROUTING_BASE_URL`
- `ROUTING_PROFILE`
- `ROUTING_TIMEOUT_SECONDS`
- `ROUTING_ENABLED`
- `LOG_LEVEL`
