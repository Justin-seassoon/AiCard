package com.aicard.maintenance.controller;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.aicard.maintenance.dto.InspectionRecordDto;
import com.aicard.maintenance.dto.InspectionTaskDto;
import com.aicard.maintenance.dto.RecordRequest;
import com.aicard.maintenance.dto.TaskRequest;
import com.aicard.maintenance.service.InspectionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 点检任务后台 API。 */
@RestController
@RequestMapping("/api/inspection/tasks")
public class InspectionController {

    private final InspectionService service;

    public InspectionController(InspectionService service) {
        this.service = service;
    }

    @PostMapping
    public InspectionTaskDto create(@RequestBody TaskRequest req) {
        Tenant t = TenantContext.require();
        return InspectionTaskDto.from(service.createTask(t.customerId(), t.storeId(), req));
    }

    @GetMapping
    public List<InspectionTaskDto> list(@RequestParam(required = false) String status) {
        Tenant t = TenantContext.require();
        return service.list(t.customerId(), t.storeId(), status).stream().map(InspectionTaskDto::from).toList();
    }

    @PostMapping("/{id}/records")
    public InspectionRecordDto submit(@PathVariable Long id, @RequestBody RecordRequest req) {
        Tenant t = TenantContext.require();
        return InspectionRecordDto.from(service.submitRecord(id, t.customerId(), t.storeId(), req));
    }

    @PostMapping("/{id}/close")
    public InspectionTaskDto close(@PathVariable Long id) {
        Tenant t = TenantContext.require();
        return InspectionTaskDto.from(service.closeTask(id, t.customerId(), t.storeId()));
    }

    @GetMapping("/{id}/records")
    public List<InspectionRecordDto> records(@PathVariable Long id) {
        Tenant t = TenantContext.require();
        return service.listRecords(id, t.customerId(), t.storeId()).stream().map(InspectionRecordDto::from).toList();
    }
}
