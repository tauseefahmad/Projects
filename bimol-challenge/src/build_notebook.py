"""Assemble the submission notebook (solution.ipynb) from source.

Run this, then execute + export separately:
    python src/build_notebook.py
    jupyter nbconvert --to notebook --execute --inplace notebook/solution.ipynb
"""
from pathlib import Path

import nbformat as nbf

ROOT = Path(__file__).parent.parent
SRC = ROOT / "src"
NB_DIR = ROOT / "notebook"


def md(text):
    return nbf.v4.new_markdown_cell(text.strip("\n"))


def code(text):
    return nbf.v4.new_code_cell(text.strip("\n"))


def main():
    NB_DIR.mkdir(exist_ok=True)
    nb = nbf.v4.new_notebook()
    cells = []

    cells.append(md("""
# Bi-Molecular Coupling-Energy Challenge: Kernel Ridge Regression on Coulomb Matrices

**Task**: predict `coupling_energy` for a pair of molecular conformers (A, B) from their
3D geometries. Dataset: 200 conformers each of monomer A and B (same 15-atom molecule,
C6H5NO3), coupling energies for the full 200x200 = 40000 pair grid.

This notebook implements the full pipeline: representation (Coulomb matrix of the
"supermolecule"), model (Kernel Ridge Regression with a Laplacian kernel, the classical
Rupp et al. 2012 recipe), hyperparameter selection by cross-validation, and the
learning-curve evaluation requested by the challenge. A concatenated listing of all
source files is included as an appendix at the end.
"""))

    cells.append(code("""
import sys
sys.path.insert(0, "../src")
import json
import time

import numpy as np
import matplotlib.pyplot as plt

from data_utils import load_dataset
from coulomb import atomic_numbers, build_supermol_coulomb_features, coulomb_matrix_batch
from krr_model import select_hyperparameters, fit_and_eval

SEED = 0
rng = np.random.RandomState(SEED)

d = load_dataset("../data")
print("A conformers:", d["coords_a"].shape, " B conformers:", d["coords_b"].shape)
print("pairs:", d["energies"].shape)
d["energies"].head()
"""))

    cells.append(md("""
## 1. Representation: one Coulomb matrix per pair, on the "supermolecule"

The bi-molecular difficulty of this task is that the target depends on the shape of A,
the shape of B, *and* their relative arrangement. Rather than hand-crafting a way to
combine two separate per-monomer representations, we build a single Coulomb matrix
(Rupp et al., *Phys. Rev. Lett.* **108**, 058301, 2012) for the 30-atom "supermolecule"
obtained by placing A and B at their true relative geometry -- exactly what
`Coord_supermol.xyz` encodes (verified to be the literal concatenation of the two
monomer geometries, atom-for-atom, with zero numerical difference).

    M_ii = 0.5 * Z_i^2.4                    (diagonal)
    M_ij = Z_i * Z_j / |R_i - R_j|          (off-diagonal)

This single 30x30 matrix contains three physically meaningful blocks:

- top-left 15x15: intra-monomer-A structure (how A's own conformation is distorted)
- bottom-right 15x15: intra-monomer-B structure
- off-diagonal 15x15 (and its transpose): the **inter-monomer** geometry, which is the
  dominant driver of an excitonic/Coulombic coupling energy.

We take the upper triangle (including the diagonal) as a 465-dimensional feature vector.
Standard practice additionally sorts rows/columns by descending L2-norm to make the
representation invariant to atom permutation. We skip that here: every conformer of A
(and of B) is given in exactly the same atom order (verified below), so there is no
permutation ambiguity to remove, and skipping the sort keeps each feature index tied to
the same physical atom pair across every sample -- strictly more informative for this
fixed-composition dataset.
"""))

    cells.append(code("""
# Confirm atom ordering is identical across all conformers (so no CM row/col sorting
# is needed for permutation invariance).
same_order_a = all(e == d["elems_a"][0] for e in d["elems_a"])
same_order_b = all(e == d["elems_b"][0] for e in d["elems_b"])
print("A atom order constant across conformers:", same_order_a)
print("B atom order constant across conformers:", same_order_b)
print(d["elems_a"][0])

z_a = atomic_numbers(d["elems_a"][0])
z_b = atomic_numbers(d["elems_b"][0])
"""))

    cells.append(code("""
# Visualize one supermolecule Coulomb matrix to build intuition for the 3 blocks.
example_cm = coulomb_matrix_batch(
    np.concatenate([d["coords_a"][0:1], d["coords_b"][0:1]], axis=1),
    np.concatenate([z_a, z_b]),
)[0]

fig, ax = plt.subplots(figsize=(5, 4.5))
im = ax.imshow(np.log1p(example_cm), cmap="viridis")
ax.axhline(14.5, color="white", linewidth=1)
ax.axvline(14.5, color="white", linewidth=1)
ax.set_title("log(1+Coulomb matrix), supermolecule (A[0], B[0])")
ax.set_xlabel("atom index (0-14: A, 15-29: B)")
ax.set_ylabel("atom index")
fig.colorbar(im, ax=ax, label="log(1 + M_ij)")
fig.tight_layout()
fig.savefig("../results/example_coulomb_matrix.png", dpi=150)
plt.show()
"""))

    cells.append(code("""
mol_a = d["energies"]["molA"].to_numpy()
mol_b = d["energies"]["molB"].to_numpy()
y = d["energies"]["coupling_energy"].to_numpy()

t0 = time.time()
X = build_supermol_coulomb_features(d["coords_a"], d["coords_b"], z_a, z_b, mol_a, mol_b)
print(f"X shape {X.shape}, built in {time.time()-t0:.1f}s")
print(f"target: mean={y.mean():.3f} std={y.std():.3f} min={y.min():.3f} max={y.max():.3f}")
"""))

    cells.append(md("""
## 2. Model: Kernel Ridge Regression with a Laplacian kernel

We use `k(x, x') = exp(-||x - x'||_1 / sigma)` with ridge regularizer `lambda`, the
combination originally shown (Rupp et al. 2012) to work well with Coulomb-matrix
features for molecular-property regression. Hyperparameters `(gamma=1/sigma, lambda)`
are selected by 5-fold cross-validated grid search over log-spaced grids.

**Validation protocol**: a fixed 10% test set (4000 pairs) is held out up front and
never touched by hyperparameter selection or training; all cross-validation happens
strictly within the remaining 90% training pool.
"""))

    cells.append(code("""
n_total = X.shape[0]
perm = rng.permutation(n_total)
n_test = n_total // 10
test_idx = perm[:n_test]
pool_idx = perm[n_test:]
X_test, y_test = X[test_idx], y[test_idx]
print(f"held-out test set: {n_test} pairs; training pool: {len(pool_idx)} pairs")
"""))

    cells.append(code("""
# Hyperparameter search on a moderate subset of the training pool (fast: O(n^3) per fit).
# NOTE: the full report below uses hyperparameters selected the same way on 2000 samples
# by scripts/learning_curve.py (run offline); we repeat a smaller version live here
# (1200 samples) purely to demonstrate the procedure end-to-end within this notebook.
hp_idx_demo = rng.choice(pool_idx, size=1200, replace=False)
t0 = time.time()
hp_demo = select_hyperparameters(X[hp_idx_demo], y[hp_idx_demo], seed=SEED)
print(f"[live demo, n=1200] gamma={hp_demo['gamma']:.4g} alpha={hp_demo['alpha']:.4g} "
      f"cv_MAE={hp_demo['cv_mae']:.4f}  ({time.time()-t0:.1f}s)")
"""))

    cells.append(md("""
## 3. Learning curve

Protocol: for each training-set size `N` in a log-spaced sequence, draw `n_repeats`
random training subsets of size `N` from the pool, fit KRR (fixed hyperparameters from
above), and evaluate MAE against the fixed held-out test set. We plot mean MAE
(min/max across repeats) vs. `N` on log-log axes -- the standard presentation for
molecular-ML learning curves, where MAE typically follows a power law `MAE ~ N^-alpha`.

Fitting KRR at the largest sizes considered (N up to 12800) costs O(N^3) and is too
slow to repeat live in this notebook; a live demonstration up to N=1600 is run below,
followed by the full-scale curve (up to N=12800), computed offline by
`src/learning_curve.py` using hyperparameters selected the same way (5-fold CV on a
2000-sample subset) and loaded here from `results/learning_curve.json`.
"""))

    cells.append(code("""
# Live demonstration, small scale.
demo_sizes = [50, 100, 200, 400, 800, 1600]
demo_mae = []
for n in demo_sizes:
    train_idx = rng.choice(pool_idx, size=n, replace=False)
    mae = fit_and_eval(X[train_idx], y[train_idx], X_test, y_test,
                        gamma=hp_demo["gamma"], alpha=hp_demo["alpha"])
    demo_mae.append(mae)
    print(f"N={n:5d}  MAE={mae:.4f}")
"""))

    cells.append(code("""
with open("../results/learning_curve.json") as f:
    curve = json.load(f)

fig, ax = plt.subplots(figsize=(5.5, 4.5))
ax.plot(demo_sizes, demo_mae, "o--", color="gray", alpha=0.6,
        label="live demo (this notebook, n<=1600)")
ax.errorbar(curve["train_sizes"], curve["mae_mean"],
            yerr=[np.array(curve["mae_mean"]) - np.array(curve["mae_min"]),
                  np.array(curve["mae_max"]) - np.array(curve["mae_mean"])],
            fmt="o-", color="C0", capsize=3,
            label=f"full run (offline, gamma={curve['hyperparameters']['gamma']:.3g}, "
                  f"lambda={curve['hyperparameters']['alpha']:.3g})")
ax.set_xscale("log")
ax.set_yscale("log")
ax.set_xlabel("number of training pairs, N")
ax.set_ylabel("test MAE")
ax.set_title("Learning curve: KRR on supermolecule Coulomb matrix")
ax.legend(fontsize=8)
ax.grid(True, which="both", alpha=0.3)
fig.tight_layout()
fig.savefig("../results/learning_curve.png", dpi=150)
plt.show()

print(f"Best MAE at N={curve['train_sizes'][-1]}: {curve['mae_mean'][-1]:.4f} "
      f"(target std = {curve['target_std']:.3f})")
"""))

    cells.append(md("""
## 4. Discussion

- The learning curve follows the expected power-law decay of test MAE with training-set
  size, characteristic of kernel-based molecular-property regression.
- Using the *supermolecule* Coulomb matrix (rather than, say, concatenating two separate
  per-monomer Coulomb-matrix vectors) is what lets a single, standard KRR model see the
  inter-monomer geometry directly, which is the dominant driver of a coupling energy --
  no special "bi-molecular" model architecture is required once the representation
  itself encodes the pair as one system.
- Hyperparameters were selected once (via 5-fold CV on a training-pool subset) and held
  fixed across the learning curve, rather than re-tuned at every point, to keep the
  total compute budget reasonable; in principle the optimal regularizer `lambda`
  decreases somewhat as `N` grows, so re-tuning per point would be expected to give a
  marginally lower curve at large `N`.
- Limitation: KRR training is O(N^3) in time and O(N^2) in memory, which caps the
  largest training size explored here (N=12800) well below the full 36000-pair pool;
  a Nystrom/random-features approximation or an inducing-point Gaussian Process would
  be the natural next step to push further.

## Appendix: concatenated source code listing
"""))

    appendix_files = ["data_utils.py", "coulomb.py", "krr_model.py", "learning_curve.py"]
    for fname in appendix_files:
        cells.append(md(f"### `src/{fname}`"))
        cells.append(code(f'print(open("../src/{fname}").read())'))

    nb["cells"] = cells
    nb["metadata"] = {
        "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
        "language_info": {"name": "python", "version": "3.11"},
    }
    out_path = NB_DIR / "solution.ipynb"
    nbf.write(nb, out_path)
    print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
