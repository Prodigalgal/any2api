package com.any2api.lifecycle;

import java.util.List;

public record RegistrationSchedulePageView(
    List<RegistrationScheduleView> items,
    long totalElements,
    int page,
    int size,
    int totalPages
) {
    public static RegistrationSchedulePageView of(
        List<RegistrationScheduleView> items,
        long totalElements,
        int page,
        int size
    ) {
        var totalPages = totalElements == 0
            ? 0 : Math.toIntExact((totalElements + size - 1) / size);
        return new RegistrationSchedulePageView(
            items, totalElements, page, size, totalPages);
    }
}
