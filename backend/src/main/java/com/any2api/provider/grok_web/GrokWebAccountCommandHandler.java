package com.any2api.provider.grok_web;

import com.any2api.provider.ProviderAccountCommandHandler;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
final class GrokWebAccountCommandHandler implements ProviderAccountCommandHandler {
    private static final String INITIALIZE = "initialize_web_account";
    private static final String ACCEPT_TERMS = "accept_terms";
    private static final String SET_BIRTH_DATE = "set_birth_date";
    private static final String ENABLE_NSFW = "enable_nsfw";

    private final GrokWebProtocolClient protocol;
    private final GrokWebProperties properties;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    GrokWebAccountCommandHandler(
        GrokWebProtocolClient protocol,
        GrokWebProperties properties,
        ObjectMapper mapper
    ) {
        this.protocol = protocol;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public String providerId() { return "grok_web"; }

    @Override
    public List<CommandDescriptor> commands() {
        return List.of(
            new CommandDescriptor(INITIALIZE, "初始化 Web 账号", true),
            new CommandDescriptor(ACCEPT_TERMS, "接受服务协议", true),
            new CommandDescriptor(SET_BIRTH_DATE, "设置成年生日", true),
            new CommandDescriptor(ENABLE_NSFW, "开启 NSFW 偏好", true));
    }

    @Override
    public Mono<CommandResult> execute(String command, CommandContext context) {
        var terms = !termsCurrent(context);
        var birth = !marked(context, "web_birth_date_set_at");
        var nsfw = !marked(context, "web_nsfw_enabled_at");
        var settings = switch (command) {
            case INITIALIZE -> new GrokWebProtocolClient.AccountSettings(
                terms, birth ? randomBirthDate() : null, nsfw);
            case ACCEPT_TERMS -> new GrokWebProtocolClient.AccountSettings(terms, null, false);
            case SET_BIRTH_DATE -> new GrokWebProtocolClient.AccountSettings(
                false, birth ? randomBirthDate() : null, false);
            case ENABLE_NSFW -> new GrokWebProtocolClient.AccountSettings(
                false, birth ? randomBirthDate() : null, nsfw);
            default -> throw new IllegalArgumentException(
                "unsupported Grok Web account command: " + command);
        };
        if (!settings.acceptTerms() && settings.birthDate() == null && !settings.enableNsfw()) {
            return Mono.just(new CommandResult(
                mapper.createObjectNode(),
                mapper.createObjectNode()));
        }
        return protocol.applyAccountSettings(
                context.credential(), context.proxyPool(), settings, affinity(context))
            .map(result -> new CommandResult(
                result.metadataPatch(), result.credentialPatch()));
    }

    private boolean termsCurrent(CommandContext context) {
        var value = context.metadata().get("web_terms_version");
        return value instanceof Number number
            && number.intValue() >= properties.getTermsVersion();
    }

    private String affinity(CommandContext context) {
        return String.valueOf(
            context.metadata().getOrDefault("identity_group_id", "")).trim();
    }

    private boolean marked(CommandContext context, String field) {
        var value = context.metadata().get(field);
        return value != null && !String.valueOf(value).isBlank();
    }

    private LocalDate randomBirthDate() {
        var today = LocalDate.now(ZoneOffset.UTC);
        var earliest = today.minusYears(40);
        var latest = today.minusYears(20);
        var days = ChronoUnit.DAYS.between(earliest, latest) + 1;
        return earliest.plusDays(random.nextLong(days));
    }
}
