"""
Day 6-7: Train XGBoost regressor → export to ONNX.

The model predicts optimal_limit (requests/second) given 6 traffic features.
ONNX export lets the Java inference service (Day 8) run predictions via
onnxruntime without a Python dependency at runtime.

Usage:
    python ml/generate_data.py        # create training data first
    python ml/train_model.py          # train and export
"""
import json
import numpy as np
import pandas as pd
from pathlib import Path
from sklearn.model_selection import cross_val_score, train_test_split
from sklearn.metrics import mean_absolute_error, r2_score
import xgboost as xgb

FEATURE_COLS = [
    "rolling_mean_rps_1m",
    "rolling_mean_rps_5m",
    "rps_p99_1m",
    "latency_p99_ms",
    "hour_of_day",
    "day_of_week",
]
TARGET_COL = "optimal_limit"
MODEL_DIR = Path(__file__).parent / "model"


def load_data() -> tuple[np.ndarray, np.ndarray]:
    data_path = Path(__file__).parent / "data" / "training_samples.csv"
    if not data_path.exists():
        raise FileNotFoundError(f"Missing training data — run generate_data.py first: {data_path}")

    df = pd.read_csv(data_path)
    print(f"Loaded {len(df):,} samples | features: {FEATURE_COLS}")
    X = df[FEATURE_COLS].values.astype(np.float32)
    y = df[TARGET_COL].values.astype(np.float32)
    return X, y


def train(X_train: np.ndarray, y_train: np.ndarray, X_val: np.ndarray, y_val: np.ndarray) -> xgb.XGBRegressor:
    model = xgb.XGBRegressor(
        n_estimators=300,
        max_depth=6,
        learning_rate=0.05,
        subsample=0.85,
        colsample_bytree=0.85,
        min_child_weight=5,
        gamma=0.1,
        objective="reg:squarederror",
        random_state=42,
        n_jobs=-1,
        early_stopping_rounds=30,
        eval_metric="mae",
    )
    model.fit(
        X_train,
        y_train,
        eval_set=[(X_val, y_val)],
        verbose=50,
    )
    return model


def evaluate(model: xgb.XGBRegressor, X_test: np.ndarray, y_test: np.ndarray) -> None:
    y_pred = model.predict(X_test)
    mae = mean_absolute_error(y_test, y_pred)
    r2 = r2_score(y_test, y_pred)
    within_10pct = np.mean(np.abs(y_pred - y_test) / (y_test + 1e-6) < 0.10)
    print(f"\n  MAE       = {mae:.2f} rps")
    print(f"  R²        = {r2:.4f}")
    print(f"  Within 10% = {within_10pct * 100:.1f}%")


def export_onnx(model: xgb.XGBRegressor) -> Path:
    """
    XGBoost >= 2.x supports native ONNX export via save_model('.onnx').
    The output is a standard ONNX graph; onnxruntime can load it on any platform.
    Input name: 'input' (float32 [N, 6])
    Output name: 'variable' (float32 [N])
    """
    MODEL_DIR.mkdir(exist_ok=True)
    onnx_path = MODEL_DIR / "rate_limiter.onnx"

    # Native XGBoost ONNX export — no onnxmltools needed for XGBoost >= 2.x
    model.save_model(str(onnx_path))
    return onnx_path


def save_metadata() -> None:
    meta = {
        "features": FEATURE_COLS,
        "target": TARGET_COL,
        "input_name": "input",
        "output_name": "variable",
        "description": "Predicts optimal rate limit (rps) from rolling traffic metrics.",
    }
    with open(MODEL_DIR / "metadata.json", "w") as f:
        json.dump(meta, f, indent=2)


def cross_validate(X: np.ndarray, y: np.ndarray) -> None:
    cv_model = xgb.XGBRegressor(
        n_estimators=150, max_depth=6, learning_rate=0.05, n_jobs=-1, random_state=42
    )
    scores = cross_val_score(cv_model, X, y, cv=5, scoring="neg_mean_absolute_error")
    print(f"\n  5-fold CV MAE: {-scores.mean():.2f} ± {scores.std():.2f} rps")


def verify_onnx(onnx_path: Path, X_sample: np.ndarray) -> None:
    """Quick sanity check — load ONNX and run one prediction."""
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path))
    input_name = sess.get_inputs()[0].name
    result = sess.run(None, {input_name: X_sample[:1]})[0]
    print(f"\n  ONNX sanity check — predicted limit: {result[0]:.1f} rps  ✓")


def main() -> None:
    X, y = load_data()
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)
    X_tr, X_val, y_tr, y_val = train_test_split(X_train, y_train, test_size=0.1, random_state=42)

    print("\nTraining XGBoost regressor...")
    model = train(X_tr, y_tr, X_val, y_val)

    print("\nTest set metrics:")
    evaluate(model, X_test, y_test)

    print("\nCross-validation:")
    cross_validate(X, y)

    print("\nExporting to ONNX...")
    onnx_path = export_onnx(model)
    save_metadata()
    print(f"  Saved → {onnx_path}")

    verify_onnx(onnx_path, X_test)

    print("\nDone. Run 'python ml/serve.py' to start the inference service.")


if __name__ == "__main__":
    main()
