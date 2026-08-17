package com.any2api.runtime;

import com.any2api.persistence.PostgresResultValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderRuntimeRuleService {
    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
    private static final Pattern RULE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");
    private static final Pattern BUILD_ID = Pattern.compile("^[a-f0-9]{64}$");
    private static final int MAX_RULE_BYTES = 32_768;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ProviderRuntimeRuleService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<RuleStateView> list() {
        return jdbc.sql("""
            SELECT provider_id FROM provider_runtime_rule_states ORDER BY provider_id
            """).query(String.class).list().stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public RuleStateView get(String providerId) {
        var normalizedId = normalizeProviderId(providerId);
        var state = state(normalizedId, false);
        var revisions = jdbc.sql("""
            SELECT provider_id, revision, schema_version, rules, checksum, created_at
            FROM provider_runtime_rule_revisions
            WHERE provider_id = :provider
            ORDER BY revision DESC LIMIT 50
            """).param("provider", normalizedId).query(this::mapRevision).list();
        return view(state, revisions);
    }

    @Transactional(readOnly = true)
    public RuntimePlan plan(String providerId) {
        return findPlan(providerId).orElseThrow(() -> new IllegalArgumentException(
            "provider does not support declarative runtime rules: " + providerId));
    }

    @Transactional(readOnly = true)
    public Optional<RuntimePlan> findPlan(String providerId) {
        var normalizedId = normalizeProviderId(providerId);
        return jdbc.sql("""
            SELECT state.provider_id, state.active_revision, active.rules AS active_rules,
                   state.candidate_revision, state.candidate_status,
                   candidate.rules AS candidate_rules,
                   state.active_build_id, state.candidate_build_id
            FROM provider_runtime_rule_states state
            JOIN provider_runtime_rule_revisions active
              ON active.provider_id = state.provider_id
             AND active.revision = state.active_revision
            LEFT JOIN provider_runtime_rule_revisions candidate
              ON candidate.provider_id = state.provider_id
             AND candidate.revision = state.candidate_revision
            WHERE state.provider_id = :provider
            """).param("provider", normalizedId).query((row, ignored) -> {
                try {
                    var active = new RuleSelection(
                        row.getString("provider_id"), row.getLong("active_revision"),
                        requireRule(mapper.readValue(
                            row.getString("active_rules"), RuleDocument.class)));
                    RuleSelection candidate = null;
                    var candidateRevision = (Long) row.getObject("candidate_revision");
                    if (candidateRevision != null
                        && CandidateStatus.valueOf(row.getString("candidate_status"))
                        == CandidateStatus.PENDING) {
                        candidate = new RuleSelection(
                            row.getString("provider_id"), candidateRevision,
                            requireRule(mapper.readValue(
                                row.getString("candidate_rules"), RuleDocument.class)));
                    }
                    return new RuntimePlan(
                        active, candidate, row.getString("active_build_id"),
                        row.getString("candidate_build_id"));
                } catch (RuntimeException error) {
                    throw new SQLException("provider runtime plan is invalid", error);
                }
            }).optional();
    }

    @Transactional
    public RuleStateView createCandidate(String providerId, RuleDocument request) {
        var normalizedId = normalizeProviderId(providerId);
        var normalizedRule = requireRule(request);
        var state = state(normalizedId, true);
        var revision = jdbc.sql("""
            SELECT COALESCE(MAX(revision), 0) + 1
            FROM provider_runtime_rule_revisions WHERE provider_id = :provider
            """).param("provider", normalizedId).query(Long.class).single();
        var serialized = mapper.writeValueAsString(normalizedRule);
        jdbc.sql("""
            INSERT INTO provider_runtime_rule_revisions(
                provider_id, revision, schema_version, rules, checksum)
            VALUES (:provider, :revision, 1, CAST(:rules AS jsonb), :checksum)
            """)
            .param("provider", normalizedId)
            .param("revision", revision)
            .param("rules", serialized)
            .param("checksum", sha256(serialized))
            .update();
        jdbc.sql("""
            UPDATE provider_runtime_rule_states SET
                candidate_revision = :revision,
                candidate_status = 'PENDING',
                candidate_build_id = NULL,
                failure_reason = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :provider AND active_revision = :active
            """)
            .param("revision", revision)
            .param("provider", normalizedId)
            .param("active", state.activeRevision())
            .update();
        return get(normalizedId);
    }

    @Transactional
    public RuleStateView rollback(String providerId, long revision) {
        var normalizedId = normalizeProviderId(providerId);
        state(normalizedId, true);
        var historical = revision(normalizedId, revision).rules();
        return createCandidate(normalizedId, historical);
    }

    @Transactional
    public RuleStateView discardCandidate(String providerId) {
        var normalizedId = normalizeProviderId(providerId);
        state(normalizedId, true);
        jdbc.sql("""
            UPDATE provider_runtime_rule_states SET
                candidate_revision = NULL,
                candidate_status = 'IDLE',
                candidate_build_id = NULL,
                failure_reason = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE provider_id = :provider
            """).param("provider", normalizedId).update();
        return get(normalizedId);
    }

    @Transactional
    public void acceptReport(CanaryReport report) {
        if (report == null) return;
        var providerId = normalizeProviderId(report.providerId());
        if (report.revision() <= 0) {
            throw new IllegalArgumentException("runtime rule revision must be positive");
        }
        var buildId = normalizeBuildId(report.buildId());
        var state = state(providerId, true);
        if (report.status() == CanaryStatus.PASSED) {
            if (state.candidateRevision() != null
                && state.candidateRevision() == report.revision()
                && state.candidateStatus() == CandidateStatus.PENDING) {
                jdbc.sql("""
                    UPDATE provider_runtime_rule_states SET
                        last_known_good_revision = active_revision,
                        active_revision = :revision,
                        candidate_revision = NULL,
                        candidate_status = 'IDLE',
                        active_build_id = :build,
                        candidate_build_id = NULL,
                        failure_reason = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE provider_id = :provider
                      AND candidate_revision = :revision
                      AND candidate_status = 'PENDING'
                    """)
                    .param("revision", report.revision())
                    .param("build", buildId, Types.VARCHAR)
                    .param("provider", providerId)
                    .update();
            } else if (state.activeRevision() == report.revision()) {
                jdbc.sql("""
                    UPDATE provider_runtime_rule_states SET
                        active_build_id = :build,
                        failure_reason = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE provider_id = :provider AND active_revision = :revision
                      AND active_build_id IS DISTINCT FROM :build
                    """)
                    .param("build", buildId, Types.VARCHAR)
                    .param("provider", providerId)
                    .param("revision", report.revision())
                    .update();
            }
            return;
        }
        if (state.candidateRevision() != null
            && state.candidateRevision() == report.revision()
            && state.candidateStatus() == CandidateStatus.PENDING) {
            jdbc.sql("""
                UPDATE provider_runtime_rule_states SET
                    candidate_status = 'FAILED',
                    candidate_build_id = :build,
                    failure_reason = :reason,
                    updated_at = CURRENT_TIMESTAMP
                WHERE provider_id = :provider
                  AND candidate_revision = :revision
                  AND candidate_status = 'PENDING'
                """)
                .param("build", buildId, Types.VARCHAR)
                .param("reason", normalizeReason(report.reason()), Types.VARCHAR)
                .param("provider", providerId)
                .param("revision", report.revision())
                .update();
        }
    }

    private RuleState state(String providerId, boolean forUpdate) {
        return optionalState(providerId, forUpdate)
            .orElseThrow(() -> new IllegalArgumentException(
                "provider does not support declarative runtime rules: " + providerId));
    }

    private Optional<RuleState> optionalState(String providerId, boolean forUpdate) {
        return jdbc.sql("""
            SELECT provider_id, active_revision, candidate_revision,
                   last_known_good_revision, candidate_status,
                   active_build_id, candidate_build_id, failure_reason, updated_at
            FROM provider_runtime_rule_states WHERE provider_id = :provider
            """ + (forUpdate ? " FOR UPDATE" : ""))
            .param("provider", providerId)
            .query(this::mapState)
            .optional();
    }

    private RuleRevisionView revision(String providerId, long revision) {
        if (revision <= 0) throw new IllegalArgumentException("runtime rule revision must be positive");
        return jdbc.sql("""
            SELECT provider_id, revision, schema_version, rules, checksum, created_at
            FROM provider_runtime_rule_revisions
            WHERE provider_id = :provider AND revision = :revision
            """).param("provider", providerId).param("revision", revision)
            .query(this::mapRevision).optional()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown runtime rule revision: " + providerId + "@" + revision));
    }

    private RuleStateView view(RuleState state, List<RuleRevisionView> revisions) {
        var byRevision = new LinkedHashMap<Long, RuleRevisionView>();
        revisions.forEach(value -> byRevision.put(value.revision(), value));
        var active = byRevision.get(state.activeRevision());
        if (active == null) active = revision(state.providerId(), state.activeRevision());
        RuleRevisionView candidate = null;
        if (state.candidateRevision() != null) {
            candidate = byRevision.get(state.candidateRevision());
            if (candidate == null) candidate = revision(state.providerId(), state.candidateRevision());
        }
        return new RuleStateView(
            state.providerId(), active, candidate, state.lastKnownGoodRevision(),
            state.candidateStatus(), state.activeBuildId(), state.candidateBuildId(),
            state.failureReason(), state.updatedAt(), List.copyOf(revisions));
    }

    private RuleRevisionView mapRevision(ResultSet row, int ignored) throws SQLException {
        try {
            return new RuleRevisionView(
                row.getString("provider_id"), row.getLong("revision"),
                row.getInt("schema_version"),
                requireRule(mapper.readValue(row.getString("rules"), RuleDocument.class)),
                row.getString("checksum"), PostgresResultValues.instant(row, "created_at"));
        } catch (RuntimeException error) {
            throw new SQLException("provider runtime rule is invalid", error);
        }
    }

    private RuleState mapState(ResultSet row, int ignored) throws SQLException {
        return new RuleState(
            row.getString("provider_id"), row.getLong("active_revision"),
            (Long) row.getObject("candidate_revision"),
            (Long) row.getObject("last_known_good_revision"),
            CandidateStatus.valueOf(row.getString("candidate_status")),
            row.getString("active_build_id"), row.getString("candidate_build_id"),
            row.getString("failure_reason"), PostgresResultValues.instant(row, "updated_at"));
    }

    private RuleDocument requireRule(RuleDocument request) {
        if (request == null) throw new IllegalArgumentException("runtime rule is required");
        var normalized = request.normalized();
        if (mapper.writeValueAsBytes(normalized).length > MAX_RULE_BYTES) {
            throw new IllegalArgumentException("runtime rule exceeds 32 KiB");
        }
        return normalized;
    }

    private static String normalizeProviderId(String value) {
        var providerId = value == null ? "" : value.trim();
        if (!PROVIDER_ID.matcher(providerId).matches()) {
            throw new IllegalArgumentException("invalid runtime rule provider id");
        }
        return providerId;
    }

    private static String normalizeBuildId(String value) {
        if (value == null || value.isBlank()) return null;
        var buildId = value.trim().toLowerCase();
        if (!BUILD_ID.matcher(buildId).matches()) {
            throw new IllegalArgumentException("runtime build id must be a SHA-256 value");
        }
        return buildId;
    }

    private static String normalizeReason(String value) {
        if (value == null || value.isBlank()) return "runtime discovery failed";
        var compact = value.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public enum CandidateStatus { IDLE, PENDING, FAILED }

    public enum CanaryStatus { PASSED, FAILED }

    public record RuleDocument(
        int schemaVersion,
        int sessionMaxAgeSeconds,
        int canaryTimeoutSeconds,
        List<String> buildAssetMarkers,
        Map<String, List<String>> discoveryMarkers,
        Map<String, String> capabilities,
        Map<String, String> endpointPaths
    ) {
        public RuleDocument normalized() {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("runtime rule schemaVersion must be 1");
            }
            requireRange(sessionMaxAgeSeconds, 60, 86_400, "sessionMaxAgeSeconds");
            requireRange(canaryTimeoutSeconds, 5, 300, "canaryTimeoutSeconds");
            var buildMarkers = normalizeStrings(
                buildAssetMarkers, 1, 16, "buildAssetMarkers");
            var markers = normalizeStringLists(discoveryMarkers, "discoveryMarkers");
            if (markers.isEmpty()) {
                throw new IllegalArgumentException("discoveryMarkers must not be empty");
            }
            return new RuleDocument(
                1, sessionMaxAgeSeconds, canaryTimeoutSeconds, buildMarkers, markers,
                normalizeStringMap(capabilities, false, "capabilities"),
                normalizeStringMap(endpointPaths, true, "endpointPaths"));
        }

        private static Map<String, List<String>> normalizeStringLists(
            Map<String, List<String>> input,
            String name
        ) {
            if (input == null || input.size() > 16) {
                throw new IllegalArgumentException(name + " has too many entries");
            }
            var output = new LinkedHashMap<String, List<String>>();
            input.forEach((key, values) -> output.put(
                normalizeKey(key, name), normalizeStrings(values, 1, 16, name)));
            return Collections.unmodifiableMap(output);
        }

        private static Map<String, String> normalizeStringMap(
            Map<String, String> input,
            boolean path,
            String name
        ) {
            if (input == null || input.size() > 16) {
                throw new IllegalArgumentException(name + " has too many entries");
            }
            var output = new LinkedHashMap<String, String>();
            input.forEach((key, rawValue) -> {
                var value = normalizeLiteral(rawValue, name);
                if (path && (!value.startsWith("/") || value.contains("://")
                    || value.chars().anyMatch(Character::isWhitespace))) {
                    throw new IllegalArgumentException(name + " values must be same-origin paths");
                }
                output.put(normalizeKey(key, name), value);
            });
            return Collections.unmodifiableMap(output);
        }

        private static List<String> normalizeStrings(
            List<String> input,
            int minimum,
            int maximum,
            String name
        ) {
            if (input == null || input.size() < minimum || input.size() > maximum) {
                throw new IllegalArgumentException(name + " is outside allowed size");
            }
            var output = new ArrayList<String>();
            input.forEach(value -> {
                var literal = normalizeLiteral(value, name);
                if (!output.contains(literal)) output.add(literal);
            });
            if (output.size() < minimum) {
                throw new IllegalArgumentException(name + " must contain a value");
            }
            return List.copyOf(output);
        }

        private static String normalizeKey(String value, String name) {
            var key = value == null ? "" : value.trim();
            if (!RULE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(name + " contains an invalid key");
            }
            return key;
        }

        private static String normalizeLiteral(String value, String name) {
            var literal = value == null ? "" : value.trim();
            if (literal.isBlank() || literal.length() > 256
                || literal.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " contains an invalid literal");
            }
            return literal;
        }

        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " is outside allowed range");
            }
        }
    }

    public record RuleRevisionView(
        String providerId,
        long revision,
        int schemaVersion,
        RuleDocument rules,
        String checksum,
        Instant createdAt
    ) {}

    public record RuleStateView(
        String providerId,
        RuleRevisionView active,
        RuleRevisionView candidate,
        Long lastKnownGoodRevision,
        CandidateStatus candidateStatus,
        String activeBuildId,
        String candidateBuildId,
        String failureReason,
        Instant updatedAt,
        List<RuleRevisionView> revisions
    ) {}

    public record RuleSelection(String providerId, long revision, RuleDocument rules) {}

    public record RuntimePlan(
        RuleSelection active,
        RuleSelection candidate,
        String activeBuildId,
        String candidateBuildId
    ) {}

    public record CanaryReport(
        String providerId,
        long revision,
        String buildId,
        CanaryStatus status,
        String reason
    ) {}

    private record RuleState(
        String providerId,
        long activeRevision,
        Long candidateRevision,
        Long lastKnownGoodRevision,
        CandidateStatus candidateStatus,
        String activeBuildId,
        String candidateBuildId,
        String failureReason,
        Instant updatedAt
    ) {}
}
