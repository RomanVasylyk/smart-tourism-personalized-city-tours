from fastapi import FastAPI

from app.api.routes import router

app = FastAPI(title="Smart Tourism API")
app.include_router(router)
