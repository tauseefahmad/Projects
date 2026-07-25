"""Train and evaluate ML models for the bimolecular coupling-energy challenge.

Since the official challenge page could not be reached from this environment
(blocked by network policy), the evaluation protocol below is our own
reasonable default, documented in the README: a conformer-level train/test
split (80/20 of each monomer's conformers) so that the test set contains
pair combinations built entirely from held-out conformers of BOTH monomers,
never seen (individually or in combination) during training. This is a
stricter, more realistic test of generalization than a random split of the
40000 pairs, which would let the model see each conformer in many other
training pairs.

Usage:
    python src/train.py
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from data_utils import load_dataset
from features import build_features

from sklearn.ensemble import HistGradientBoostingRegressor, RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

DATA_DIR = Path(__file__).parent.parent / "data"
RESULTS_DIR = Path(__file__).parent.parent / "results"
SEED = 0


def conformer_split(n_conformers: int, test_frac: float, seed: int):
    rng = np.random.RandomState(seed)
    idx = rng.permutation(n_conformers)
    n_test = int(round(n_conformers * test_frac))
    test_idx = set(idx[:n_test].tolist())
    train_idx = set(idx[n_test:].tolist())
    return train_idx, test_idx


def main():
    RESULTS_DIR.mkdir(exist_ok=True)
    print("Loading dataset...")
    d = load_dataset(str(DATA_DIR))
    coords_a, coords_b = d["coords_a"], d["coords_b"]
    energies = d["energies"]

    n_conf = coords_a.shape[0]
    train_conf, test_conf = conformer_split(n_conf, test_frac=0.2, seed=SEED)

    mol_a = energies["molA"].to_numpy()
    mol_b = energies["molB"].to_numpy()
    y = energies["coupling_energy"].to_numpy()

    is_a_train = np.isin(mol_a, list(train_conf))
    is_b_train = np.isin(mol_b, list(train_conf))
    is_a_test = np.isin(mol_a, list(test_conf))
    is_b_test = np.isin(mol_b, list(test_conf))

    train_mask = is_a_train & is_b_train
    test_mask = is_a_test & is_b_test
    # "mixed" pairs (one seen conformer, one unseen) are dropped from both
    # sets to keep train/test cleanly separated; report their count for
    # transparency only.
    mixed_mask = ~(train_mask | test_mask)

    print(f"conformers: {n_conf} total, {len(train_conf)} train, {len(test_conf)} test")
    print(f"pairs: {train_mask.sum()} train, {test_mask.sum()} test, "
          f"{mixed_mask.sum()} mixed (unused)")

    print("Building features...")
    t0 = time.time()
    X_train = build_features(coords_a, coords_b, mol_a[train_mask], mol_b[train_mask])
    X_test = build_features(coords_a, coords_b, mol_a[test_mask], mol_b[test_mask])
    y_train, y_test = y[train_mask], y[test_mask]
    print(f"  X_train {X_train.shape}, X_test {X_test.shape} ({time.time() - t0:.1f}s)")

    scaler = StandardScaler()
    X_train_s = scaler.fit_transform(X_train)
    X_test_s = scaler.transform(X_test)

    models = {
        "ridge": Ridge(alpha=1.0, random_state=SEED),
        "random_forest": RandomForestRegressor(
            n_estimators=300, max_depth=None, n_jobs=-1, random_state=SEED
        ),
        "hist_gbdt": HistGradientBoostingRegressor(
            max_iter=400, learning_rate=0.05, random_state=SEED
        ),
    }

    results = {}
    for name, model in models.items():
        t0 = time.time()
        Xtr = X_train_s if name == "ridge" else X_train
        Xte = X_test_s if name == "ridge" else X_test
        model.fit(Xtr, y_train)
        pred = model.predict(Xte)
        mae = mean_absolute_error(y_test, pred)
        rmse = mean_squared_error(y_test, pred) ** 0.5
        r2 = r2_score(y_test, pred)
        train_pred = model.predict(Xtr)
        train_mae = mean_absolute_error(y_train, train_pred)
        elapsed = time.time() - t0
        print(f"[{name}] train_MAE={train_mae:.3f}  test_MAE={mae:.3f}  "
              f"test_RMSE={rmse:.3f}  test_R2={r2:.4f}  ({elapsed:.1f}s)")
        results[name] = {
            "train_mae": train_mae,
            "test_mae": mae,
            "test_rmse": rmse,
            "test_r2": r2,
            "fit_seconds": elapsed,
        }
        if name == "hist_gbdt":
            np.save(RESULTS_DIR / "y_test.npy", y_test)
            np.save(RESULTS_DIR / "pred_hist_gbdt.npy", pred)

    y_std = float(np.std(y))
    results["_meta"] = {
        "n_train_pairs": int(train_mask.sum()),
        "n_test_pairs": int(test_mask.sum()),
        "n_mixed_pairs_unused": int(mixed_mask.sum()),
        "target_std": y_std,
        "target_mean": float(np.mean(y)),
    }
    with open(RESULTS_DIR / "metrics.json", "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nTarget std = {y_std:.3f} (for reference against RMSE)")
    print(f"Wrote results to {RESULTS_DIR}/metrics.json")


if __name__ == "__main__":
    main()
