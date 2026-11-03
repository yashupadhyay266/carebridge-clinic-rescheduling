package com.carebridge.rescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

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
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SchedulingServiceTest {

    @Mock private ProviderRepository providers;
    @Mock private SlotRepository slots;
    @Mock private AppointmentRepository appointments;
    @Mock private SuggestionRepository suggestions;
    @Mock private IdempotencyRecordRepository idempotency;
    private SchedulingService service;

    @BeforeEach
    void setUp() {
        service = new SchedulingService(
                providers, slots, appointments, suggestions, idempotency,
                new ObjectMapper(), new LlmRecommendationClient(
                        RestClient.builder(), new ObjectMapper(), "", "", "test-model", "PC1"));
    }

    @Test
    void replaysIdenticalBookingRequestWithoutBookingAgain() {
        BookingRequest request = new BookingRequest("Patient", "ROUTINE");
        IdempotencyRecord record = new IdempotencyRecord(
                "book-key", "PRV-001", "BOOK", hash("PRV-001|SLT-001|Patient|ROUTINE"),
                201, "{\"appointmentId\":\"APT-001\"}");
        when(idempotency.findByIdempotencyKeyAndActorIdAndOperation("book-key", "PRV-001", "BOOK"))
                .thenReturn(Optional.of(record));

        Map<String, Object> result = service.book("SLT-001", "PRV-001", "book-key", request);

        assertThat(result).containsEntry("appointmentId", "APT-001");
        verify(slots, never()).findByIdForUpdate(any());
        verify(appointments, never()).save(any());
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentRequest() {
        IdempotencyRecord record = new IdempotencyRecord(
                "book-key", "PRV-001", "BOOK", "different-request", 201,
                "{\"appointmentId\":\"APT-001\"}");
        when(idempotency.findByIdempotencyKeyAndActorIdAndOperation("book-key", "PRV-001", "BOOK"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.book(
                "SLT-001", "PRV-001", "book-key", new BookingRequest("Patient", "ROUTINE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Idempotency key was reused with a different request");
        verify(slots, never()).findByIdForUpdate(any());
    }

    @Test
    void expiresPendingSuggestionWhenRecommendedSlotIsNoLongerOpen() {
        UUID id = UUID.randomUUID();
        Suggestion suggestion = suggestion(id, "PENDING", "SLT-002");
        Slot booked = new Slot("SLT-002", "PRV-002", Instant.now(), 30, "BOOKED");
        when(suggestions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(suggestion));
        when(slots.findById("SLT-002")).thenReturn(Optional.of(booked));

        assertThat(service.suggestions()).isEmpty();

        assertThat(suggestion.getStatus()).isEqualTo("EXPIRED");
        verify(suggestions).save(suggestion);
    }

    @Test
    void leavesPendingSuggestionVisibleWhenRecommendedSlotIsOpen() {
        UUID id = UUID.randomUUID();
        Suggestion suggestion = suggestion(id, "PENDING", "SLT-002");
        Slot open = new Slot("SLT-002", "PRV-002", Instant.now(), 30, "OPEN");
        when(suggestions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(suggestion));
        when(slots.findById("SLT-002")).thenReturn(Optional.of(open));

        assertThat(service.suggestions()).containsExactly(suggestion);

        verify(suggestions, never()).save(suggestion);
    }

    @Test
    void rejectsUnknownRecommendation() {
        UUID id = UUID.randomUUID();
        when(suggestions.findByIdForUpdate(id)).thenReturn(null);

        assertThatThrownBy(() -> service.decide(id.toString(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Suggestion not found");
    }

    @Test
    void rejectsPendingRecommendationAndPersistsDecision() {
        UUID id = UUID.randomUUID();
        Suggestion suggestion = suggestion(id, "PENDING", "SLT-002");
        when(suggestions.findByIdForUpdate(id)).thenReturn(suggestion);

        Map<String, Object> result = service.decide(id.toString(), false);

        assertThat(result).containsEntry("id", id.toString()).containsEntry("status", "REJECTED");
        assertThat(suggestion.getStatus()).isEqualTo("REJECTED");
        verify(suggestions).save(suggestion);
        verify(slots, never()).findByIdForUpdate(any());
    }

    @Test
    void acceptsRecommendationAndMovesAppointmentToRecommendedSlot() {
        UUID id = UUID.randomUUID();
        Suggestion suggestion = suggestion(id, "PENDING", "SLT-002");
        Appointment appointment = new Appointment(
                "APT-001", "Patient", "ROUTINE", "PRV-001", "SLT-001", "RESCHEDULE_PENDING");
        Slot target = new Slot("SLT-002", "PRV-002", Instant.now().plusSeconds(3600), 30, "OPEN");
        Slot old = new Slot("SLT-001", "PRV-001", Instant.now(), 30, "BOOKED");
        when(suggestions.findByIdForUpdate(id)).thenReturn(suggestion);
        when(slots.findByIdForUpdate("SLT-002")).thenReturn(Optional.of(target));
        when(appointments.findByIdForUpdate("APT-001")).thenReturn(Optional.of(appointment));
        when(slots.findByIdForUpdate("SLT-001")).thenReturn(Optional.of(old));

        Map<String, Object> result = service.decide(id.toString(), true);

        assertThat(result).containsEntry("id", id.toString()).containsEntry("status", "RESCHEDULED");
        assertThat(suggestion.getStatus()).isEqualTo("ACCEPTED");
        assertThat(appointment.getProviderId()).isEqualTo("PRV-002");
        assertThat(appointment.getSlotId()).isEqualTo("SLT-002");
        assertThat(appointment.getStatus()).isEqualTo("RESCHEDULED");
        assertThat(target.getStatus()).isEqualTo("BOOKED");
        assertThat(old.getStatus()).isEqualTo("OPEN");
        verify(appointments).save(appointment);
        verify(slots).save(target);
        verify(slots).save(old);
        verify(suggestions).save(suggestion);
    }

    private Suggestion suggestion(UUID id, String status, String recommendedSlot) {
        Suggestion suggestion = new Suggestion(
                "APT-001", "Patient", "Dr. Current", "SLT-001", "Dr. Next", recommendedSlot,
                Instant.now().plusSeconds(3600), java.math.BigDecimal.valueOf(0.95),
                "Suitable alternative", status, "PROVIDER_OFFLINE", "RULE_BASED_FALLBACK");
        try {
            var field = Suggestion.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(suggestion, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        return suggestion;
    }

    private String hash(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}