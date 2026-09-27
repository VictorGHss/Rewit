"""
Rewit Vision Service - Esqueleto fundacional em Python/FastAPI.
Microserviço dedicado para futuras tarefas de embeddings e visão computacional.
"""
from fastapi import FastAPI

app = FastAPI(
    title="Rewit Vision Service",
    version="0.1.0",
    description="Serviço de visão computacional e extração de embeddings para produtos do Rewit."
)

@app.get("/health")
def health():
    return {"status": "UP", "service": "rewit-vision", "version": "0.1.0"}
