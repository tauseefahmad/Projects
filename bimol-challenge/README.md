# Bimolecular Coupling-Energy ML Challenge

Solution to Peter Zaspel's "Challenge in Bi-Molecular Machine Learning" (Bergische
Universität Wuppertal, HPC group).

## Official task (for reference)

> For pairs of 200 molecular geometries (200x200 pairs total), artificially generated
> excitonic coupling data is provided ... build and benchmark a bi-molecular machine
> learning model ... using Kernel Ridge Regression or Gaussian Process Regression. As
> representation for the molecular geometries, it is recommended to use Coulomb
> matrices ... generate a learning curve [log-log MAE vs. number of training samples,
> via (cross) validation] ... document everything in a one-page PDF (write-up + figures)
> followed by a concatenated listing of all source code (ideally an exported Jupyter
> notebook).

**Note on LLM usage**: this solution was developed with Claude Code (Anthropic). The
official instructions state that if LLMs are used, the *full prompt history* must be
submitted alongside the code as part of the application. If you (the user) submit this
work, remember to export/attach that conversation transcript — it isn't included in this
repo.

## Data

`BiMolData.zip` (referenced from the challenge page) contains:

- `Coord_A.xyz`, `Coord_B.xyz` — 200 conformers each of monomer A and B. Both are the
  same 15-atom molecule (C6H5NO3: 1 N, 3 O, 6 C, 5 H) in identical atom ordering across
  every conformer — a homo-dimer, i.e. coupling between two copies of one molecule
  sampled in different geometries.
- `Coord_supermol.xyz` (not committed here) — 40000 "supermolecule" geometries, one per
  (molA, molB) pair. **Verified** to be an exact concatenation
  `coords_supermol[k] = concat(Coord_A[molA(k)], Coord_B[molB(k)])`, zero numerical
  difference at every frame checked. Dropped from the repo to avoid a redundant 48 MB
  file; the Coulomb-matrix code reconstructs it on the fly from `Coord_A`/`Coord_B` plus
  the CSV pairing.
- `CouplingEnergies.csv` — 40000 rows of `molA, molB, coupling_energy`, a full 200x200
  grid (molA-major order).

## Approach

**Representation** (`src/coulomb.py`): a single standard Coulomb matrix (Rupp et al.,
*Phys. Rev. Lett.* **108**, 058301, 2012) for the 30-atom supermolecule formed by A and B
at their true relative geometry. This one representation naturally covers the
intra-A, intra-B, and (dominant) inter-monomer blocks of the interaction — no separate
scheme for combining two per-monomer representations is needed. Upper triangle (465
values) used as the feature vector; no permutation-invariance sorting is applied since
every conformer already shares identical atom ordering (verified in the notebook).

**Model** (`src/krr_model.py`): Kernel Ridge Regression with a Laplacian kernel
(`k(x,x') = exp(-||x-x'||_1/sigma)`), the classical Coulomb-matrix pairing. Hyperparameters
(`gamma=1/sigma`, ridge `alpha`) chosen by 5-fold cross-validated grid search on a
training-pool subset.

**Learning curve** (`src/learning_curve.py`): a fixed 10% held-out test set (4000 pairs),
never touched by hyperparameter search or training; MAE evaluated at training sizes N
from 50 to 12800 (log-spaced), with repeats at smaller N and fewer repeats at the largest,
most expensive sizes (KRR fitting is O(N^3)) to bound total compute.

## Results

| N (train pairs) | Test MAE |
|---:|---:|
| 50 | 13.09 |
| 100 | 11.31 |
| 200 | 9.06 |
| 400 | 7.09 |
| 800 | 4.35 |
| 1600 | 2.19 |
| 3200 | 0.93 |
| 6400 | 0.40 |
| 12800 | 0.175 |

Clean log-log power-law decay, `MAE ~ N^-0.79` (least-squares fit, R2=0.93). Target std is
38.4, so even N=50 already captures most of the variance; by N=12800 the error is ~0.5% of
the target spread. The challenge notes kernel models can reach MAE 0.01-0.1; the trend
here is consistent with reaching that range at somewhat larger N than explored (KRR's
O(N^3) cost is the limiting factor, not the representation/model choice — see
`writeup/writeup.html` and the notebook's Discussion section for details).

## Deliverables

- **`submission.pdf`** — the one-page write-up followed by the executed notebook
  (including the concatenated source-code appendix), matching the format the challenge
  asks for.
- `writeup/writeup.html` / `writeup/writeup.pdf` — the standalone one-page write-up.
- `notebook/solution.ipynb` / `notebook/solution.html` / `notebook/solution.pdf` — the
  full worked solution (executed).
- `results/` — `learning_curve.json` (raw numbers), `learning_curve.png`,
  `example_coulomb_matrix.png`.

## Reproducing

```
pip install numpy pandas scikit-learn scipy matplotlib pypdf \
            notebook nbconvert nbformat nbclient playwright
python src/learning_curve.py         # writes results/learning_curve.json (~10 min)
python src/plot_learning_curve.py    # writes results/learning_curve.png
python src/build_notebook.py         # (re)generates notebook/solution.ipynb
jupyter nbconvert --to notebook --execute --inplace notebook/solution.ipynb
jupyter nbconvert --to html notebook/solution.ipynb
python src/html_to_pdf.py "$PWD/writeup/writeup.html" "$PWD/writeup/writeup.pdf"
python src/html_to_pdf.py "$PWD/notebook/solution.html" "$PWD/notebook/solution.pdf"
```
(`html_to_pdf.py` drives Playwright/Chromium directly with an explicit executable path,
since `nbconvert`'s own `--to webpdf` browser auto-detection didn't work in this
environment.)

## Layout

```
bimol-challenge/
  data/                        Coord_A.xyz, Coord_B.xyz, CouplingEnergies.csv
  src/
    data_utils.py              xyz/csv loading
    coulomb.py                 Coulomb-matrix representation (supermolecule)
    krr_model.py                Laplacian-kernel KRR + hyperparameter search
    learning_curve.py           learning-curve protocol / driver script
    plot_learning_curve.py      standalone learning-curve figure
    build_notebook.py           generates notebook/solution.ipynb
    html_to_pdf.py              HTML -> PDF via Playwright/Chromium
    features.py, train.py, plot_parity.py   earlier exploratory pass (see below)
  notebook/solution.ipynb       full worked solution (executed notebook)
  writeup/writeup.html          one-page write-up
  results/                      learning_curve.json/png, example_coulomb_matrix.png
  submission.pdf                writeup + notebook, concatenated (the actual deliverable)
```

## Note on the earlier exploratory pass

Before the official challenge text was available in this session (the page was initially
blocked by network policy), an exploratory pass was built using ad hoc inverse-distance
features and tree-ensemble models (`src/features.py`, `src/train.py`,
`src/plot_parity.py`, results in `results/metrics.json` / `parity_hist_gbdt.png`). That
approach doesn't match the official spec (Coulomb matrix + KRR/GPR + learning curve) and
is kept only as a secondary reference point, not part of the submission.
