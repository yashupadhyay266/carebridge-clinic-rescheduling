package com.carebridge.rescheduling.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.carebridge.rescheduling.entity.Slot;

public interface SlotRepository extends JpaRepository<Slot, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") String id);

    List<Slot> findByStatusOrderByStartTimeAsc(String status);
}