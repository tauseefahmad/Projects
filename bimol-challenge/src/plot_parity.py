"""Generate a parity (predicted vs. actual) plot for the best model."""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

RESULTS_DIR = Path(__file__).parent.parent / "results"


def main():
    y_test = np.load(RESULTS_DIR / "y_test.npy")
    pred = np.load(RESULTS_DIR / "pred_hist_gbdt.npy")

    lo = min(y_test.min(), pred.min())
    hi = max(y_test.max(), pred.max())

    fig, ax = plt.subplots(figsize=(5, 5))
    ax.scatter(y_test, pred, s=8, alpha=0.5, edgecolors="none")
    ax.plot([lo, hi], [lo, hi], "k--", linewidth=1)
    ax.set_xlabel("True coupling energy")
    ax.set_ylabel("Predicted coupling energy")
    ax.set_title("HistGradientBoosting: held-out conformer pairs")
    fig.tight_layout()
    fig.savefig(RESULTS_DIR / "parity_hist_gbdt.png", dpi=150)
    print(f"Saved {RESULTS_DIR / 'parity_hist_gbdt.png'}")


if __name__ == "__main__":
    main()
