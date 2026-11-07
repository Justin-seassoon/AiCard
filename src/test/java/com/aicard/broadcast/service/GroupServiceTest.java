package com.aicard.broadcast.service;

import com.aicard.broadcast.domain.DeviceGroup;
import com.aicard.broadcast.domain.DeviceGroupMember;
import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.broadcast.repository.DeviceGroupRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void createSavesGroupWithTenant() {
        when(groups.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeviceGroup created = service.create(10L, 20L, "site1", "工地1");

        assertThat(created.getCustomerId()).isEqualTo(10L);
        assertThat(created.getStoreId()).isEqualTo(20L);
        assertThat(created.getCode()).isEqualTo("site1");
    }

    @Test
    void listReturnsGroupsForTenant() {
        DeviceGroup group = DeviceGroup.builder().id(1L).code("site1").name("工地1").build();
        when(groups.findByCustomerIdAndStoreId(10L, 20L)).thenReturn(List.of(group));

        assertThat(service.list(10L, 20L)).containsExactly(group);
    }

    @Test
    void addMemberRejectsUnknownGroup() {
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(1L, "dev-x", 10L, 20L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void addMemberRejectsUnknownDevice() {
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(
                DeviceGroup.builder().id(1L).code("site1").name("工地1").build()));
        when(devices.findByDeviceId("dev-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(1L, "dev-x", 10L, 20L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void addMemberReturnsExistingWhenAlreadyMember() {
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(
                DeviceGroup.builder().id(1L).code("site1").name("工地1").build()));
        when(devices.findByDeviceId("dev-x")).thenReturn(Optional.of(
                Device.builder().deviceId("dev-x").customerId(10L).storeId(20L).build()));
        DeviceGroupMember existing = DeviceGroupMember.builder().id(1L).groupId(1L).deviceId("dev-x").build();
        when(members.findByGroupIdAndDeviceId(1L, "dev-x")).thenReturn(Optional.of(existing));

        DeviceGroupMember result = service.addMember(1L, "dev-x", 10L, 20L);

        assertThat(result).isEqualTo(existing);
        verify(members, never()).save(any());
    }

    @Test
    void addMemberSavesNewMember() {
        when(groups.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(
                DeviceGroup.builder().id(1L).code("site1").name("工地1").build()));
        when(devices.findByDeviceId("dev-x")).thenReturn(Optional.of(
                Device.builder().deviceId("dev-x").customerId(10L).storeId(20L).build()));
        when(members.findByGroupIdAndDeviceId(1L, "dev-x")).thenReturn(Optional.empty());
        when(members.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeviceGroupMember result = service.addMember(1L, "dev-x", 10L, 20L);

        assertThat(result.getGroupId()).isEqualTo(1L);
        assertThat(result.getDeviceId()).isEqualTo("dev-x");
    }

    @Test
    void removeMemberDeletes() {
        service.removeMember(1L, "dev-x");

        verify(members).deleteByGroupIdAndDeviceId(1L, "dev-x");
    }

    @Test
    void listMembersReturnsMembers() {
        DeviceGroupMember member = DeviceGroupMember.builder().id(1L).groupId(1L).deviceId("dev-x").build();
        when(members.findByGroupId(1L)).thenReturn(List.of(member));

        assertThat(service.listMembers(1L)).containsExactly(member);
    }
}
