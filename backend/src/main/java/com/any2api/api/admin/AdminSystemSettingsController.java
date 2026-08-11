package com.any2api.api.admin;

import com.any2api.settings.RuntimeSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/settings")
public class AdminSystemSettingsController {
    private final RuntimeSettingsService settings;

    public AdminSystemSettingsController(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public RuntimeSettingsService.SettingsView get() {
        return settings.get();
    }

    @PutMapping("/temp-mail")
    public RuntimeSettingsService.TempMailSettings updateTempMail(
        @RequestBody RuntimeSettingsService.TempMailSettings request
    ) {
        return settings.updateTempMail(request);
    }

    @PutMapping("/registration-defaults")
    public RuntimeSettingsService.RegistrationDefaults updateRegistrationDefaults(
        @RequestBody RuntimeSettingsService.RegistrationDefaults request
    ) {
        return settings.updateRegistrationDefaults(request);
    }

    @PutMapping("/provider-keepalive")
    public RuntimeSettingsService.ProviderKeepaliveSettings updateProviderKeepalive(
        @RequestBody RuntimeSettingsService.ProviderKeepaliveSettings request
    ) {
        return settings.updateProviderKeepalive(request);
    }
}
