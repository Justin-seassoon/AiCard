package com.aicard.broadcast.service;

import com.aicard.broadcast.domain.DeviceGroup;
import com.aicard.broadcast.domain.DeviceGroupMember;
import com.aicard.broadcast.repository.DeviceGroupMemberRepository;
import com.aicard.broadcast.repository.DeviceGroupRepository;
import com.aicard.common.domain.Device;
import com.aicard.common.repository.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** 群组管理：建群、列表、成员增删查（租户隔离由调用方传入 customerId/storeId）。 */
@Service
public class GroupService {

    private final DeviceGroupRepository groups;
    private final DeviceGroupMemberRepository members;
    private final DeviceRepository devices;

    public GroupService(DeviceGroupRepository groups, DeviceGroupMemberRepository members,
                        DeviceRepository devices) {
        this.groups = groups;
        this.members = members;
        this.devices = devices;
    }

    public DeviceGroup create(Long customerId, Long storeId, String code, String name) {
        return groups.save(DeviceGroup.builder()
                .code(code).name(name)
                .customerId(customerId).storeId(storeId)
                .createdAt(Instant.now()).build());
    }

    public List<DeviceGroup> list(Long customerId, Long storeId) {
        return groups.findByCustomerIdAndStoreId(customerId, storeId);
    }

    public DeviceGroupMember addMember(Long groupId, String deviceId, Long customerId, Long storeId) {
        groups.findByIdAndCustomerIdAndStoreId(groupId, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "group not found"));
        Device device = devices.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found"));
        if (!customerId.equals(device.getCustomerId()) || !storeId.equals(device.getStoreId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "device not in this tenant");
        }
        return members.findByGroupIdAndDeviceId(groupId, deviceId)
                .orElseGet(() -> members.save(DeviceGroupMember.builder()
                        .groupId(groupId).deviceId(deviceId).createdAt(Instant.now()).build()));
    }

    public void removeMember(Long groupId, String deviceId) {
        members.deleteByGroupIdAndDeviceId(groupId, deviceId);
    }

    public List<DeviceGroupMember> listMembers(Long groupId) {
        return members.findByGroupId(groupId);
    }
}
