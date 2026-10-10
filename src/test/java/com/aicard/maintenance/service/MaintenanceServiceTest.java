package com.aicard.maintenance.service;

import com.aicard.maintenance.domain.MaintenanceOrder;
import com.aicard.maintenance.dto.OrderRequest;
import com.aicard.maintenance.repository.MaintenanceOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaintenanceServiceTest {

    private final MaintenanceOrderRepository orders = mock(MaintenanceOrderRepository.class);
    private final MaintenanceService service = new MaintenanceService(orders);

    private OrderRequest req() {
        return new OrderRequest("MO-001", "dev-a", "麦克风杂音", "前台收银设备", "店长");
    }

    @Test
    void createsOrderWithOpenStatus() {
        when(orders.existsByCustomerIdAndStoreIdAndOrderNo(10L, 20L, "MO-001")).thenReturn(false);
        when(orders.save(any())).thenAnswer(inv -> {
            MaintenanceOrder o = inv.getArgument(0);
            o.setId(1L);
            return o;
        });

        MaintenanceOrder result = service.create(10L, 20L, req());

        assertThat(result.getStatus()).isEqualTo(MaintenanceOrder.STATUS_OPEN);
        assertThat(result.getCustomerId()).isEqualTo(10L);
        assertThat(result.getStoreId()).isEqualTo(20L);
    }

    @Test
    void rejectsDuplicateOrderNo() {
        when(orders.existsByCustomerIdAndStoreIdAndOrderNo(10L, 20L, "MO-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(10L, 20L, req()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void transitionsOpenToProcessing() {
        MaintenanceOrder order = MaintenanceOrder.builder().id(1L).orderNo("MO-001")
                .status(MaintenanceOrder.STATUS_OPEN).customerId(10L).storeId(20L).build();
        when(orders.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceOrder result = service.transition(1L, 10L, 20L, MaintenanceOrder.STATUS_PROCESSING);

        assertThat(result.getStatus()).isEqualTo(MaintenanceOrder.STATUS_PROCESSING);
    }

    @Test
    void rejectsInvalidTransition() {
        MaintenanceOrder order = MaintenanceOrder.builder().id(1L).orderNo("MO-001")
                .status(MaintenanceOrder.STATUS_DONE).customerId(10L).storeId(20L).build();
        when(orders.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.transition(1L, 10L, 20L, MaintenanceOrder.STATUS_OPEN))
                .isInstanceOf(ResponseStatusException.class);
    }
}
