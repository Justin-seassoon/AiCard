package com.aicard.broadcast.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** 群成员：群与设备的关联。 */
@Entity
@Table(name = "device_group_member")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeviceGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
