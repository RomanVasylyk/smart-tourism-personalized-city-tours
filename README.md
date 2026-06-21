# Smart Tourism

Štruktúra pre diplomový  projekt:
- `android-app/` – natívna Android aplikácia v Kotlin + Jetpack Compose
- `backend/` – FastAPI backend
- `pipeline/` – ETL skripty pre import a prípravu dát

## Docker dev config

Create a local `.env` from `.env.example` before running Compose. The example
uses dev-only credentials and pinned image tags; override them locally for your
machine or deployment environment.

```bash
cp .env.example .env
docker compose up --build
```
