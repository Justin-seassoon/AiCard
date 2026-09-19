package com.aicard.broadcast.service;

import com.aicard.broadcast.domain.DeviceGroup;
import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.broadcast.repository.DeviceGroupRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupServiceTest {

    private final DeviceGroupRepository groups = mock(DeviceGroupRepository.class);
    private final DeviceGroupMemberRepository members = mock(DeviceGroupMemberRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final GroupService service = new GroupService(groups, members, devices);

    @Test
    void addMemberRejectsDeviceFromOtherTenant() {
        DeviceGroup group = DeviceGroup.builder().id(1L).code("site1").name("工地1")
                .customerId(10L).storeId(20L).build();
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(group));
        when(devices.findByDeviceId("dev-x")).thenReturn(Optional.of(
                Device.builder().deviceId("dev-x").customerId(30L).storeId(40L).build()));

        assertThatThrownBy(() -> service.addMember(1L, "dev-x", 10L, 20L))
                .isInstanceOf(ResponseStatusException.class);
    }
}
