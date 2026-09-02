package com.aicard.common.tenant;

import com.aicard.common.domain.Customer;
import com.aicard.common.domain.Device;
import com.aicard.common.domain.Store;
import com.aicard.common.repository.CustomerRepository;
import com.aicard.common.repository.DeviceScopedRepository;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.repository.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TenantIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aicard").withUsername("aicard").withPassword("aicard")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CustomerRepository customers;
    @Autowired StoreRepository stores;
    @Autowired DeviceRepository devices;
    @Autowired DeviceScopedRepository scopedDevices;

    private Long tenantACustomerId;
    private Long tenantAStoreId;
    private Long tenantBCustomerId;
    private Long tenantBStoreId;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        devices.deleteAll();
        stores.deleteAll();
        customers.deleteAll();
    }

    private void seed() {
        Customer a = customers.save(Customer.builder().code("A").name("A").createdAt(Instant.now()).build());
        Customer b = customers.save(Customer.builder().code("B").name("B").createdAt(Instant.now()).build());
        Store sa = stores.save(Store.builder().code("SA").customerId(a.getId()).name("SA").createdAt(Instant.now()).build());
        Store sb = stores.save(Store.builder().code("SB").customerId(b.getId()).name("SB").createdAt(Instant.now()).build());
        tenantACustomerId = a.getId();
        tenantAStoreId = sa.getId();
        tenantBCustomerId = b.getId();
        tenantBStoreId = sb.getId();
        devices.save(Device.builder().deviceId("dev-A").customerId(a.getId()).storeId(sa.getId()).token("t").createdAt(Instant.now()).build());
        devices.save(Device.builder().deviceId("dev-B").customerId(b.getId()).storeId(sb.getId()).token("t").createdAt(Instant.now()).build());
    }

    @Test
    void scopedQueryReturnsOwnTenantDevice() {
        seed();
        TenantContext.set(Tenant.of(tenantACustomerId, tenantAStoreId));
        Optional<Device> found = scopedDevices.findByDeviceIdScoped("dev-A");
        assertThat(found).isPresent();
    }

    @Test
    void scopedQueryBlocksOtherTenantDevice() {
        seed();
        TenantContext.set(Tenant.of(tenantACustomerId, tenantAStoreId));
        Optional<Device> found = scopedDevices.findByDeviceIdScoped("dev-B");
        assertThat(found).isEmpty();
    }
}
