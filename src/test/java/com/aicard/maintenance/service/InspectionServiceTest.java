package com.aicard.maintenance.service;

import com.aicard.maintenance.domain.InspectionRecord;
import com.aicard.maintenance.domain.InspectionTask;
import com.aicard.maintenance.dto.RecordRequest;
import com.aicard.maintenance.dto.TaskRequest;
import com.aicard.maintenance.repository.InspectionRecordRepository;
import com.aicard.maintenance.repository.InspectionTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectionServiceTest {

    private final InspectionTaskRepository tasks = mock(InspectionTaskRepository.class);
    private final InspectionRecordRepository records = mock(InspectionRecordRepository.class);
    private final InspectionService service = new InspectionService(tasks, records);

    private TaskRequest taskReq() {
        return new TaskRequest("IT-001", "dev-a", "每日点检", "[{\"item\":\"麦克风\",\"required\":true}]", null, "店长");
    }

    @Test
    void createsTaskWithPendingStatus() {
        when(tasks.existsByCustomerIdAndStoreIdAndTaskNo(10L, 20L, "IT-001")).thenReturn(false);
        when(tasks.save(any())).thenAnswer(inv -> {
            InspectionTask t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });

        InspectionTask result = service.createTask(10L, 20L, taskReq());

        assertThat(result.getStatus()).isEqualTo(InspectionTask.STATUS_PENDING);
    }

    @Test
    void submitRecordMarksTaskDone() {
        InspectionTask task = InspectionTask.builder().id(1L).taskNo("IT-001")
                .status(InspectionTask.STATUS_PENDING).customerId(10L).storeId(20L).build();
        when(tasks.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(task));
        when(records.save(any())).thenAnswer(inv -> {
            InspectionRecord r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InspectionRecord result = service.submitRecord(1L, 10L, 20L,
                new RecordRequest(null, "[{\"item\":\"麦克风\",\"ok\":true}]", "店长", "正常"));

        assertThat(result.getTaskId()).isEqualTo(1L);
        assertThat(task.getStatus()).isEqualTo(InspectionTask.STATUS_DONE);
    }

    @Test
    void rejectsRecordOnClosedTask() {
        InspectionTask task = InspectionTask.builder().id(1L).taskNo("IT-001")
                .status(InspectionTask.STATUS_CLOSED).customerId(10L).storeId(20L).build();
        when(tasks.findByIdAndCustomerIdAndStoreId(1L, 10L, 20L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.submitRecord(1L, 10L, 20L,
                new RecordRequest(null, "[]", "店长", null)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
