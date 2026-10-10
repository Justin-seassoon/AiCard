package com.aicard.notification.controller;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.notification.dto.NotificationDeliveryDto;
import com.aicard.notification.dto.NotificationDto;
import com.aicard.notification.dto.NotificationRequest;
import com.aicard.notification.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 运营通知后台 API。 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    public NotificationDto create(@RequestBody NotificationRequest req) {
        Tenant t = TenantContext.require();
        return NotificationDto.from(service.create(t.customerId(), t.storeId(), req));
    }

    @GetMapping
    public List<NotificationDto> list() {
        Tenant t = TenantContext.require();
        return service.list(t.customerId(), t.storeId()).stream().map(NotificationDto::from).toList();
    }

    @PostMapping("/{id}/cancel")
    public NotificationDto cancel(@PathVariable Long id) {
        Tenant t = TenantContext.require();
        return NotificationDto.from(service.cancel(id, t.customerId(), t.storeId()));
    }

    @GetMapping("/{id}/deliveries")
    public List<NotificationDeliveryDto> deliveries(@PathVariable Long id) {
        return service.listDeliveries(id).stream().map(NotificationDeliveryDto::from).toList();
    }
}
