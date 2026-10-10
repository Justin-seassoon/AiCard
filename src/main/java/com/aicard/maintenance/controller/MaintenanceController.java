package com.aicard.maintenance.controller;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.maintenance.dto.OrderDto;
import com.aicard.maintenance.dto.OrderRequest;
import com.aicard.maintenance.dto.TransitionRequest;
import com.aicard.maintenance.service.MaintenanceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 维修单后台 API。 */
@RestController
@RequestMapping("/api/maintenance/orders")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @PostMapping
    public OrderDto create(@RequestBody OrderRequest req) {
        Tenant t = TenantContext.require();
        return OrderDto.from(service.create(t.customerId(), t.storeId(), req));
    }

    @GetMapping
    public List<OrderDto> list(@RequestParam(required = false) String status) {
        Tenant t = TenantContext.require();
        return service.list(t.customerId(), t.storeId(), status).stream().map(OrderDto::from).toList();
    }

    @PostMapping("/{id}/transition")
    public OrderDto transition(@PathVariable Long id, @RequestBody TransitionRequest req) {
        Tenant t = TenantContext.require();
        return OrderDto.from(service.transition(id, t.customerId(), t.storeId(), req.status()));
    }
}
