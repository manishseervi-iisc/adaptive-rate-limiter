package com.darl.ratelimiter;

import com.darl.ratelimiter.storage.postgres.ClientConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator implements ApplicationRunner {

    private final ClientConfigRepository clientConfigRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== distributed_adaptive_rate_limiter starting up ===");
        checkRedis();
        checkPostgres();
        log.info("=== All storage backends healthy. Ready. ===");
    }

    private void checkRedis() {
        try {
            String pong = stringRedisTemplate.getConnectionFactory()
                    .getConnection().ping();
            log.info("[Redis] ping={}", pong);
        } catch (Exception e) {
            log.error("[Redis] health check FAILED: {}", e.getMessage());
            throw new IllegalStateException("Redis unavailable on startup", e);
        }
    }

    private void checkPostgres() {
        try {
            long count = clientConfigRepository.count();
            log.info("[Postgres] connected — client_configs rows: {}", count);
            clientConfigRepository.findAllByActiveTrue().forEach(cfg ->
                    log.info("[Postgres] loaded config: clientId={} algorithm={} rps={}",
                            cfg.getClientId(), cfg.getAlgorithm(), cfg.getRatePerSecond())
            );
        } catch (Exception e) {
            log.error("[Postgres] health check FAILED: {}", e.getMessage());
            throw new IllegalStateException("PostgreSQL unavailable on startup", e);
        }
    }
}