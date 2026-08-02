package com.any2api.provider.deepseek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DeepseekPowSolverTest {
    private final DeepseekPowSolver solver = new DeepseekPowSolver();

    @Test
    void solvesOfficialHashV1SearchContract() {
        var challenge = new DeepseekPowSolver.Challenge(
            "DeepSeekHashV1",
            "5025435d13a52ce7c3198ea05075889760409b8a4e1ce57187344b82e7561c9f",
            "0123456789abcdef",
            "signature",
            100,
            1_893_456_000_000L,
            300_000,
            "/api/v0/chat/completion");

        assertThat(solver.solve(challenge)).isEqualTo(37);
    }

    @Test
    void matchesSanitizedOfficialBrowserFixture() {
        var prefix = "84226691b35fac66c866_1785338327707_"
            .getBytes(StandardCharsets.UTF_8);
        var target = HexFormat.of().parseHex(
            "34faae2603ea238bb11d85042a19e8d4e34582f0550733fb7e5062e85a749260");

        assertThat(DeepseekHashV1.matches(prefix, 122_014, target)).isTrue();
        assertThat(DeepseekHashV1.matches(prefix, 122_013, target)).isFalse();
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
