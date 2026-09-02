package com.aicard.common.repository;

import com.aicard.common.domain.Device;
import com.aicard.common.tenant.TenantContext;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

@Repository
public class DeviceScopedRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<Device> findByDeviceIdScoped(String deviceId) {
        var tenant = TenantContext.require();
        return em.createQuery(
                "SELECT d FROM Device d WHERE d.deviceId = :deviceId " +
                "AND d.customerId = :customerId AND d.storeId = :storeId", Device.class)
            .setParameter("deviceId", deviceId)
            .setParameter("customerId", tenant.customerId())
            .setParameter("storeId", tenant.storeId())
            .setMaxResults(1)
            .getResultList()
            .stream()
            .findFirst();
    }
}
