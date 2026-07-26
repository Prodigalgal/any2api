from statistics import fmean

from .models import Consensus, SolverEstimate


def fuse_offsets(
    estimates: list[SolverEstimate],
    maximum_spread: float = 15.0,
    minimum_votes: int = 2,
) -> tuple[float | None, float, Consensus]:
    numeric = [item for item in estimates if isinstance(item.value, (int, float))]
    if not numeric:
        return None, 0.0, Consensus(accepted=False, votes=0)

    best_cluster: list[SolverEstimate] = []
    for pivot in numeric:
        cluster = [
            item
            for item in numeric
            if abs(float(item.value) - float(pivot.value)) <= maximum_spread
        ]
        if sum(item.confidence for item in cluster) > sum(item.confidence for item in best_cluster):
            best_cluster = cluster

    weights = [max(item.confidence, 0.05) for item in best_cluster]
    value = sum(float(item.value) * weight for item, weight in zip(best_cluster, weights)) / sum(
        weights
    )
    spread = max(float(item.value) for item in best_cluster) - min(
        float(item.value) for item in best_cluster
    )
    confidence = min(1.0, fmean(item.confidence for item in best_cluster))
    accepted = len(best_cluster) >= minimum_votes and spread <= maximum_spread
    return value, confidence, Consensus(accepted=accepted, spread=spread, votes=len(best_cluster))


def fuse_text(estimates: list[SolverEstimate]) -> tuple[str | None, float, Consensus]:
    votes: dict[str, list[SolverEstimate]] = {}
    for estimate in estimates:
        if isinstance(estimate.value, str) and estimate.value:
            votes.setdefault(estimate.value.strip().upper(), []).append(estimate)
    if not votes:
        return None, 0.0, Consensus(accepted=False, votes=0)
    text, matches = max(
        votes.items(), key=lambda item: (len(item[1]), sum(x.confidence for x in item[1]))
    )
    confidence = min(1.0, fmean(item.confidence for item in matches))
    return text, confidence, Consensus(accepted=True, votes=len(matches))
