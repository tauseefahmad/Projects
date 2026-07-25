"""Coulomb-matrix representation (Rupp et al. 2012) for the supermolecule.

The Coulomb matrix of an N-atom system is the N x N matrix

    M_ii = 0.5 * Z_i ** 2.4                     (diagonal: free-atom energy fit)
    M_ij = Z_i * Z_j / |R_i - R_j|   (i != j)    (off-diagonal: nuclear repulsion)

For the bi-molecular task, we build ONE Coulomb matrix per pair, for the
30-atom "supermolecule" formed by concatenating monomer A's 15 atoms with
monomer B's 15 atoms at their true relative geometry (exactly what
Coord_supermol.xyz stores). This single representation naturally covers:
  - the A-A block (top-left 15x15): intra-monomer-A structure
  - the B-B block (bottom-right 15x15): intra-monomer-B structure
  - the A-B block (off-diagonal 15x15, appearing twice by symmetry):
    the inter-monomer coupling geometry, which is the dominant physical
    driver of an excitonic/Coulombic coupling energy.

We take the upper triangle (including the diagonal) as the feature vector:
N=30 -> N*(N+1)/2 = 465 features.

Standard practice (Rupp et al.) sorts rows/columns by descending L2 row
norm to make the representation invariant to atom permutation/labeling.
We do NOT sort here: every conformer of A (and of B) shares the exact same
atom ordering (verified in data_utils/exploration), so there is no
permutation ambiguity to remove, and skipping the sort keeps the same
feature index tied to the same physical atom pair across all samples,
which is a strictly more informative (never less) representation for a
fixed-composition, fixed-connectivity dataset like this one.
"""
from __future__ import annotations

import numpy as np

ATOMIC_NUMBER = {"H": 1, "C": 6, "N": 7, "O": 8, "F": 9, "S": 16, "Cl": 17}


def atomic_numbers(elements: list[str]) -> np.ndarray:
    return np.array([ATOMIC_NUMBER[e] for e in elements], dtype=np.float64)


def coulomb_matrix_batch(coords: np.ndarray, z: np.ndarray) -> np.ndarray:
    """Coulomb matrices for a batch of same-composition geometries.

    coords: (n_samples, n_atoms, 3)
    z: (n_atoms,) nuclear charges, shared across the batch
    returns: (n_samples, n_atoms, n_atoms)
    """
    diff = coords[:, :, None, :] - coords[:, None, :, :]
    dist = np.linalg.norm(diff, axis=-1)  # (n_samples, n_atoms, n_atoms)

    zz = z[:, None] * z[None, :]  # (n_atoms, n_atoms)
    with np.errstate(divide="ignore"):
        off_diag = zz[None, :, :] / dist
    n_atoms = z.shape[0]
    diag_val = 0.5 * z ** 2.4
    idx = np.arange(n_atoms)
    off_diag[:, idx, idx] = diag_val[None, :]
    return off_diag


def upper_triangle_features(matrices: np.ndarray) -> np.ndarray:
    """Flatten the upper triangle (incl. diagonal) of a batch of square matrices."""
    n = matrices.shape[-1]
    iu = np.triu_indices(n)
    return matrices[:, iu[0], iu[1]]


def build_supermol_coulomb_features(coords_a: np.ndarray, coords_b: np.ndarray,
                                     z_a: np.ndarray, z_b: np.ndarray,
                                     mol_a_idx: np.ndarray, mol_b_idx: np.ndarray) -> np.ndarray:
    """Coulomb-matrix feature vectors for a set of (molA, molB) pairs.

    coords_a, coords_b: (200, 15, 3) monomer conformer geometries
    z_a, z_b: (15,) nuclear charges (identical composition/order for both)
    mol_a_idx, mol_b_idx: (n_pairs,) integer indices into coords_a / coords_b
    """
    n_pairs = mol_a_idx.shape[0]
    ca = coords_a[mol_a_idx]
    cb = coords_b[mol_b_idx]
    supermol = np.concatenate([ca, cb], axis=1)  # (n_pairs, 30, 3)
    z = np.concatenate([z_a, z_b])  # (30,)
    cm = coulomb_matrix_batch(supermol, z)  # (n_pairs, 30, 30)
    return upper_triangle_features(cm)
