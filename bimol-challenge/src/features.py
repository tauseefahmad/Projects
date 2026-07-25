"""Feature engineering for the bimolecular coupling-energy task.

A and B are two copies of the same 15-atom molecule (same atom order in
every conformer). For a pair (conformer i of A, conformer j of B) we build:

  - cross block: inverse distances between every atom of A and every atom
    of B (15*15 = 225 features). This is the dominant physical signal,
    since Coulombic/exciton coupling falls off with inter-monomer distance.
  - intra blocks: inverse distances between atom pairs within A, and
    within B (15*14/2 = 105 features each), which encode how each
    monomer's own conformation (bond lengths/angles/torsions) has
    distorted from equilibrium.

Atom identity is implicit in feature position since every conformer shares
the same atom ordering, so no atom-type encoding is needed.
"""
from __future__ import annotations

import numpy as np


def pairwise_inverse_distances(coords: np.ndarray) -> np.ndarray:
    """Inverse distances between all atom pairs within each conformer.

    coords: (n_conf, n_atoms, 3)
    returns: (n_conf, n_pairs) with n_pairs = n_atoms*(n_atoms-1)/2
    """
    n_conf, n_atoms, _ = coords.shape
    iu = np.triu_indices(n_atoms, k=1)
    diff = coords[:, iu[0], :] - coords[:, iu[1], :]
    dist = np.linalg.norm(diff, axis=-1)
    return 1.0 / dist


def cross_inverse_distances(coords_a: np.ndarray, coords_b: np.ndarray) -> np.ndarray:
    """Inverse distances between every atom of A and every atom of B, per pair.

    coords_a: (n_pairs, n_atoms, 3) -- already indexed/aligned per pair
    coords_b: (n_pairs, n_atoms, 3)
    returns: (n_pairs, n_atoms*n_atoms)
    """
    # (n_pairs, n_atoms_a, 1, 3) - (n_pairs, 1, n_atoms_b, 3)
    diff = coords_a[:, :, None, :] - coords_b[:, None, :, :]
    dist = np.linalg.norm(diff, axis=-1)  # (n_pairs, n_atoms_a, n_atoms_b)
    n_pairs = dist.shape[0]
    return (1.0 / dist).reshape(n_pairs, -1)


def build_features(coords_a: np.ndarray, coords_b: np.ndarray, mol_a_idx: np.ndarray,
                    mol_b_idx: np.ndarray) -> np.ndarray:
    """Build the full feature matrix for a set of (molA, molB) index pairs.

    coords_a, coords_b: (200, 15, 3) monomer conformer geometries
    mol_a_idx, mol_b_idx: (n_pairs,) integer indices into coords_a / coords_b
    """
    # Precompute intra-monomer features once per unique conformer, then index.
    intra_a_all = pairwise_inverse_distances(coords_a)  # (200, 105)
    intra_b_all = pairwise_inverse_distances(coords_b)  # (200, 105)

    intra_a = intra_a_all[mol_a_idx]
    intra_b = intra_b_all[mol_b_idx]

    cross = cross_inverse_distances(coords_a[mol_a_idx], coords_b[mol_b_idx])  # (n_pairs, 225)

    return np.concatenate([cross, intra_a, intra_b], axis=1)
