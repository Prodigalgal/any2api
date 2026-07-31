package com.any2api.provider;

public interface ProviderInstallationCatalog {

    void requireInstalled(String providerId);

    boolean isEnabled(String providerId);

    void refresh();
}
