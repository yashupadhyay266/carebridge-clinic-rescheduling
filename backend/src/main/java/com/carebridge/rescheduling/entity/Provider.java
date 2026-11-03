package com.carebridge.rescheduling.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "providers")
public class Provider {
    @Id private String id;
    private String name;
    private String specialty;
    private String status;
    @Version private Long version;

    protected Provider() {}

    public Provider(String id, String name, String specialty, String status) {
        this.id = id; this.name = name; this.specialty = specialty; this.status = status;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSpecialty() { return specialty; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}