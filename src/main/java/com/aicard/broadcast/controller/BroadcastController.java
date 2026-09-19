package com.aicard.broadcast.controller;

import com.aicard.broadcast.dto.BroadcastDto;
import com.aicard.broadcast.dto.BroadcastRequest;
import com.aicard.broadcast.dto.DeliveryDto;
import com.aicard.broadcast.repository.BroadcastDeliveryRepository;
import com.aicard.broadcast.repository.BroadcastRepository;
import com.aicard.broadcast.service.BroadcastService;
import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 广播后台 API：发广播、查广播列表、查投递/回执状态。 */
@RestController
@RequestMapping("/api")
public class BroadcastController {

    private final BroadcastService broadcasts;
    private final BroadcastRepository broadcastRepo;
    private final BroadcastDeliveryRepository deliveryRepo;

    public BroadcastController(BroadcastService broadcasts, BroadcastRepository broadcastRepo,
                               BroadcastDeliveryRepository deliveryRepo) {
        this.broadcasts = broadcasts;
        this.broadcastRepo = broadcastRepo;
        this.deliveryRepo = deliveryRepo;
    }

    @PostMapping("/groups/{groupId}/broadcasts")
    public BroadcastDto broadcast(@PathVariable Long groupId, @RequestBody BroadcastRequest req) {
        Tenant t = TenantContext.require();
        return BroadcastDto.from(broadcasts.broadcast(groupId, req.sourceText(), req.sourceLanguage(),
                t.customerId(), t.storeId()));
    }

    @GetMapping("/groups/{groupId}/broadcasts")
    public List<BroadcastDto> list(@PathVariable Long groupId) {
        return broadcastRepo.findByGroupIdOrderByCreatedAtDesc(groupId).stream().map(BroadcastDto::from).toList();
    }

    @GetMapping("/broadcasts/{id}/deliveries")
    public List<DeliveryDto> deliveries(@PathVariable Long id) {
        return deliveryRepo.findByBroadcastId(id).stream().map(DeliveryDto::from).toList();
    }
}
