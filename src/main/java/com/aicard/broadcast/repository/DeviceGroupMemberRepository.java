package com.aicard.broadcast.repository;

import com.aicard.broadcast.domain.DeviceGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceGroupMemberRepository extends JpaRepository<DeviceGroupMember, Long> {

    List<DeviceGroupMember> findByGroupId(Long groupId);

    Optional<DeviceGroupMember> findByGroupIdAndDeviceId(Long groupId, String deviceId);

    void deleteByGroupIdAndDeviceId(Long groupId, String deviceId);
}
