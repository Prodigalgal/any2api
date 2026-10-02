package com.any2api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.any2api.interop.InteropDatabase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RuntimeRuleBatchIntegrationTest {
    @Test
    void batchKeepsOldActiveCandidateAndLatestFiftyRevisions() throws Exception {
        try (var database = new InteropDatabase()) {
            database.jdbc.sql("""
                INSERT INTO provider_runtime_rule_revisions(
                    provider_id, revision, schema_version, rules, checksum)
                SELECT 'mimo', sequence.revision, source.schema_version, source.rules, source.checksum
                FROM generate_series(2,65) AS sequence(revision)
                CROSS JOIN provider_runtime_rule_revisions source
                WHERE source.provider_id = 'mimo' AND source.revision = 1
                """).update();
            database.jdbc.sql("""
                UPDATE provider_runtime_rule_states SET active_revision=1,
                    candidate_revision=2, candidate_status='PENDING'
                WHERE provider_id='mimo'
                """).update();
            var expected = new ProviderRuntimeRuleService(database.jdbc, new ObjectMapper()).get("mimo");
            var jdbc = spy(database.jdbc);
            var views = new ProviderRuntimeRuleService(jdbc, new ObjectMapper()).list();
            var actual = views.stream().filter(view -> view.providerId().equals("mimo")).findFirst().orElseThrow();

            assertThat(actual).isEqualTo(expected);
            assertThat(actual.active().revision()).isEqualTo(1);
            assertThat(actual.candidate().revision()).isEqualTo(2);
            assertThat(actual.revisions()).hasSize(50);
            assertThat(actual.revisions().getFirst().revision()).isEqualTo(65);
            assertThat(actual.revisions().getLast().revision()).isEqualTo(16);
            assertThat(views).extracting(ProviderRuntimeRuleService.RuleStateView::providerId).isSorted();
            verify(jdbc, times(2)).sql(anyString());
        }
    }
}
