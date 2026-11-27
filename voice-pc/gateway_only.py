"""Lightweight Agent gateway without importing Fairy model dependencies."""

import argparse

import uvicorn
from fastapi import FastAPI

from agent_gateway import router as agent_router

app = FastAPI()
app.include_router(agent_router)


@app.get("/health")
def health():
    return {"ready": True, "mode": "gateway"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning")
