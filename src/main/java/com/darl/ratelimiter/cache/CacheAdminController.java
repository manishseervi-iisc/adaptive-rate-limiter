package com.darl.ratelimiter.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/cache")
@RequiredArgsConstructor
public class CacheAdminController {

    private final ClientConfigCache configCache;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "cachedConfigs", configCache.size(),
                "message", "Cache refreshes every 60 seconds automatically"
        );
    }

    @PostMapping("/refresh")
    public Map<String, Object> forceRefresh() {
        int before = configCache.size();
        configCache.warmUp();
        return Map.of(
                "before", before,
                "after",  configCache.size(),
                "message", "Cache refreshed from PostgreSQL"
        );
    }

    @PostMapping("/evict/{clientId}")
    public Map<String, Object> evict(@PathVariable String clientId) {
        configCache.evict(clientId);
        return Map.of(
                "clientId", clientId,
                "message",  "Evicted — next request will reload from PostgreSQL"
        );
    }
}