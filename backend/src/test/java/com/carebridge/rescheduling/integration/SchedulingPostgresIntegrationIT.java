package com.carebridge.rescheduling.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.carebridge.rescheduling.dto.BookingRequest;
import com.carebridge.rescheduling.entity.Appointment;
import com.carebridge.rescheduling.entity.Provider;
import com.carebridge.rescheduling.entity.Slot;
import com.carebridge.rescheduling.repository.AppointmentRepository;
import com.carebridge.rescheduling.repository.ProviderRepository;
import com.carebridge.rescheduling.repository.SlotRepository;
import com.carebridge.rescheduling.service.SchedulingService;

@SpringBootTest
@ActiveProfiles("integration")
@Tag("postgres-integration")
class SchedulingPostgresIntegrationIT {

    @Autowired private SchedulingService service;
    @Autowired private ProviderRepository providers;
    @Autowired private SlotRepository slots;
    @Autowired private AppointmentRepository appointments;

    private String providerId;
    private String slotId;

    @BeforeEach
    void setUp() {
        providerId = "IT-PRV-" + UUID.randomUUID();
        slotId = "IT-SLT-" + UUID.randomUUID();
        providers.saveAndFlush(new Provider(providerId, "Integration Doctor", "GENERAL", "AVAILABLE"));
        slots.saveAndFlush(new Slot(slotId, providerId, Instant.now().plusSeconds(3600), 30, "OPEN"));
    }

    @AfterEach
    void cleanUp() {
        appointments.findAll().stream()
                .filter(appointment -> appointment.getId().startsWith("IT-APT-"))
                .forEach(appointment -> appointments.deleteById(appointment.getId()));
        slots.findById(slotId).ifPresent(slot -> slots.deleteById(slot.getId()));
        providers.findById(providerId).ifPresent(provider -> providers.deleteById(provider.getId()));
    }

    @Test
    void concurrentDifferentRequestsCanOnlyBookOneSlot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> first = bookingAttempt("key-a", "Patient A", ready, start);
            Callable<Object> second = bookingAttempt("key-b", "Patient B", ready, start);
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result instanceof java.util.Map).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result instanceof Throwable).count()).isEqualTo(1);
            assertThat(slots.findById(slotId)).get().extracting(Slot::getStatus).isEqualTo("BOOKED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRetriesWithSameIdempotencyKeyReturnOneLogicalBooking() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> first = bookingAttempt("same-key", "Patient", ready, start);
            Callable<Object> second = bookingAttempt("same-key", "Patient", ready, start);
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Object firstBooking = firstResult.get(15, TimeUnit.SECONDS);
            Object secondBooking = secondResult.get(15, TimeUnit.SECONDS);
            assertThat(firstBooking).isInstanceOf(java.util.Map.class);
            assertThat(secondBooking).isInstanceOf(java.util.Map.class);
            assertThat(((java.util.Map<?, ?>) firstBooking).get("appointmentId"))
                    .isEqualTo(((java.util.Map<?, ?>) secondBooking).get("appointmentId"));
            assertThat(appointments.findAll().stream()
                    .filter(appointment -> appointment.getSlotId().equals(slotId)).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseUniqueIndexRejectsTwoActiveAppointmentsForOneSlot() {
        appointments.saveAndFlush(new Appointment(
                "IT-APT-1-" + UUID.randomUUID(), "Patient A", "ROUTINE", providerId, slotId, "CONFIRMED"));

        assertThatThrownBy(() -> appointments.saveAndFlush(new Appointment(
                "IT-APT-2-" + UUID.randomUUID(), "Patient B", "ROUTINE", providerId, slotId, "RESCHEDULED")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAllowsCancelledAppointmentToReleaseSlotUniqueness() {
        Appointment cancelled = appointments.saveAndFlush(new Appointment(
                "IT-APT-1-" + UUID.randomUUID(), "Patient A", "ROUTINE", providerId, slotId, "CANCELLED"));

        Appointment confirmed = appointments.saveAndFlush(new Appointment(
                "IT-APT-2-" + UUID.randomUUID(), "Patient B", "ROUTINE", providerId, slotId, "CONFIRMED"));

        assertThat(cancelled.getSlotId()).isEqualTo(confirmed.getSlotId());
    }

    @Test
    void failedBookingDoesNotCreateAnAppointmentOrIdempotencyRecord() {
        Slot slot = slots.findById(slotId).orElseThrow();
        slot.setStatus("BOOKED");
        slots.saveAndFlush(slot);

        assertThatThrownBy(() -> service.book(
                slotId, providerId, "rollback-key", new BookingRequest("Patient", "ROUTINE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Slot is no longer open");

        assertThat(appointments.findAll().stream().noneMatch(
                appointment -> appointment.getSlotId().equals(slotId))).isTrue();
    }

    private Callable<Object> bookingAttempt(String key, String patient, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                return service.book(slotId, providerId, key, new BookingRequest(patient, "ROUTINE"));
            } catch (RuntimeException exception) {
                return exception;
            }
        };
    }
}