"""
Day 7: FastAPI inference microservice wrapping the ONNX rate-limit model.

Endpoints:
  POST /predict    — { client_id, features } → { predicted_limit, source }
  GET  /health     — readiness probe for the Java circuit breaker

The Java AdaptiveRateLimitService (Day 8) calls this service and caches the
result in Redis for 5 minutes, so this endpoint need not be ultra-low latency.

Usage:
    uvicorn ml.serve:app --port 8000 --reload
  or:
    python ml/serve.py
"""
import json
import logging
import time
from pathlib import Path
from typing import Optional

import numpy as np
import onnxruntime as ort
import uvicorn
from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

MODEL_DIR = Path(__file__).parent / "model"
ONNX_PATH = MODEL_DIR / "rate_limiter.onnx"
META_PATH = MODEL_DIR / "metadata.json"

# Absolute limits — predictions outside this range are clamped
MIN_LIMIT = 1
MAX_LIMIT = 10_000

app = FastAPI(
    title="DARL ML Inference",
    description="Predicts optimal rate-limit (rps) per client from traffic features.",
    version="1.0.0",
)

# ── Model loading ────────────────────────────────────────────────────────────

_session: Optional[ort.InferenceSession] = None
_input_name: str = "input"
_feature_cols: list[str] = []
_startup_time: float = 0.0


@app.on_event("startup")
def load_model() -> None:
    global _session, _input_name, _feature_cols, _startup_time

    if not ONNX_PATH.exists():
        log.error("ONNX model not found at %s — run train_model.py first", ONNX_PATH)
        raise RuntimeError(f"ONNX model missing: {ONNX_PATH}")

    _session = ort.InferenceSession(
        str(ONNX_PATH),
        providers=["CPUExecutionProvider"],
    )
    _input_name = _session.get_inputs()[0].name

    if META_PATH.exists():
        meta = json.loads(META_PATH.read_text())
        _feature_cols = meta.get("features", [])

    _startup_time = time.time()
    log.info(
        "Model loaded: %s | input='%s' | features=%s",
        ONNX_PATH.name,
        _input_name,
        _feature_cols,
    )


# ── Request / Response schemas ────────────────────────────────────────────────


class Features(BaseModel):
    rolling_mean_rps_1m: float = Field(ge=0.0, description="Rolling mean RPS over last 1 minute")
    rolling_mean_rps_5m: float = Field(ge=0.0, description="Rolling mean RPS over last 5 minutes")
    rps_p99_1m: float = Field(ge=0.0, description="p99 RPS in last 1 minute")
    latency_p99_ms: float = Field(ge=0.0, description="p99 request latency in milliseconds")
    hour_of_day: int = Field(ge=0, le=23, description="UTC hour of day (0-23)")
    day_of_week: int = Field(ge=1, le=7, description="ISO day of week (1=Mon, 7=Sun)")


class PredictRequest(BaseModel):
    client_id: str = Field(description="Client identifier")
    features: Features


class PredictResponse(BaseModel):
    client_id: str
    predicted_limit: int
    raw_prediction: float
    source: str = "xgboost-onnx"
    model_version: str = "1.0"


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    uptime_seconds: float
    onnx_path: str


# ── Endpoints ─────────────────────────────────────────────────────────────────


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest) -> PredictResponse:
    if _session is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Model not loaded",
        )

    f = req.features
    x = np.array(
        [[
            f.rolling_mean_rps_1m,
            f.rolling_mean_rps_5m,
            f.rps_p99_1m,
            f.latency_p99_ms,
            float(f.hour_of_day),
            float(f.day_of_week),
        ]],
        dtype=np.float32,
    )

    raw: float = float(_session.run(None, {_input_name: x})[0][0])
    clamped = int(np.clip(round(raw), MIN_LIMIT, MAX_LIMIT))

    log.debug("predict client=%s raw=%.1f clamped=%d", req.client_id, raw, clamped)

    return PredictResponse(
        client_id=req.client_id,
        predicted_limit=clamped,
        raw_prediction=round(raw, 2),
    )


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok" if _session is not None else "degraded",
        model_loaded=_session is not None,
        uptime_seconds=round(time.time() - _startup_time, 1),
        onnx_path=str(ONNX_PATH),
    )


if __name__ == "__main__":
    uvicorn.run("serve:app", host="0.0.0.0", port=8000, reload=False)
