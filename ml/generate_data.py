"""
Day 6: Synthetic traffic trace generator for XGBoost training.

Produces diurnal patterns (business-hour peaks) with random spike anomalies
across 4 client IDs matching the dev seed data. Output goes to ml/data/.

Usage:
    python ml/generate_data.py
"""
import numpy as np
import pandas as pd
from pathlib import Path

RANDOM_SEED = 42
DAYS = 30
CLIENT_BASE_RPS = {
    "dev-client-1": 100.0,
    "dev-client-2": 50.0,
    "dev-client-3": 200.0,
    "dev-client-4": 300.0,
}
SPIKE_PROBABILITY = 0.05
SPIKE_MULTIPLIER_RANGE = (3.0, 8.0)
ROLLING_WINDOW_MINUTES = 60


def diurnal_factor(hour: int) -> float:
    """Sinusoidal curve: peak ~14:00, trough ~03:00."""
    return 0.2 + 0.8 * np.sin(np.pi * max(0, hour - 3) / 21) ** 2


def simulate_minute_rps(
    hour: int, base_rps: float, rng: np.random.Generator
) -> tuple[float, bool]:
    factor = diurnal_factor(hour)
    mean = base_rps * factor
    rps = max(1.0, rng.normal(mean, mean * 0.12))
    is_spike = rng.random() < SPIKE_PROBABILITY
    if is_spike:
        rps *= rng.uniform(*SPIKE_MULTIPLIER_RANGE)
    return rps, is_spike


def compute_p99(values: list[float]) -> float:
    return float(np.percentile(values, 99)) if values else 0.0


def generate_client_trace(client_id: str, base_rps: float, rng: np.random.Generator) -> pd.DataFrame:
    total_minutes = DAYS * 24 * 60
    rows = []
    rps_history: list[float] = []

    for minute in range(total_minutes):
        hour_of_day = (minute // 60) % 24
        day_of_week = (minute // (60 * 24)) % 7

        rps, is_spike = simulate_minute_rps(hour_of_day, base_rps, rng)
        rps_history.append(rps)

        window_1m = rps_history[-ROLLING_WINDOW_MINUTES:]
        window_5m = rps_history[-ROLLING_WINDOW_MINUTES * 5:]

        rolling_mean_rps_1m = float(np.mean(window_1m))
        rolling_mean_rps_5m = float(np.mean(window_5m))
        rps_p99_1m = compute_p99(window_1m)

        # Latency degrades non-linearly with load
        latency_p99_ms = max(1.0, 1.5 + rolling_mean_rps_1m * 0.04 + rng.exponential(3.0))

        # Label: headroom of 20% above recent p99, clamped to a sane range
        optimal_limit = int(np.clip(rps_p99_1m * 1.2, base_rps * 0.1, base_rps * 3.0))

        rows.append(
            {
                "client_id": client_id,
                "rolling_mean_rps_1m": round(rolling_mean_rps_1m, 3),
                "rolling_mean_rps_5m": round(rolling_mean_rps_5m, 3),
                "rps_p99_1m": round(rps_p99_1m, 3),
                "latency_p99_ms": round(latency_p99_ms, 3),
                "hour_of_day": hour_of_day,
                "day_of_week": day_of_week,
                "optimal_limit": optimal_limit,
                "was_spike": is_spike,
            }
        )

    return pd.DataFrame(rows)


def main() -> None:
    rng = np.random.default_rng(RANDOM_SEED)
    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(exist_ok=True)

    frames = []
    for client_id, base_rps in CLIENT_BASE_RPS.items():
        df = generate_client_trace(client_id, base_rps, rng)
        frames.append(df)
        spikes = df["was_spike"].sum()
        print(
            f"  {client_id}: {len(df):,} rows | "
            f"limit [{df['optimal_limit'].min()}-{df['optimal_limit'].max()}] | "
            f"spikes: {spikes}"
        )

    combined = pd.concat(frames, ignore_index=True)
    out_path = out_dir / "training_samples.csv"
    combined.to_csv(out_path, index=False)
    print(f"\nSaved {len(combined):,} rows → {out_path}")


if __name__ == "__main__":
    main()
