package com.any2api.provider.mimo;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.OpenAiBridgeProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class MimoProvider extends OpenAiBridgeProvider {

    public MimoProvider(Any2ApiProperties properties) {
        super(properties, "mimo", "MiMo", "remote-bridge-v1", List.of("mimo-v2.5-pro"), Map.of(
            ProviderCapability.FUNCTION_TOOLS, SupportLevel.NATIVE,
            ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.FILE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.STORED_RESPONSES, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE));
    }
}
