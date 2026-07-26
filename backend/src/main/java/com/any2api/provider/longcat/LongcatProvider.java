package com.any2api.provider.longcat;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.OpenAiBridgeProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class LongcatProvider extends OpenAiBridgeProvider {

    public LongcatProvider(Any2ApiProperties properties) {
        super(properties, "longcat", "LongCat", "remote-bridge-v1", List.of("longcat-flash"), Map.of(
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.EMULATED,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE));
    }
}
