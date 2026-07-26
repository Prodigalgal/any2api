package com.any2api.provider.grok;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.OpenAiBridgeProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class GrokProvider extends OpenAiBridgeProvider {

    public GrokProvider(Any2ApiProperties properties) {
        super(properties, "grok", "Grok", "remote-bridge-v1", List.of("grok-4.5"), Map.of(
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE,
            ProviderCapability.REASONING, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE));
    }
}
