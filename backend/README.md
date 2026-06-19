# Backend

## Database migrations

Run schema migrations from the repository root:

```bash
python -m alembic upgrade head
```

The baseline migration reuses `backend/sql/init.sql` for the current schema.
Future schema changes should be added as Alembic revisions under
`backend/migrations/versions`.
