package com.darl.ratelimiter.cache;

import com.darl.ratelimiter.model.ClientConfig;
import com.darl.ratelimiter.storage.postgres.ClientConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClientConfigCache {

    private final ClientConfigRepository repository;

    private final ConcurrentHashMap<String, ClientConfig> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void warmUp() {
        loadAll();
        log.info("[ConfigCache] warmed up with {} active configs", cache.size());
    }

    @Scheduled(fixedDelay = 60_000)
    public void refresh() {
        int before = cache.size();
        loadAll();
        log.debug("[ConfigCache] refreshed — {} configs loaded (was {})", cache.size(), before);
    }

    public Optional<ClientConfig> get(String clientId) {
        ClientConfig cached = cache.get(clientId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ClientConfig> fromDb = repository.findByClientId(clientId);
        fromDb.ifPresent(config -> {
            cache.put(clientId, config);
            log.debug("[ConfigCache] cache miss for clientId={}, loaded from DB", clientId);
        });
        return fromDb;
    }

    public void put(String clientId, ClientConfig config) {
        cache.put(clientId, config);
    }

    public void evict(String clientId) {
        cache.remove(clientId);
    }

    public int size() {
        return cache.size();
    }

    private void loadAll() {
        repository.findAllByActiveTrue().forEach(config ->
                cache.put(config.getClientId(), config));
    }
}