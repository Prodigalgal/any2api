package com.any2api.provider.qwen;

import com.any2api.config.Any2ApiProperties;
import com.any2api.provider.OpenAiBridgeProvider;
import com.any2api.provider.ProviderCapability;
import com.any2api.provider.SupportLevel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class QwenProvider extends OpenAiBridgeProvider {

    public QwenProvider(Any2ApiProperties properties) {
        super(properties, "qwen", "Qwen", "remote-bridge-v1", List.of("qwen3.7-plus"), Map.of(
            ProviderCapability.IMAGE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.AUDIO_INPUT, SupportLevel.NATIVE,
            ProviderCapability.VIDEO_INPUT, SupportLevel.NATIVE,
            ProviderCapability.FILE_INPUT, SupportLevel.NATIVE,
            ProviderCapability.REGISTRATION, SupportLevel.NATIVE,
            ProviderCapability.REAUTHENTICATION, SupportLevel.NATIVE));
    }
}
