package com.carebridge.rescheduling.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.carebridge.rescheduling.dto.BookingRequest;
import com.carebridge.rescheduling.entity.Appointment;
import com.carebridge.rescheduling.entity.IdempotencyRecord;
import com.carebridge.rescheduling.entity.Provider;
import com.carebridge.rescheduling.entity.Slot;
import com.carebridge.rescheduling.entity.Suggestion;
import com.carebridge.rescheduling.repository.AppointmentRepository;
import com.carebridge.rescheduling.repository.IdempotencyRecordRepository;
import com.carebridge.rescheduling.repository.ProviderRepository;
import com.carebridge.rescheduling.repository.SlotRepository;
import com.carebridge.rescheduling.repository.SuggestionRepository;

@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final SuggestionRepository suggestions;
    private final IdempotencyRecordRepository idempotency;
    private final ObjectMapper json;
    private final LlmRecommendationClient llm;

    public SchedulingService(
            ProviderRepository providers,
            SlotRepository slots,
            AppointmentRepository appointments,
            SuggestionRepository suggestions,
            IdempotencyRecordRepository idempotency,
            ObjectMapper json,
            LlmRecommendationClient llm) {
        this.providers = providers;
        this.slots = slots;
        this.appointments = appointments;
        this.suggestions = suggestions;
        this.idempotency = idempotency;
        this.json = json;
        this.llm = llm;
    }

    public Collection<Provider> providers() {
        return providers.findAll();
    }

    public Collection<Appointment> appointments() {
        return appointments.findAll();
    }

    public Collection<Slot> slots() {
        return slots.findAll();
    }

    @Transactional
    public Collection<Suggestion> suggestions() {
        List<Suggestion> current = suggestions.findAllByOrderByCreatedAtDesc();
        current.stream()
                .filter(suggestion -> "PENDING".equals(suggestion.getStatus()))
                .forEach(this::expireIfRecommendedSlotUnavailable);
        return current.stream()
                .filter(suggestion -> !"EXPIRED".equals(suggestion.getStatus()))
                .toList();
    }

    @Transactional
    public void providerOffline(String providerId) {
        Provider provider = providers.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found"));
        provider.setStatus("OFFLINE");
        providers.save(provider);
        trigger(providerId, "PROVIDER_OFFLINE");
    }

    @Transactional
    public Map<String, Object> book(String slotId, String providerId, String key, BookingRequest request) {
        requireKey(key);
        String requestHash = hash(providerId + "|" + slotId + "|" + request.patientName() + "|" + request.appointmentType());
        Map<String, Object> replay = replay(key, providerId, "BOOK", requestHash);
        if (replay != null) {
            return replay;
        }

        Slot slot = slots.findByIdForUpdate(slotId)
                .orElseThrow(() -> new IllegalArgumentException("Slot not found"));
        replay = replay(key, providerId, "BOOK", requestHash);
        if (replay != null) {
            return replay;
        }
        Provider provider = providers.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found"));
        if ("OFFLINE".equals(provider.getStatus())) {
            throw new IllegalStateException("Unavailable providers cannot receive new appointments");
        }
        if (!providerId.equals(slot.getProviderId())) {
            throw new SecurityException("Appointment time belongs to another provider");
        }
        if (!"OPEN".equals(slot.getStatus())) {
            throw new IllegalStateException("Slot is no longer open");
        }

        slot.setStatus("BOOKED");
        slots.save(slot);
        suggestions.findByRecommendedSlotAndStatus(slotId, "PENDING").forEach(suggestion -> {
            suggestion.setStatus("EXPIRED");
            suggestions.save(suggestion);
        });
        Appointment appointment = appointments.save(new Appointment(
                "APT-" + UUID.randomUUID(),
                request.patientName(),
                request.appointmentType(),
                providerId,
                slotId,
                "CONFIRMED"));
        Map<String, Object> result = Map.of(
                "appointmentId", appointment.getId(),
                "slotId", slotId,
                "status", "CONFIRMED");
        saveIdempotency(key, providerId, "BOOK", requestHash, 201, result);
        return result;
    }

    @Transactional
    public Map<String, Object> cancelSlot(String slotId, String providerId, String key) {
        requireKey(key);
        String requestHash = hash(providerId + "|" + slotId);
        Map<String, Object> replay = replay(key, providerId, "CANCEL", requestHash);
        if (replay != null) {
            return replay;
        }

        Slot slot = slots.findByIdForUpdate(slotId)
                .orElseThrow(() -> new IllegalArgumentException("Slot not found"));
        replay = replay(key, providerId, "CANCEL", requestHash);
        if (replay != null) {
            return replay;
        }
        if (!providerId.equals(slot.getProviderId())) {
            throw new SecurityException("Provider cannot cancel another provider's slot");
        }
        if (!"OPEN".equals(slot.getStatus())) {
            throw new IllegalStateException("Only an open slot can be cancelled");
        }

        slot.setStatus("CANCELLED");
        slot.setCancelledByProviderId(providerId);
        slot.setCancelledAt(Instant.now());
        slots.save(slot);
        Map<String, Object> result = Map.of(
                "slotId", slotId,
                "status", "CANCELLED",
                "cancelledBy", providerId);
        saveIdempotency(key, providerId, "CANCEL", requestHash, 200, result);
        return result;
    }

    @Transactional
    public Map<String, Object> decide(String id, boolean accept) {
        Suggestion suggestion = suggestions.findByIdForUpdate(UUID.fromString(id));
        if (suggestion == null) {
            throw new IllegalArgumentException("Suggestion not found");
        }
        if (!accept) {
            suggestion.setStatus("REJECTED");
            suggestions.save(suggestion);
            return Map.of("id", id, "status", "REJECTED");
        }

        Slot target = slots.findByIdForUpdate(suggestion.getRecommendedSlot())
                .orElseThrow(() -> new IllegalStateException("Recommended slot is no longer available"));
        if (!"OPEN".equals(target.getStatus())) {
            throw new IllegalStateException("Recommended slot is no longer open");
        }
        Appointment appointment = appointments.findByIdForUpdate(suggestion.getAppointmentId())
                .orElseThrow(() -> new IllegalStateException("Appointment not found"));
        Slot old = slots.findByIdForUpdate(appointment.getSlotId())
                .orElseThrow(() -> new IllegalStateException("Current slot not found"));

        target.setStatus("BOOKED");
        old.setStatus("OPEN");
        appointment.setProviderId(target.getProviderId());
        appointment.setSlotId(target.getId());
        appointment.setStatus("RESCHEDULED");
        suggestion.setStatus("ACCEPTED");
        slots.save(target);
        slots.save(old);
        appointments.save(appointment);
        suggestions.save(suggestion);
        return Map.of("id", id, "status", "ACCEPTED");
    }

    @Async
    @Transactional
    public void trigger(String providerId, String triggerType) {
        List<Appointment> affected = appointments.findByProviderIdAndStatus(providerId, "CONFIRMED");
        Provider provider = providers.findById(providerId).orElseThrow();
        for (Appointment appointment : affected) {
            appointment.setStatus("RESCHEDULE_PENDING");
            appointments.save(appointment);
            Slot alternative = chooseAlternative(appointment, provider);
            if (alternative != null) {
                Provider recommendedProvider = providers.findById(alternative.getProviderId()).orElseThrow();
                String fallbackExplanation = "Nearest safe opening; preserves duration and avoids double-booking.";
                BigDecimal confidence = confidence(appointment, provider, recommendedProvider, alternative);
                String recommendationSource = "RULE_BASED_FALLBACK";
                LlmRecommendationClient.Recommendation ai = llm.explain(new LlmRecommendationClient.Context(
                        appointment.getPatientName(), appointment.getAppointmentType(), triggerType,
                        provider.getName(), appointment.getSlotId(), recommendedProvider.getName(),
                        alternative.getStartTime().toString()));
                if (ai != null) {
                    fallbackExplanation = ai.explanation();
                    recommendationSource = "LLM";
                    log.info("Recommendation saved with LLM explanation: trigger={}, currentSlot={}, suggestedSlot={}",
                            triggerType, appointment.getSlotId(), alternative.getId());
                } else {
                    log.info("Recommendation saved with rule-based fallback: trigger={}, currentSlot={}, suggestedSlot={}",
                            triggerType, appointment.getSlotId(), alternative.getId());
                }
                suggestions.save(new Suggestion(
                        appointment.getId(), appointment.getPatientName(), provider.getName(), appointment.getSlotId(),
                        recommendedProvider.getName(), alternative.getId(), alternative.getStartTime(), confidence,
                        fallbackExplanation, "PENDING", triggerType, recommendationSource));
            }
        }
    }

    private Slot chooseAlternative(Appointment appointment, Provider unavailableProvider) {
        List<Slot> candidates = slots.findByStatusOrderByStartTimeAsc("OPEN").stream()
                .filter(slot -> !unavailableProvider.getId().equals(slot.getProviderId()))
                .filter(slot -> slot.getDurationMinutes() == appointmentDuration(appointment))
                .filter(slot -> providers.findById(slot.getProviderId())
                        .map(provider -> !"OFFLINE".equals(provider.getStatus()))
                        .orElse(false))
                .toList();
        return candidates.stream()
                .filter(slot -> providers.findById(slot.getProviderId())
                        .map(provider -> provider.getSpecialty().equals(unavailableProvider.getSpecialty()))
                        .orElse(false))
                .findFirst()
                .orElseGet(() -> candidates.stream().findFirst().orElse(null));
    }

    private int appointmentDuration(Appointment appointment) {
        return slots.findById(appointment.getSlotId()).map(Slot::getDurationMinutes).orElse(0);
    }

    private BigDecimal confidence(Appointment appointment, Provider unavailableProvider,
            Provider recommendedProvider, Slot alternative) {
        boolean sameSpecialty = unavailableProvider.getSpecialty().equals(recommendedProvider.getSpecialty());
        long minutes = Math.abs(alternative.getStartTime().getEpochSecond()
                - slots.findById(appointment.getSlotId()).map(slot -> slot.getStartTime().getEpochSecond()).orElse(0L)) / 60;
        BigDecimal score = BigDecimal.valueOf(sameSpecialty ? 0.95 : 0.86);
        if (minutes > 120) score = score.subtract(BigDecimal.valueOf(0.08));
        if (score.compareTo(BigDecimal.valueOf(0.50)) < 0) return BigDecimal.valueOf(0.50);
        return score.setScale(4);
    }

    private Map<String, Object> replay(String key, String actor, String operation, String requestHash) {
        return idempotency.findByIdempotencyKeyAndActorIdAndOperation(key, actor, operation)
                .map(record -> {
                    if (!requestHash.equals(record.getRequestHash())) {
                        throw new IllegalStateException("Idempotency key was reused with a different request");
                    }
                    try {
                        return json.readValue(record.getResponseBody(), new TypeReference<Map<String, Object>>() {
                        });
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Stored idempotency response is invalid", exception);
                    }
                }).orElse(null);
    }

    private void saveIdempotency(String key, String actor, String operation, String requestHash, int status, Map<String, Object> result) {
        try {
            idempotency.save(new IdempotencyRecord(key, actor, operation, requestHash, status, json.writeValueAsString(result)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not save idempotency record", exception);
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
    }

    private void expireIfRecommendedSlotUnavailable(Suggestion suggestion) {
        boolean unavailable = slots.findById(suggestion.getRecommendedSlot())
                .map(slot -> !"OPEN".equals(slot.getStatus()))
                .orElse(true);
        if (unavailable) {
            suggestion.setStatus("EXPIRED");
            suggestions.save(suggestion);
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash request", exception);
        }
    }
}