package com.carebridge.rescheduling.entity;

import java.time.Instant;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String idempotencyKey;
    private String actorId;
    private String operation;
    private String requestHash;
    private int responseStatus;
    private String responseBody;
    private Instant createdAt = Instant.now();

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String key, String actor, String operation, String hash, int status, String body) {
        idempotencyKey = key; actorId = actor; this.operation = operation; requestHash = hash;
        responseStatus = status; responseBody = body;
    }

    public String getRequestHash() { return requestHash; }
    public String getResponseBody() { return responseBody; }
}