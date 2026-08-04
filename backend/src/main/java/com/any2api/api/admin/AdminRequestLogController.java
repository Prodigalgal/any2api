package com.any2api.api.admin;

import com.any2api.observability.RequestLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/requests")
public class AdminRequestLogController {
    private final RequestLogService requests;

    public AdminRequestLogController(RequestLogService requests) {
        this.requests = requests;
    }

    @GetMapping
    public RequestLogService.Page list(
        @RequestParam(defaultValue = "") String provider,
        @RequestParam(defaultValue = "") String model,
        @RequestParam(name = "api_key_id", defaultValue = "") String apiKeyId,
        @RequestParam(name = "request_kind", defaultValue = "") String requestKind,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return requests.list(new RequestLogService.Query(
            provider, model, apiKeyId, requestKind, status, search, page, size));
    }

    @GetMapping("/{requestId}/attempts/{attempt}")
    public RequestLogService.Detail get(
        @PathVariable String requestId,
        @PathVariable int attempt
    ) {
        return requests.get(requestId, attempt);
    }
}
