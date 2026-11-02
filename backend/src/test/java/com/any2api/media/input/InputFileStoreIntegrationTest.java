package com.any2api.media.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.any2api.interop.InteropDatabase;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Repository byte-count/transaction checks; no visual recognition or model input. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InputFileStoreIntegrationTest {
    private InteropDatabase database;
    private InputFileStore store;
    private final String owner="key:"+InteropDatabase.PRIMARY_KEY;
    private final String other="key:"+InteropDatabase.OTHER_KEY;

    @BeforeAll void start() throws Exception { database=new InteropDatabase();store=database.inputFiles(); }
    @BeforeEach void clear() { database.jdbc.sql("DELETE FROM input_image_files").update(); }
    @AfterAll void stop() throws Exception { if(database!=null)database.close(); }

    @Test void ownsPersistsExpiresAndDeletesRawBytes() throws Exception {
        var bytes=new byte[]{1,2,3,4};
        var saved=save("replay",bytes);
        assertThat(database.inputFiles().content(owner,saved.id()).orElseThrow().bytes()).isEqualTo(bytes);
        assertThat(store.find(other,saved.id())).isEmpty();
        assertThat(store.content(other,saved.id())).isEmpty();
        assertThat(store.delete(other,saved.id())).isFalse();
        assertThat(store.find(owner,saved.id())).isPresent();
        database.jdbc.sql("UPDATE input_image_files SET expires_at=clock_timestamp()-interval '1 second' WHERE file_id=:id")
            .param("id",saved.id()).update();
        assertThat(store.find(owner,saved.id())).isEmpty();
        assertThat(store.content(owner,saved.id())).isEmpty();
        store.cleanup();
        assertThat(database.jdbc.sql("SELECT count(*) FROM input_image_files").query(Long.class).single()).isZero();
        var replacement=save("replay",bytes);
        assertThat(replacement.id()).isNotEqualTo(saved.id());
        assertThat(store.delete(owner,replacement.id())).isTrue();
        assertThat(store.delete(owner,replacement.id())).isFalse();
    }

    @Test void concurrentIdempotencyCreatesOneFileAndRejectsConflictingContent() throws Exception {
        var bytes=new byte[]{1,2,3,4};
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var calls=new ArrayList<Callable<String>>();
            for(int i=0;i<12;i++)calls.add(()->save("concurrent",bytes).id());
            var ids=new java.util.HashSet<String>();
            for(var future:executor.invokeAll(calls))ids.add(future.get());
            assertThat(ids).hasSize(1);
        }
        assertThatThrownBy(()->save("concurrent",new byte[]{4,3,2,1})).isInstanceOf(InputFileException.class)
            .extracting("status").isEqualTo(409);
        assertThat(database.jdbc.sql("SELECT count(*) FROM input_image_files").query(Long.class).single()).isEqualTo(1);
    }

    @Test void replayRefreshesExpiryWithoutCreatingAnotherObject() throws Exception {
        var bytes=new byte[]{1,2};var saved=save("refresh",bytes);
        database.jdbc.sql("UPDATE input_image_files SET expires_at=clock_timestamp()+interval '2 seconds'").update();
        var replay=save("refresh",bytes);
        assertThat(replay.id()).isEqualTo(saved.id());
        assertThat(replay.createdAt()).isEqualTo(saved.createdAt());
        assertThat(replay.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(800));
    }

    @Test void keyDeletionCascadesOnlyItsFiles() throws Exception {
        var saved=save("cascade",new byte[]{1});
        database.jdbc.sql("DELETE FROM api_keys WHERE id=:id").param("id",InteropDatabase.PRIMARY_KEY).update();
        try { assertThat(store.find(owner,saved.id())).isEmpty(); }
        finally {
            database.jdbc.sql("INSERT INTO api_keys(id,name,prefix,key_hash) VALUES(:id,'interop','fixture',:hash)")
                .param("id",InteropDatabase.PRIMARY_KEY).param("hash",InteropDatabase.PRIMARY_KEY.toString()).update();
        }
    }

    @Test void concurrentCountQuotaHasNoCheckThenInsertRace() throws Exception {
        // Rows contain opaque repository bytes, not artificial recognition images.
        for(int i=0;i<InputFileStore.OWNER_COUNT-1;i++)save("seed-"+i,new byte[]{1});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var calls=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<8;i++){var key="quota-"+i;calls.add(()-> {
                try{save(key,new byte[]{2});return true;}catch(InputFileException e){assertThat(e.status()).isEqualTo(429);return false;}
            });}
            int accepted=0;for(var future:executor.invokeAll(calls))if(future.get())accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(database.jdbc.sql("SELECT count(*) FROM input_image_files").query(Long.class).single()).isEqualTo(InputFileStore.OWNER_COUNT);
        assertThat(save("seed-0",new byte[]{1}).id()).startsWith("file_");
        assertThat(store.save(other,InteropDatabase.OTHER_KEY,"other","opaque.jpg","image/jpeg",hash(new byte[]{1}),new byte[]{1}).id())
            .startsWith("file_");
    }

    private InputFileStore.Metadata save(String token,byte[] content) throws Exception {
        return store.save(owner,InteropDatabase.PRIMARY_KEY,token,"opaque.jpg","image/jpeg",hash(content),content);
    }
    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
