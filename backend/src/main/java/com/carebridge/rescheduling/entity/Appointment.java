package com.carebridge.rescheduling.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "appointments")
public class Appointment {
    @Id private String id;
    private String patientName;
    private String appointmentType;
    private String providerId;
    private String slotId;
    private String status;
    @Version private Long version;

    protected Appointment() {}

    public Appointment(String id, String patientName, String appointmentType, String providerId, String slotId, String status) {
        this.id = id; this.patientName = patientName; this.appointmentType = appointmentType;
        this.providerId = providerId; this.slotId = slotId; this.status = status;
    }

    public String getId() { return id; }
    public String getPatientName() { return patientName; }
    public String getAppointmentType() { return appointmentType; }
    public String getProviderId() { return providerId; }
    public String getSlotId() { return slotId; }
    public String getStatus() { return status; }
    public void setProviderId(String id) { providerId = id; }
    public void setSlotId(String id) { slotId = id; }
    public void setStatus(String status) { this.status = status; }
}