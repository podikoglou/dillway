"""Model definition and honest evaluation (TRAINING.md).

Algorithm: RandomForest on engineered features. Tree ensembles fit the
sample sizes typical of a small labeled dataset; revisit sequence models
when the dataset grows much larger.

Structure: one binary specialist per cheat (legit vs cheat-X), so legit
data is reused across specialists and each cheat tunes its own features
and thresholds. Multi-label detection works: a player running two cheats
is flagged by both models.

Evaluation rules: never split by window — split by player. GroupKFold is
the only reported score, and the production gate is false flags per hour
on legit data, not accuracy.
"""

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import GroupKFold

# Label index order used everywhere: 0=LEGIT, 1=CHEAT
LABEL_LEGIT = 0


def build_model() -> RandomForestClassifier:
    """The baseline specialist model (500 tree ensemble, sqrt features)."""
    return RandomForestClassifier(
        n_estimators=500,
        max_depth=None,
        min_samples_leaf=1,
        max_features="sqrt",
        random_state=42,
        n_jobs=-1,
    )


def fit_model(X: np.ndarray, y: np.ndarray) -> RandomForestClassifier:
    """Fit the specialist on all windows and return the fitted model."""
    model = build_model()
    model.fit(X, y)
    return model


def grouped_score(
    X: np.ndarray, y: np.ndarray, groups: list[str], window_s: float
) -> dict:
    """Honest out-of-fold score with player-grouped CV.

    Returns metrics the training run reports and stores in the ONNX-side
    manifest, including the production gate: false flags per legit hour
    (FP windows scaled by window size). Raises when there are fewer than
    2 players, since grouped CV is meaningless below that.
    """
    n_groups = len(set(groups))
    if n_groups < 2:
        raise RuntimeError(
            f"Need at least 2 distinct players to do grouped CV, got {n_groups}."
        )
    n_splits = min(5, n_groups)
    groups = np.asarray(groups)

    cm = np.zeros((2, 2), dtype=int)
    fold_accs: list[float] = []
    y_all = np.asarray(y)
    for train_idx, test_idx in GroupKFold(n_splits=n_splits).split(X, y, groups):
        fold_model = build_model()
        fold_model.fit(X[train_idx], y_all[train_idx])
        oof = fold_model.predict(X[test_idx])
        y_true = y_all[test_idx]

        for true_label in (0, 1):
            for pred_label in (0, 1):
                cm[true_label, pred_label] += int(
                    ((y_true == true_label) & (oof == pred_label)).sum()
                )
        fold_accs.append(float((oof == y_true).mean()))

    legit_mask = y_all == LABEL_LEGIT
    false_positives = int(cm[0, 1])
    legit_hours = legit_mask.sum() * window_s / 3600.0
    fp_per_legit_hour = false_positives / legit_hours if legit_hours > 0 else float("inf")
    cheat_mask = y_all != LABEL_LEGIT
    recall = (
        float((cm[1, 1]) / (cm[1, 0] + cm[1, 1]))
        if (cm[1, 0] + cm[1, 1]) > 0
        else float("nan")
    )

    return {
        "method": f"GroupKFold by player (n_splits={n_splits}, players={n_groups})",
        "accuracy": float(np.mean(fold_accs)),
        "accuracy_std": float(np.std(fold_accs)),
        "recall": recall,
        "confusion_matrix": cm.tolist(),
        "false_positives_per_legit_hour": fp_per_legit_hour,
        "legit_hours": legit_hours,
        "false_positives": false_positives,
    }


def print_metrics(metrics: dict) -> None:
    """Print the evaluation block the training run reports."""
    cm = np.array(metrics["confusion_matrix"])
    print(f"Validation: {metrics['method']}")
    print(f"  accuracy:                  {metrics['accuracy']:.4f}")
    print(f"  recall (cheat windows):    {metrics['recall']:.4f}")
    print(f"  false flags / legit hour:  {metrics['false_positives_per_legit_hour']:.2f}")
    print("  confusion matrix (rows=actual, cols=predicted) LEGIT, CHEAT:")
    print(f"    LEGIT  {cm[0, 0]:6d} {cm[0, 1]:6d}")
    print(f"    CHEAT  {cm[1, 0]:6d} {cm[1, 1]:6d}")
