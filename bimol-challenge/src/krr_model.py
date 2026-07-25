"""Kernel Ridge Regression on the supermolecule Coulomb-matrix representation.

Follows the standard Coulomb-matrix + Laplacian-kernel KRR recipe of
Rupp et al. (Phys. Rev. Lett. 108, 058301, 2012) and the accompanying
tutorial articles referenced by the challenge:

    k(x, x') = exp(-||x - x'||_1 / sigma)
    alpha = (K + lambda I)^-1 y

with hyperparameters (sigma, lambda) chosen by cross-validated grid search.
"""
from __future__ import annotations

import numpy as np
from sklearn.kernel_ridge import KernelRidge
from sklearn.metrics import mean_absolute_error
from sklearn.model_selection import GridSearchCV, KFold


def select_hyperparameters(X: np.ndarray, y: np.ndarray, seed: int = 0) -> dict:
    """Grid-search (sigma, lambda) for Laplacian-kernel KRR via 5-fold CV.

    Run on a modest-sized subset (a few thousand samples) since this is
    O(n^3) per fit x n_grid_points x n_folds; the resulting hyperparameters
    are then reused for the full learning curve.
    """
    # gamma = 1/sigma in sklearn's laplacian_kernel convention.
    param_grid = {
        "gamma": np.logspace(-4, -1, 6),
        "alpha": np.logspace(-6, -1, 6),
    }
    base = KernelRidge(kernel="laplacian")
    cv = KFold(n_splits=5, shuffle=True, random_state=seed)
    search = GridSearchCV(
        base, param_grid, scoring="neg_mean_absolute_error", cv=cv, n_jobs=-1
    )
    search.fit(X, y)
    return {"gamma": search.best_params_["gamma"], "alpha": search.best_params_["alpha"],
            "cv_mae": -search.best_score_}


def fit_and_eval(X_train: np.ndarray, y_train: np.ndarray, X_test: np.ndarray,
                  y_test: np.ndarray, gamma: float, alpha: float) -> float:
    model = KernelRidge(kernel="laplacian", gamma=gamma, alpha=alpha)
    model.fit(X_train, y_train)
    pred = model.predict(X_test)
    return mean_absolute_error(y_test, pred)
