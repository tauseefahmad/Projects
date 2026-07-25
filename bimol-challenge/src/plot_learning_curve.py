"""Standalone plot of the learning curve from results/learning_curve.json."""
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

RESULTS_DIR = Path(__file__).parent.parent / "results"


def main():
    with open(RESULTS_DIR / "learning_curve.json") as f:
        curve = json.load(f)

    sizes = np.array(curve["train_sizes"])
    mean = np.array(curve["mae_mean"])
    lo = mean - np.array(curve["mae_min"])
    hi = np.array(curve["mae_max"]) - mean

    fig, ax = plt.subplots(figsize=(5.2, 4.2))
    ax.errorbar(sizes, mean, yerr=[lo, hi], fmt="o-", color="C0", capsize=3)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("number of training pairs, N")
    ax.set_ylabel("test MAE (coupling energy)")
    ax.set_title("KRR (Laplacian kernel, supermolecule Coulomb matrix)")
    ax.grid(True, which="both", alpha=0.3)
    fig.tight_layout()
    fig.savefig(RESULTS_DIR / "learning_curve.png", dpi=180)
    print(f"Saved {RESULTS_DIR / 'learning_curve.png'}")
    print(f"gamma={curve['hyperparameters']['gamma']:.4g} "
          f"alpha={curve['hyperparameters']['alpha']:.4g} "
          f"cv_MAE={curve['hyperparameters']['cv_mae']:.4f}")
    for n, m in zip(sizes, mean):
        print(f"  N={n:6d}  MAE={m:.4f}")
    print(f"target_std={curve['target_std']:.3f}  n_test={curve['n_test']}")


if __name__ == "__main__":
    main()
