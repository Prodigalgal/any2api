package com.any2api.media.input;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyFeature;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.media.MediaInputValidation;
import com.any2api.protocol.OpenAiRequestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class InputFileService {
    private final InputFileStore store;
    private final ApiKeyAuthorization authorization;
    private final ExecutorService databaseExecutor;
    private final java.util.concurrent.Semaphore operations=new java.util.concurrent.Semaphore(8);

    public InputFileService(InputFileStore store, ApiKeyAuthorization authorization, ExecutorService databaseExecutor) {
        this.store=store;
        this.authorization=authorization;
        this.databaseExecutor=databaseExecutor;
    }

    public void authorize(ApiKeyGrant grant) {
        authorization.requireFeatures(grant,Set.of(ApiKeyFeature.FILE_UPLOADS,ApiKeyFeature.MULTIMODAL_INPUT));
    }

    public <T> Mono<T> withinCapacity(java.util.function.Supplier<Mono<T>> action) {
        return Mono.defer(()-> {
            if(!operations.tryAcquire())return Mono.error(InputFileException.busy());
            return Mono.defer(action).doFinally(signal->operations.release());
        });
    }

    public Mono<InputFileStore.Metadata> upload(ApiKeyGrant grant, String token, String filename,
        String declaredType, byte[] content) {
        authorize(grant);
        if (content.length==0) throw OpenAiRequestException.invalid("file","image file is empty");
        if (content.length>InputFileStore.MAX_FILE_BYTES) throw InputFileException.tooLarge();
        if (filename==null || filename.isBlank() || filename.length()>200
            || filename.chars().anyMatch(v->v<32 || v=='/' || v=='\\')) {
            throw OpenAiRequestException.invalid("file","invalid image filename");
        }
        var mediaType=MediaInputValidation.requireRasterImage(declaredType,content);
        if (!Set.of("image/jpeg","image/png","image/webp").contains(mediaType)) {
            throw OpenAiRequestException.invalid("file","uploaded vision files must be JPEG, PNG or WebP");
        }
        var idempotency=token==null ? UUID.randomUUID().toString() : token;
        if (!idempotency.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw OpenAiRequestException.invalid("Idempotency-Key","invalid upload idempotency key");
        }
        return Mono.fromCallable(()->store.save(owner(grant),grant.keyId(),idempotency,filename,mediaType,sha256(content),content))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public Mono<InputFileStore.Metadata> metadata(ApiKeyGrant grant, String id) {
        authorize(grant);validateId(id);
        return Mono.fromCallable(()->store.find(owner(grant),id).orElseThrow(InputFileException::missing))
            .subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public Mono<InputFileStore.Content> content(ApiKeyGrant grant, String id) {
        authorize(grant);validateId(id);
        return Mono.fromCallable(()-> {
            var result=store.content(owner(grant),id).orElseThrow(InputFileException::missing);
            if (result.bytes().length!=result.metadata().bytes()
                || !sha256(result.bytes()).equals(result.metadata().sha256())) {
                throw InputFileException.missing();
            }
            return result;
        }).subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public Mono<Map<String,Object>> delete(ApiKeyGrant grant, String id) {
        authorize(grant);validateId(id);
        return Mono.fromCallable(()-> {
            store.delete(owner(grant),id);
            // Idempotent and non-enumerating for missing or differently owned IDs.
            return Map.<String,Object>of("id",id,"object","file","deleted",true);
        }).subscribeOn(Schedulers.fromExecutor(databaseExecutor));
    }

    public static Map<String,Object> describe(InputFileStore.Metadata value) {
        return Map.of("id",value.id(),"object","file","bytes",value.bytes(),"created_at",value.createdAt().getEpochSecond(),
            "expires_at",value.expiresAt().getEpochSecond(),"filename",value.filename(),"purpose","vision",
            "media_type",value.mediaType(),"sha256",value.sha256());
    }

    static String owner(ApiKeyGrant grant) {
        return grant.keyId()==null ? "system" : "key:"+grant.keyId();
    }

    static void validateId(String id) {
        if (id==null || !id.matches("file_[0-9a-f]{32}")) throw InputFileException.missing();
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable",error); }
    }
}
