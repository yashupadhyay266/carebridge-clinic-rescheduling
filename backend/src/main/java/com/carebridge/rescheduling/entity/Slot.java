package com.carebridge.rescheduling.entity;

import java.time.Instant;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "slots")
public class Slot {
    @Id private String id;
    private String providerId;
    private Instant startTime;
    private int durationMinutes;
    private String status;
    @Version private Long version;
    private String cancelledByProviderId;
    private Instant cancelledAt;

    protected Slot() {}

    public Slot(String id, String providerId, Instant startTime, int durationMinutes, String status) {
        this.id = id; this.providerId = providerId; this.startTime = startTime;
        this.durationMinutes = durationMinutes; this.status = status;
    }

    public String getId() { return id; }
    public String getProviderId() { return providerId; }
    public Instant getStartTime() { return startTime; }
    public int getDurationMinutes() { return durationMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public void setCancelledByProviderId(String id) { cancelledByProviderId = id; }
    public void setCancelledAt(Instant time) { cancelledAt = time; }
}