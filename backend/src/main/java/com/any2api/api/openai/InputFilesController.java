package com.any2api.api.openai;

import com.any2api.auth.ApiKeyAuthorization;
import com.any2api.auth.ApiKeyGrant;
import com.any2api.auth.ApiKeyScopeException;
import com.any2api.media.input.InputFileException;
import com.any2api.media.input.InputFileService;
import com.any2api.media.input.InputFileStore;
import com.any2api.protocol.OpenAiRequestException;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePartEvent;
import org.springframework.http.codec.multipart.FormPartEvent;
import org.springframework.http.codec.multipart.PartEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class InputFilesController {
    private final InputFileService files;
    private final ApiKeyAuthorization authorization;

    public InputFilesController(InputFileService files, ApiKeyAuthorization authorization) {
        this.files=files;
        this.authorization=authorization;
    }

    @PostMapping(path={"/v1/files","/{provider:[a-z][a-z0-9_-]{1,31}}/v1/files"},
        consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String,Object>> upload(@RequestBody Flux<PartEvent> parts, ServerWebExchange exchange) {
        var grant=grant(exchange);
        var token=exchange.getRequest().getHeaders().getFirst("Idempotency-Key");
        // PartEvent prevents getMultipartData() from spooling an unbounded file
        // before the application can enforce the eight-MiB limit.
        return files.withinCapacity(()->parts.reduceWith(Upload::new,(upload,event)-> { upload.accept(event); return upload; })
            .doOnDiscard(PartEvent.class,event->DataBufferUtils.release(event.content()))
            .flatMap(upload-> {
                upload.requireComplete();
                return files.upload(grant,token,upload.filename,upload.mediaType,upload.bytes.toByteArray());
            }).map(InputFileService::describe).timeout(java.time.Duration.ofSeconds(360))
            .onErrorMap(java.util.concurrent.TimeoutException.class,error->InputFileException.timeout()));
    }

    @GetMapping({"/v1/files/{id}","/{provider:[a-z][a-z0-9_-]{1,31}}/v1/files/{id}"})
    public Mono<Map<String,Object>> metadata(@PathVariable("id") String id, ServerWebExchange exchange) {
        return files.metadata(grant(exchange),id).map(InputFileService::describe);
    }

    @DeleteMapping({"/v1/files/{id}","/{provider:[a-z][a-z0-9_-]{1,31}}/v1/files/{id}"})
    public Mono<Map<String,Object>> delete(@PathVariable("id") String id, ServerWebExchange exchange) {
        return files.delete(grant(exchange),id);
    }

    private ApiKeyGrant grant(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().set("X-Content-Type-Options","nosniff");
        var grant=authorization.grant(exchange);
        var matcher=java.util.regex.Pattern.compile("^/([a-z][a-z0-9_-]{1,31})/v1/files(?:/.*)?$")
            .matcher(exchange.getRequest().getPath().value());
        if (matcher.matches() && !grant.allowsProvider(matcher.group(1))) {
            throw new ApiKeyScopeException("API key does not allow this file route provider");
        }
        files.authorize(grant);
        return grant;
    }

    static final class Upload {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        String filename;
        String mediaType;
        boolean fileComplete;
        boolean purpose;

        void accept(PartEvent event) {
            try {
                if (event instanceof FormPartEvent form) {
                    if (!"purpose".equals(form.name()) || purpose || !"vision".equals(form.value())
                        || !form.isLast() || (filename!=null && !fileComplete)) {
                        throw OpenAiRequestException.invalid("purpose","exactly one purpose=vision field is required");
                    }
                    purpose=true;
                } else if (event instanceof FilePartEvent file) {
                    if (!"file".equals(file.name()) || fileComplete) {
                        throw OpenAiRequestException.invalid("file","exactly one image file is required");
                    }
                    if (filename==null) {
                        filename=file.filename();
                        var type=file.headers().getContentType();
                        mediaType=type==null ? "application/octet-stream" : type.toString();
                    }
                    var size=file.content().readableByteCount();
                    if ((long)bytes.size()+size>InputFileStore.MAX_FILE_BYTES) throw InputFileException.tooLarge();
                    var chunk=new byte[size];
                    file.content().read(chunk);
                    bytes.writeBytes(chunk);
                    if (file.isLast()) fileComplete=true;
                } else {
                    throw OpenAiRequestException.invalid("file","unsupported multipart part");
                }
            } finally {
                DataBufferUtils.release(event.content());
            }
        }

        void requireComplete() {
            if (!purpose || !fileComplete || filename==null || bytes.size()==0) {
                throw OpenAiRequestException.invalid("file","complete image file and purpose=vision are required");
            }
        }
    }
}
