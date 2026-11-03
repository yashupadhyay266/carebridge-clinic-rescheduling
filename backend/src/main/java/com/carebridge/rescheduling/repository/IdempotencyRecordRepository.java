package com.carebridge.rescheduling.repository;

import com.carebridge.rescheduling.entity.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByIdempotencyKeyAndActorIdAndOperation(String key, String actorId, String operation);
}