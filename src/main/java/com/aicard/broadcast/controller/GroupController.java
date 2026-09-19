package com.aicard.broadcast.controller;

import com.aicard.broadcast.domain.DeviceGroupMember;
import com.aicard.broadcast.dto.GroupDto;
import com.aicard.broadcast.dto.GroupRequest;
import com.aicard.broadcast.dto.MemberRequest;
import com.aicard.broadcast.service.GroupService;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 群组管理后台 API。 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @PostMapping
    public GroupDto create(@RequestBody GroupRequest req) {
        Tenant t = TenantContext.require();
        return GroupDto.from(groups.create(t.customerId(), t.storeId(), req.code(), req.name()));
    }

    @GetMapping
    public List<GroupDto> list() {
        Tenant t = TenantContext.require();
        return groups.list(t.customerId(), t.storeId()).stream().map(GroupDto::from).toList();
    }

    @PostMapping("/{id}/members")
    public void addMember(@PathVariable Long id, @RequestBody MemberRequest req) {
        Tenant t = TenantContext.require();
        groups.addMember(id, req.deviceId(), t.customerId(), t.storeId());
    }

    @DeleteMapping("/{id}/members/{deviceId}")
    public void removeMember(@PathVariable Long id, @PathVariable String deviceId) {
        groups.removeMember(id, deviceId);
    }

    @GetMapping("/{id}/members")
    public List<String> listMembers(@PathVariable Long id) {
        return groups.listMembers(id).stream().map(DeviceGroupMember::getDeviceId).toList();
    }
}
