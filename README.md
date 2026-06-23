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

## Quality checks

Install Python dev tooling and ktlint once on your machine:

```bash
python3 -m pip install -r requirements-dev.txt
brew install ktlint
pre-commit install
```

Run all configured static checks:

```bash
pre-commit run --all-files
```

Useful direct commands:

```bash
python3 -m ruff check backend pipeline
python3 -m black --check backend pipeline
ktlint "android-app/**/*.kt"
```
