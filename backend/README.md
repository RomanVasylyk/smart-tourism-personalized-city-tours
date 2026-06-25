# Backend

## Database migrations

Run schema migrations from the repository root:

```bash
python -m alembic upgrade head
```

Alembic migrations are the single source of truth for the database schema.
Future schema changes should be added as revisions under `backend/migrations/versions`.
The Docker backend container runs `alembic upgrade head` before starting the API.
