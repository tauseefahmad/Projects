# Bimolecular Coupling-Energy ML Challenge

An ML pipeline for the dataset from Peter Zaspel's "Challenge in Bi-Molecular
Machine Learning" (Bergische Universität Wuppertal, HPC group).

## A note on the source

The challenge webpage
(`hpc.uni-wuppertal.de/de/peter-zaspel/challenge-in-bi-molecular-machine-learning/`)
is blocked by this environment's network egress policy (confirmed 403 policy
denial at the proxy), so this pipeline was **not** built against the site's
official task statement, evaluation metric, or submission format. Everything
below was derived from the provided data (`BiMolData.zip`) and general
practice for this kind of quantum-chemistry ML problem. If the official
protocol differs (e.g. a specific train/test split or metric), swap the
split/metric in `src/train.py` accordingly.

## Data

`BiMolData.zip` contains:

- `Coord_A.xyz`, `Coord_B.xyz` — 200 conformers each of monomers A and B.
  Both are the same 15-atom molecule (formula C6H5NO3: 1 N, 3 O, 6 C, 5 H)
  in the same atom ordering across all conformers — this is a homo-dimer,
  i.e. coupling between two copies of one molecule sampled in different
  geometries.
- `Coord_supermol.xyz` (not committed here) — 40000 "supermolecule"
  geometries, one per (molA, molB) pair. **Verified** to be an exact
  concatenation `coords_supermol[k] = concat(Coord_A[molA(k)],
  Coord_B[molB(k)])` with zero numerical difference at every frame checked
  (including boundary frames), so it carries no information beyond
  `Coord_A.xyz` + `Coord_B.xyz` + the CSV pairing. Dropped from the repo to
  avoid committing a redundant 48 MB file; regenerate it if literally needed
  by concatenating the monomer frames in CSV row order.
- `CouplingEnergies.csv` — 40000 rows of `molA, molB, coupling_energy`,
  a full 200×200 grid (molA-major order) giving the coupling energy for
  every pair of conformers.

## Task

Predict `coupling_energy` for a (molA, molB) conformer pair from the two
monomers' 3D geometries, without access to the label.

## Approach

**Features** (`src/features.py`), built per pair from raw atomic coordinates:

- Cross block: inverse distances between every atom of A and every atom of B
  (15×15 = 225 features). This is the dominant physical signal — Coulombic/
  exciton-type coupling falls off with inter-monomer atomic distance.
- Intra blocks: inverse distances between atom pairs within A, and within B
  (15·14/2 = 105 features each), encoding how each monomer's own conformation
  (bond/angle/torsion distortion) affects the coupling.

Atom identity doesn't need explicit encoding because every conformer shares
the same atom order, so a given feature index always refers to the same pair
of atom types.

**Train/test split** (`src/train.py`): a conformer-level 80/20 split, not a
random split of the 40000 pairs. 160 conformers of each monomer are marked
"train", 40 "test". Train pairs use two train-conformers, test pairs use two
test-conformers; pairs mixing a train and a test conformer are dropped from
both sets. This is a stricter and more realistic generalization test than a
random pair split, since a random split would let the model see every
individual conformer repeatedly during training (just paired with a
different partner), which inflates apparent accuracy.

**Models compared**: Ridge regression (linear baseline), Random Forest, and
HistGradientBoosting (`scikit-learn`).

## Results

Evaluated on 1600 fully-held-out test pairs (both conformers unseen during
training); target std = 38.4:

| Model | Train MAE | Test MAE | Test RMSE | Test R² | Fit time |
|---|---|---|---|---|---|
| Ridge | 4.65 | 18.14 | 23.94 | 0.591 | 0.2 s |
| Random Forest (300 trees) | 0.97 | 11.76 | 15.90 | 0.820 | 619 s |
| **HistGradientBoosting** | 0.69 | **11.32** | **15.31** | **0.833** | 15 s |

HistGradientBoosting is both the most accurate and by far the fastest to
train. See `results/parity_hist_gbdt.png` for a predicted-vs-actual plot on
the test set, and `results/metrics.json` for the raw numbers.

## Reproducing

```
pip install numpy pandas scikit-learn scipy matplotlib
python src/train.py          # trains all 3 models, writes results/metrics.json
python src/plot_parity.py    # writes results/parity_hist_gbdt.png
```

## Layout

```
bimol-challenge/
  data/                    Coord_A.xyz, Coord_B.xyz, CouplingEnergies.csv
  src/
    data_utils.py          xyz/csv loading
    features.py            inverse-distance feature engineering
    train.py               conformer-split train/eval of 3 models
    plot_parity.py          parity plot for the best model
  results/
    metrics.json
    parity_hist_gbdt.png
```
