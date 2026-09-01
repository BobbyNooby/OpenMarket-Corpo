import os

from fastapi import FastAPI

app = FastAPI(title="Presence & Notifications", version="0.1.0")


@app.get("/health/live")
def health_live():
    return {"status": "ok"}


@app.get("/health/ready")
def health_ready():
    db_url = os.environ.get("DATABASE_URL")
    if not db_url:
        return {"status": "no DATABASE_URL"}
    # In Phase 1+ this will ping the actual DB
    return {"status": "ready"}


@app.get("/")
def root():
    return {"service": "presence", "status": "ok", "version": "0.1.0"}
