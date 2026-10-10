package com.aicard.maintenance.service;

import com.aicard.maintenance.domain.MaintenanceOrder;
import com.aicard.maintenance.dto.OrderRequest;
import com.aicard.maintenance.repository.MaintenanceOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** 维修单管理：建单、列表、状态机流转（租户隔离由调用方传入 customerId/storeId）。 */
@Service
public class MaintenanceService {

    private final MaintenanceOrderRepository orders;

    public MaintenanceService(MaintenanceOrderRepository orders) {
        this.orders = orders;
    }

    public MaintenanceOrder create(Long customerId, Long storeId, OrderRequest req) {
        if (orders.existsByCustomerIdAndStoreIdAndOrderNo(customerId, storeId, req.orderNo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order_no already exists");
        }
        return orders.save(MaintenanceOrder.builder()
                .orderNo(req.orderNo()).deviceId(req.deviceId()).title(req.title())
                .description(req.description()).status(MaintenanceOrder.STATUS_OPEN)
                .reporter(req.reporter()).customerId(customerId).storeId(storeId)
                .createdAt(Instant.now()).build());
    }

    public List<MaintenanceOrder> list(Long customerId, Long storeId, String status) {
        if (status == null || status.isBlank()) {
            return orders.findByCustomerIdAndStoreId(customerId, storeId);
        }
        return orders.findByCustomerIdAndStoreIdAndStatus(customerId, storeId, status);
    }

    public MaintenanceOrder transition(Long id, Long customerId, Long storeId, String targetStatus) {
        MaintenanceOrder order = orders.findByIdAndCustomerIdAndStoreId(id, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!isValidTransition(order.getStatus(), targetStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid transition: " + order.getStatus() + " -> " + targetStatus);
        }
        order.setStatus(targetStatus);
        return orders.save(order);
    }

    private boolean isValidTransition(String from, String to) {
        return switch (from) {
            case MaintenanceOrder.STATUS_OPEN ->
                    MaintenanceOrder.STATUS_PROCESSING.equals(to) || MaintenanceOrder.STATUS_CANCELLED.equals(to);
            case MaintenanceOrder.STATUS_PROCESSING ->
                    MaintenanceOrder.STATUS_DONE.equals(to) || MaintenanceOrder.STATUS_CANCELLED.equals(to);
            default -> false; // done / cancelled 为终态，不再流转
        };
    }
}
