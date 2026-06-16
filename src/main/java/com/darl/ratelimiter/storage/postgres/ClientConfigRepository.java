package com.darl.ratelimiter.storage.postgres;

import com.darl.ratelimiter.model.ClientConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientConfigRepository extends JpaRepository<ClientConfig, UUID> {

    Optional<ClientConfig> findByClientId(String clientId);

    /** Used on startup to pre-warm Redis with all active configs. */
    List<ClientConfig> findAllByActiveTrue();

    /** Quick existence check — avoids loading the full entity. */
    boolean existsByClientId(String clientId);

    @Query("SELECT c FROM ClientConfig c WHERE c.active = true AND c.algorithm = :algorithm")
    List<ClientConfig> findActiveByAlgorithm(ClientConfig.Algorithm algorithm);
}
