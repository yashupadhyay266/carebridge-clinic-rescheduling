package com.carebridge.rescheduling.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.carebridge.rescheduling.service.SchedulingService;

class SchedulingControllerTest {

    private final SchedulingService service = mock(SchedulingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchedulingController(service)).build();

    @Test
    void mapsProviderOwnershipFailureToForbidden() throws Exception {
        when(service.book(eq("SLT-005"), eq("PRV-001"), eq("book-key"), any()))
                .thenThrow(new SecurityException("Appointment time belongs to another provider"));

        mvc.perform(post("/api/slots/SLT-005/book")
                        .header("X-Provider-Id", "PRV-001")
                        .header("Idempotency-Key", "book-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientName\":\"Patient\",\"appointmentType\":\"ROUTINE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Appointment time belongs to another provider"));
    }

    @Test
    void mapsUnavailableSlotToConflict() throws Exception {
        when(service.book(eq("SLT-005"), eq("PRV-004"), eq("book-key"), any()))
                .thenThrow(new IllegalStateException("Slot is no longer open"));

        mvc.perform(post("/api/slots/SLT-005/book")
                        .header("X-Provider-Id", "PRV-004")
                        .header("Idempotency-Key", "book-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientName\":\"Patient\",\"appointmentType\":\"ROUTINE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Slot is no longer open"));
    }
}