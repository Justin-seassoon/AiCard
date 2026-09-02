package com.aicard.common.domain;

import com.aicard.common.repository.CustomerRepository;
import com.aicard.common.repository.DeviceRepository;
import com.aicard.common.repository.StoreRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class DomainMappingTest {

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

    @Test
    void persistsAndReadsDevice() {
        Customer c = customers.save(Customer.builder().code("C1").name("酒店A").createdAt(Instant.now()).build());
        Store s = stores.save(Store.builder().code("S1").customerId(c.getId()).name("门店1").createdAt(Instant.now()).build());
        Device d = devices.save(Device.builder().deviceId("dev-001").customerId(c.getId()).storeId(s.getId())
                .token("t1").staffLanguage("ja-JP").createdAt(Instant.now()).build());

        assertThat(devices.findByDeviceId("dev-001")).isPresent()
                .get().extracting(Device::getStaffLanguage).isEqualTo("ja-JP");
    }
}
