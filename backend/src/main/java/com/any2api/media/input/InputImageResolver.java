package com.any2api.media.input;

import com.any2api.auth.ApiKeyGrant;
import com.any2api.protocol.OpenAiRequestException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class InputImageResolver {
    private static final int MAX_IMAGE_BLOCKS=16;
    private static final int MAX_EXPANDED_IMAGE_CHARACTERS=12 << 20;
    private final InputFileService files;

    public InputImageResolver(InputFileService files) { this.files=files; }

    public Mono<ObjectNode> resolve(ObjectNode original, ApiKeyGrant grant) {
        return Mono.defer(()->resolveContent(original,grant));
    }

    private Mono<ObjectNode> resolveContent(ObjectNode original, ApiKeyGrant grant) {
        var request=original.deepCopy();
        var blocks=new ArrayList<ObjectNode>();
        contentBlocks(request.path("messages"),blocks);
        contentBlocks(request.path("input"),blocks);
        var references=blocks.stream().filter(InputImageResolver::hasReference).toList();
        if (references.isEmpty()) return Mono.just(original);
        files.authorize(grant);
        if (blocks.size()>MAX_IMAGE_BLOCKS) throw OpenAiRequestException.invalid("file_id","too many image blocks");
        var resolved=new HashMap<String,String>();
        long[] characters={0};
        for (var block:blocks) {
            if (hasReference(block)) continue;
            var value=block.path("image_url");
            reserve(characters,value.isTextual() ? value.asText().length() : value.path("url").asText("").length());
        }
        return files.withinCapacity(()->Flux.fromIterable(references).concatMap(block-> {
            var reference=reference(block);
            var cached=resolved.get(reference);
            if (cached!=null) { reserve(characters,cached.length()); replace(block,cached); return Mono.just(block); }
            return files.content(grant,reference).map(content-> {
                reserve(characters,4L*((content.bytes().length+2)/3)+64);
                var url="data:"+content.metadata().mediaType()+";base64,"+Base64.getEncoder().encodeToString(content.bytes());
                resolved.put(reference,url);
                replace(block,url);
                return block;
            });
        }).then(Mono.just(request)));
    }

    private static void reserve(long[] used, long count) {
        used[0]+=count;
        if (used[0]>MAX_EXPANDED_IMAGE_CHARACTERS) throw InputFileException.tooLarge();
    }

    private static void contentBlocks(JsonNode messages, List<ObjectNode> images) {
        if (!messages.isArray()) return;
        for (var message:messages) {
            imageBlock(message,images);
            var content=message.path("content");
            if (content.isArray()) for (var block:content) imageBlock(block,images);
        }
    }

    private static void imageBlock(JsonNode value, List<ObjectNode> images) {
        if (value instanceof ObjectNode object
            && java.util.Set.of("image_url","input_image").contains(value.path("type").asText())) images.add(object);
    }

    private static boolean hasReference(ObjectNode block) {
        return block.has("file_id") || block.path("image_url").has("file_id");
    }

    private static String reference(ObjectNode block) {
        if (block.properties().stream().anyMatch(v->!java.util.Set.of("type","file_id","image_url","detail").contains(v.getKey()))) {
            throw OpenAiRequestException.invalid("file_id","file image block contains unsupported fields");
        }
        var nested=block.path("image_url");
        var top=block.get("file_id");
        var inner=nested.get("file_id");
        if ((top!=null && inner!=null) || (top!=null && block.has("image_url"))
            || (inner!=null && nested.size()!=1)) {
            throw OpenAiRequestException.invalid("file_id","file reference cannot be combined with another image source");
        }
        var id=top!=null ? top : inner;
        if (id==null || !id.isTextual()) throw InputFileException.missing();
        InputFileService.validateId(id.asText());
        return id.asText();
    }

    private static void replace(ObjectNode block, String dataUrl) {
        block.remove("file_id");
        if ("input_image".equals(block.path("type").asText())) block.put("image_url",dataUrl);
        else block.putObject("image_url").put("url",dataUrl);
    }
}
