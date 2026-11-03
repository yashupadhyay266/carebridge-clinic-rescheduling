package com.carebridge.rescheduling.repository;

import com.carebridge.rescheduling.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, String> {
}