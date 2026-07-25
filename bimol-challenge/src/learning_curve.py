"""Build the learning curve: log-log MAE vs. number of training pairs.

Protocol:
  1. Hold out a fixed test set (10% of all 40000 pairs), never used for
     training or hyperparameter selection.
  2. Select Laplacian-kernel KRR hyperparameters (sigma via gamma, and the
     ridge regularizer lambda) by 5-fold cross-validated grid search on a
     representative-sized subset of the remaining training pool.
  3. For each training-set size N in a log-spaced sequence, draw `n_repeats`
     random training subsets of that size from the pool, fit KRR with the
     hyperparameters from step 2, and evaluate MAE on the fixed test set.
  4. Plot mean MAE (with min/max across repeats) vs. N on log-log axes.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from coulomb import atomic_numbers, build_supermol_coulomb_features
from data_utils import load_dataset
from krr_model import fit_and_eval, select_hyperparameters

DATA_DIR = Path(__file__).parent.parent / "data"
RESULTS_DIR = Path(__file__).parent.parent / "results"
SEED = 0
TRAIN_SIZES = [50, 100, 200, 400, 800, 1600, 3200, 6400, 12800]
# KRR fitting is O(n^3), so use fewer repeats at the largest, most expensive sizes
# to keep total learning-curve compute bounded.
N_REPEATS_BY_SIZE = {50: 3, 100: 3, 200: 3, 400: 3, 800: 3, 1600: 3, 3200: 3,
                      6400: 2, 12800: 1}
HYPERPARAM_SEARCH_N = 2000


def main():
    RESULTS_DIR.mkdir(exist_ok=True)
    rng = np.random.RandomState(SEED)

    print("Loading dataset and building Coulomb-matrix features...")
    d = load_dataset(str(DATA_DIR))
    z_a = atomic_numbers(d["elems_a"][0])
    z_b = atomic_numbers(d["elems_b"][0])
    mol_a = d["energies"]["molA"].to_numpy()
    mol_b = d["energies"]["molB"].to_numpy()
    y = d["energies"]["coupling_energy"].to_numpy()

    t0 = time.time()
    X = build_supermol_coulomb_features(d["coords_a"], d["coords_b"], z_a, z_b, mol_a, mol_b)
    print(f"  X shape {X.shape} ({time.time() - t0:.1f}s)")

    n_total = X.shape[0]
    perm = rng.permutation(n_total)
    n_test = n_total // 10
    test_idx = perm[:n_test]
    pool_idx = perm[n_test:]
    X_test, y_test = X[test_idx], y[test_idx]
    print(f"held-out test set: {n_test} pairs; training pool: {len(pool_idx)} pairs")

    print("Selecting hyperparameters via 5-fold CV grid search "
          f"on {HYPERPARAM_SEARCH_N} training-pool samples...")
    hp_idx = rng.choice(pool_idx, size=HYPERPARAM_SEARCH_N, replace=False)
    t0 = time.time()
    hp = select_hyperparameters(X[hp_idx], y[hp_idx], seed=SEED)
    print(f"  chosen gamma={hp['gamma']:.4g}, alpha={hp['alpha']:.4g}, "
          f"cv_MAE={hp['cv_mae']:.4f} ({time.time() - t0:.1f}s)")

    curve = {"train_sizes": [], "mae_mean": [], "mae_min": [], "mae_max": [], "mae_all": []}
    for n in TRAIN_SIZES:
        if n > len(pool_idx):
            continue
        maes = []
        for r in range(N_REPEATS_BY_SIZE[n]):
            sub_rng = np.random.RandomState(SEED * 1000 + r)
            train_idx = sub_rng.choice(pool_idx, size=n, replace=False)
            t0 = time.time()
            mae = fit_and_eval(X[train_idx], y[train_idx], X_test, y_test,
                                gamma=hp["gamma"], alpha=hp["alpha"])
            maes.append(mae)
            print(f"  N={n:6d} repeat={r} MAE={mae:.4f} ({time.time() - t0:.1f}s)")
        curve["train_sizes"].append(n)
        curve["mae_mean"].append(float(np.mean(maes)))
        curve["mae_min"].append(float(np.min(maes)))
        curve["mae_max"].append(float(np.max(maes)))
        curve["mae_all"].append([float(m) for m in maes])

    curve["hyperparameters"] = hp
    curve["n_test"] = int(n_test)
    curve["target_std"] = float(np.std(y))
    with open(RESULTS_DIR / "learning_curve.json", "w") as f:
        json.dump(curve, f, indent=2)
    print(f"Wrote {RESULTS_DIR / 'learning_curve.json'}")


if __name__ == "__main__":
    main()
