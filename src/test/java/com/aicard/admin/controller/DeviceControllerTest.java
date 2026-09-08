package com.aicard.admin.controller;

import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
class DeviceControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DeviceRepository devices;

    @Test
    void listsDevicesForTenant() throws Exception {
        Device d = Device.builder().deviceId("dev-1").customerId(1L).storeId(2L)
                .token("t").staffLanguage("ja-JP").createdAt(Instant.now()).build();
        when(devices.findByCustomerIdAndStoreId(1L, 2L)).thenReturn(List.of(d));

        mvc.perform(get("/api/devices").header("X-Customer-Id", "1").header("X-Store-Id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].device_id").value("dev-1"));
    }

    @Test
    void createsDevice() throws Exception {
        when(devices.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/devices").header("X-Customer-Id", "1").header("X-Store-Id", "2")
                        .contentType("application/json")
                        .content("{\"device_id\":\"dev-9\",\"staff_language\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value("dev-9"));
    }
}
