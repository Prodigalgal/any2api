package com.any2api.media.input;

import com.any2api.coordination.PostgresAdvisoryLocks;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InputFileStore {
    public static final int MAX_FILE_BYTES = 8 << 20;
    public static final long OWNER_BYTES = 64L << 20;
    public static final long GLOBAL_BYTES = 256L << 20;
    public static final int OWNER_COUNT = 512;
    public static final int GLOBAL_COUNT = 2048;
    private final JdbcClient jdbc;
    private final PostgresAdvisoryLocks locks;

    public InputFileStore(JdbcClient jdbc, PostgresAdvisoryLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    public record Metadata(String id, String filename, String mediaType, String sha256,
        int bytes, Instant createdAt, Instant expiresAt) {}
    public record Content(Metadata metadata, byte[] bytes) {}

    @Transactional
    public Metadata save(String owner, UUID key, String idempotencyKey, String filename,
        String mediaType, String sha256, byte[] content) {
        if (content.length == 0 || content.length > MAX_FILE_BYTES) throw InputFileException.tooLarge();
        // One short database transaction serializes the quota check and insert.
        // No filesystem, HTTP or provider-account work is performed here.
        locks.lockTransaction("input-files-global-quota");
        cleanup();
        var existing = jdbc.sql("""
            SELECT file_id,filename,media_type,sha256,byte_length,created_at,expires_at
            FROM input_image_files WHERE owner_scope=:owner AND idempotency_key=:token
            """).param("owner", owner).param("token", idempotencyKey)
            .query((r,n) -> metadata(r)).optional();
        if (existing.isPresent()) {
            var value = existing.get();
            if (!value.sha256().equals(sha256) || !value.mediaType().equals(mediaType)
                || !value.filename().equals(filename) || value.bytes()!=content.length) {
                throw InputFileException.conflict();
            }
            return jdbc.sql("""
                UPDATE input_image_files SET expires_at=clock_timestamp()+interval '15 minutes'
                WHERE owner_scope=:owner AND file_id=:id
                RETURNING file_id,filename,media_type,sha256,byte_length,created_at,expires_at
                """).param("owner",owner).param("id",value.id()).query((r,n)->metadata(r)).single();
        }
        var quota = jdbc.sql("""
            SELECT count(*) AS global_count,coalesce(sum(byte_length),0) AS global_bytes,
              count(*) FILTER(WHERE owner_scope=:owner) AS owner_count,
              coalesce(sum(byte_length) FILTER(WHERE owner_scope=:owner),0) AS owner_bytes
            FROM input_image_files
            """).param("owner", owner).query((r,n) -> new long[]{r.getLong("global_count"),
                r.getLong("global_bytes"),r.getLong("owner_count"),r.getLong("owner_bytes")}).single();
        if (quota[0]>=GLOBAL_COUNT || quota[1]+content.length>GLOBAL_BYTES
            || quota[2]>=OWNER_COUNT || quota[3]+content.length>OWNER_BYTES) throw InputFileException.capacity();
        return jdbc.sql("""
            INSERT INTO input_image_files(file_id,owner_scope,api_key_id,idempotency_key,
              filename,media_type,sha256,byte_length,content,expires_at)
            VALUES(:id,:owner,:key,:token,:filename,:type,:sha,:bytes,:content,clock_timestamp()+interval '15 minutes')
            RETURNING file_id,filename,media_type,sha256,byte_length,created_at,expires_at
            """).param("id", "file_"+UUID.randomUUID().toString().replace("-",""))
            .param("owner", owner).param("key",key).param("token",idempotencyKey)
            .param("filename",filename).param("type",mediaType).param("sha",sha256)
            .param("bytes",content.length).param("content",content)
            .query((r,n) -> metadata(r)).single();
    }

    public Optional<Metadata> find(String owner, String id) {
        return jdbc.sql("""
            SELECT file_id,filename,media_type,sha256,byte_length,created_at,expires_at
            FROM input_image_files WHERE owner_scope=:owner AND file_id=:id AND expires_at>clock_timestamp()
            """).param("owner",owner).param("id",id).query((r,n)->metadata(r)).optional();
    }

    public Optional<Content> content(String owner, String id) {
        return jdbc.sql("""
            SELECT file_id,filename,media_type,sha256,byte_length,created_at,expires_at,content
            FROM input_image_files WHERE owner_scope=:owner AND file_id=:id AND expires_at>clock_timestamp()
            """).param("owner",owner).param("id",id)
            .query((r,n)->new Content(metadata(r),r.getBytes("content"))).optional();
    }

    public boolean delete(String owner, String id) {
        return jdbc.sql("DELETE FROM input_image_files WHERE owner_scope=:owner AND file_id=:id")
            .param("owner",owner).param("id",id).update()>0;
    }

    @Scheduled(fixedDelayString="${any2api.input-files.cleanup-ms:60000}")
    public void cleanup() {
        jdbc.sql("DELETE FROM input_image_files WHERE expires_at<=clock_timestamp()").update();
    }

    private static Metadata metadata(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Metadata(r.getString("file_id"),r.getString("filename"),r.getString("media_type"),
            r.getString("sha256"),r.getInt("byte_length"),r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("expires_at").toInstant());
    }
}
