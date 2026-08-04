package com.any2api.api.admin;

import com.any2api.observability.OperationLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/operations")
public class AdminOperationLogController {
    private final OperationLogService operations;

    public AdminOperationLogController(OperationLogService operations) {
        this.operations = operations;
    }

    @GetMapping
    public OperationLogService.Page list(
        @RequestParam(defaultValue = "") String provider,
        @RequestParam(defaultValue = "") String domain,
        @RequestParam(defaultValue = "") String operation,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return operations.list(new OperationLogService.Query(
            provider, domain, operation, status, search, page, size));
    }
}
