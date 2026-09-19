package com.aicard.translation.qualify;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 后台质量门控词表管理：品牌词表 + 短词白名单的增删查（租户隔离）。 */
@RestController
@RequestMapping("/api/qualify-terms")
public class QualifyTermController {

    private final QualifyTermService service;

    public QualifyTermController(QualifyTermService service) {
        this.service = service;
    }

    @GetMapping
    public List<QualifyTermDto> list(@RequestParam String type) {
        Tenant t = TenantContext.require();
        return service.list(t.customerId(), t.storeId(), type).stream().map(QualifyTermDto::from).toList();
    }

    @PostMapping
    public QualifyTermDto add(@RequestBody QualifyTermRequest req) {
        Tenant t = TenantContext.require();
        return QualifyTermDto.from(service.add(t.customerId(), t.storeId(), req.type(), req.term()));
    }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        service.remove(id);
    }
}
