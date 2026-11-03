package com.carebridge.rescheduling.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.carebridge.rescheduling.dto.BookingRequest;
import com.carebridge.rescheduling.service.SchedulingService;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class SchedulingController {

    private final SchedulingService service;

    public SchedulingController(SchedulingService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
                "providers", service.providers(),
                "slots", service.slots(),
                "appointments", service.appointments(),
                "suggestions", service.suggestions());
    }

    @PostMapping("/providers/{id}/offline")
    public ResponseEntity<?> offline(@PathVariable String id) {
        try {
            service.providerOffline(id);
            return ResponseEntity.accepted().build();
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/slots/{id}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String id,
            @RequestHeader("X-Provider-Id") String providerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        try {
            return ResponseEntity.ok(service.cancelSlot(id, providerId, idempotencyKey));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    @PostMapping("/slots/{id}/book")
    public ResponseEntity<?> book(
            @PathVariable String id,
            @RequestHeader("X-Provider-Id") String providerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody BookingRequest request) {
        try {
            return ResponseEntity.status(201).body(service.book(id, providerId, idempotencyKey, request));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    @PostMapping("/suggestions/{id}/accept")
    public Object accept(@PathVariable String id) {
        return service.decide(id, true);
    }

    @PostMapping("/suggestions/{id}/reject")
    public Object reject(@PathVariable String id) {
        return service.decide(id, false);
    }

    private ResponseEntity<Map<String, String>> error(Exception exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (exception instanceof SecurityException) {
            status = HttpStatus.FORBIDDEN;
        } else if (exception instanceof IllegalArgumentException) {
            status = HttpStatus.NOT_FOUND;
        } else if (exception instanceof IllegalStateException) {
            status = HttpStatus.CONFLICT;
        }
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
    }
}