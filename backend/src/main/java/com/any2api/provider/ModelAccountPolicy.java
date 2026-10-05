package com.any2api.provider;

/** Static model access rules shared by catalog eligibility and request routing. */
@FunctionalInterface
public interface ModelAccountPolicy {
    boolean supports(String modelId, ProviderAccountProfile account);
}
