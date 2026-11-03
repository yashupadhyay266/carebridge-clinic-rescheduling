package com.carebridge.rescheduling.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.carebridge.rescheduling.entity.Suggestion;

public interface SuggestionRepository extends JpaRepository<Suggestion, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Suggestion s where s.id = :id")
    Suggestion findByIdForUpdate(@Param("id") UUID id);

    List<Suggestion> findAllByOrderByCreatedAtDesc();

    List<Suggestion> findByRecommendedSlotAndStatus(String recommendedSlot, String status);
}