package com.darl.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async thread pools.
 * - auditExecutor:    PostgreSQL audit writes (never blocks HTTP threads)
 * - adaptiveExecutor: ML inference HTTP calls + Redis cache updates
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(
                (r, e) -> System.err.println("[Audit] queue full, dropping record")
        );
        executor.initialize();
        return executor;
    }

    @Bean(name = "adaptiveExecutor")
    public Executor adaptiveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("adaptive-");
        executor.setRejectedExecutionHandler(
                (r, e) -> System.err.println("[Adaptive] queue full, skipping prediction refresh")
        );
        executor.initialize();
        return executor;
    }

    @Bean(name = "shadowExecutor")
    public Executor shadowExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("shadow-");
        executor.setRejectedExecutionHandler(
                (r, e) -> System.err.println("[Shadow] queue full, dropping shadow record")
        );
        executor.initialize();
        return executor;
    }
}
