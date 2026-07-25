"""Utilities for loading the BiMol coupling-energy dataset.

Dataset layout (from BiMolData.zip):
  Coord_A.xyz         200 conformers of monomer A (15 atoms each)
  Coord_B.xyz         200 conformers of monomer B (15 atoms each)
  Coord_supermol.xyz  40000 "supermolecule" geometries (30 atoms each),
                       one for every (molA, molB) pair in CouplingEnergies.csv
  CouplingEnergies.csv  columns: molA, molB, coupling_energy (40000 rows)
"""
from __future__ import annotations

import numpy as np
import pandas as pd


def read_xyz_multi(path: str) -> tuple[list[list[str]], np.ndarray]:
    """Read a multi-frame .xyz file.

    Returns (elements_per_frame, coords) where coords has shape
    (n_frames, n_atoms, 3). Assumes every frame has the same atom count.
    """
    elements_all = []
    coords_all = []
    with open(path) as f:
        lines = f.readlines()

    i = 0
    n_lines = len(lines)
    while i < n_lines:
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        n_atoms = int(line)
        # lines[i+1] is the comment/"Geometry k of N" line
        elements = []
        coords = np.empty((n_atoms, 3), dtype=np.float64)
        for a in range(n_atoms):
            parts = lines[i + 2 + a].split()
            elements.append(parts[0])
            coords[a] = [float(x) for x in parts[1:4]]
        elements_all.append(elements)
        coords_all.append(coords)
        i += 2 + n_atoms

    return elements_all, np.stack(coords_all)


def load_dataset(data_dir: str):
    """Load monomer geometries and coupling energy labels.

    Returns a dict with:
      elems_a, coords_a: 200 x 15 x 3
      elems_b, coords_b: 200 x 15 x 3
      energies: DataFrame with molA, molB, coupling_energy
    """
    elems_a, coords_a = read_xyz_multi(f"{data_dir}/Coord_A.xyz")
    elems_b, coords_b = read_xyz_multi(f"{data_dir}/Coord_B.xyz")
    energies = pd.read_csv(f"{data_dir}/CouplingEnergies.csv")
    return {
        "elems_a": elems_a,
        "coords_a": coords_a,
        "elems_b": elems_b,
        "coords_b": coords_b,
        "energies": energies,
    }
