package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeepseekPowSolverTest {
    private final DeepseekPowSolver solver = new DeepseekPowSolver();

    @Test
    void solvesOfficialSha3SearchContract() {
        var challenge = new DeepseekPowSolver.Challenge(
            "DeepSeekHashV1",
            "b6d5d6627557a707fe41c8c29883e9f2f58553cf9eea5093bfb94d3ae4ae25bf",
            "0123456789abcdef",
            "signature",
            100,
            1_893_456_000_000L,
            300_000,
            "/api/v0/chat/completion");

        assertThat(solver.solve(challenge)).isEqualTo(37);
    }

    @Test
    void rejectsUnknownAlgorithmsBeforeSearching() {
        var challenge = new DeepseekPowSolver.Challenge(
            "unknown", "0".repeat(64), "salt", "signature",
            10, 1_893_456_000_000L, 300_000, "/completion");

        assertThatThrownBy(() -> solver.solve(challenge))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("algorithm");
    }
}
