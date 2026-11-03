package com.carebridge.rescheduling.entity;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "suggestions")
public class Suggestion {
    @Id private UUID id;
    private String appointmentId;
    private String patientName;
    private String currentProvider;
    private String currentSlot;
    private String recommendedProvider;
    private String recommendedSlot;
    private Instant recommendedStart;
    @Column(precision = 5, scale = 4, nullable = false)
    private BigDecimal confidence;
    private String explanation;
    private String status;
    private String triggerType;
    private String recommendationSource;
    private Instant createdAt;

    protected Suggestion() {}

    public Suggestion(String appointmentId, String patientName, String currentProvider, String currentSlot,
                      String recommendedProvider, String recommendedSlot, Instant recommendedStart,
                      BigDecimal confidence, String explanation, String status, String triggerType,
                      String recommendationSource) {
        this.id = UUID.randomUUID(); this.appointmentId = appointmentId; this.patientName = patientName;
        this.currentProvider = currentProvider; this.currentSlot = currentSlot;
        this.recommendedProvider = recommendedProvider; this.recommendedSlot = recommendedSlot;
        this.recommendedStart = recommendedStart; this.confidence = confidence; this.explanation = explanation;
        this.status = status; this.triggerType = triggerType; this.createdAt = Instant.now();
        this.recommendationSource = recommendationSource;
    }

    public UUID getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getPatientName() { return patientName; }
    public String getCurrentProvider() { return currentProvider; }
    public String getCurrentSlot() { return currentSlot; }
    public String getRecommendedProvider() { return recommendedProvider; }
    public String getRecommendedSlot() { return recommendedSlot; }
    public Instant getRecommendedStart() { return recommendedStart; }
    public BigDecimal getConfidence() { return confidence; }
    public String getExplanation() { return explanation; }
    public String getStatus() { return status; }
    public String getTriggerType() { return triggerType; }
    public String getRecommendationSource() { return recommendationSource; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(String status) { this.status = status; }
}