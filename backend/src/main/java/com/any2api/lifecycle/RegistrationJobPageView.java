package com.any2api.lifecycle;

import java.util.List;

public record RegistrationJobPageView(
    List<RegistrationJobView> items,
    long totalElements,
    int page,
    int size,
    int totalPages
) {
    public static RegistrationJobPageView of(
        List<RegistrationJobView> items,
        long totalElements,
        int page,
        int size
    ) {
        var totalPages = totalElements == 0
            ? 0 : Math.toIntExact((totalElements + size - 1) / size);
        return new RegistrationJobPageView(items, totalElements, page, size, totalPages);
    }
}
