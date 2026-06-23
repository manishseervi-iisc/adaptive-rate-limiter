package com.darl.ratelimiter.storage.postgres;

import com.darl.ratelimiter.model.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

    List<AnomalyEvent> findByClientIdOrderByDetectedAtDesc(String clientId);

    List<AnomalyEvent> findByDetectedAtAfterOrderByDetectedAtDesc(OffsetDateTime since);

    long countByClientIdAndDetectedAtAfter(String clientId, OffsetDateTime since);
}
