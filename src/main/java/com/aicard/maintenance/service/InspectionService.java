package com.aicard.maintenance.service;

import com.aicard.maintenance.domain.InspectionRecord;
import com.aicard.maintenance.domain.InspectionTask;
import com.aicard.maintenance.dto.RecordRequest;
import com.aicard.maintenance.dto.TaskRequest;
import com.aicard.maintenance.repository.InspectionRecordRepository;
import com.aicard.maintenance.repository.InspectionTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** 点检管理：派任务、提交记录并闭环、关闭任务、查询记录。 */
@Service
public class InspectionService {

    private final InspectionTaskRepository tasks;
    private final InspectionRecordRepository records;

    public InspectionService(InspectionTaskRepository tasks, InspectionRecordRepository records) {
        this.tasks = tasks;
        this.records = records;
    }

    public InspectionTask createTask(Long customerId, Long storeId, TaskRequest req) {
        if (tasks.existsByCustomerIdAndStoreIdAndTaskNo(customerId, storeId, req.taskNo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task_no already exists");
        }
        return tasks.save(InspectionTask.builder()
                .taskNo(req.taskNo()).deviceId(req.deviceId()).title(req.title())
                .checklist(req.checklist()).status(InspectionTask.STATUS_PENDING)
                .scheduledDate(req.scheduledDate()).assignee(req.assignee())
                .customerId(customerId).storeId(storeId).createdAt(Instant.now()).build());
    }

    public List<InspectionTask> list(Long customerId, Long storeId, String status) {
        if (status == null || status.isBlank()) {
            return tasks.findByCustomerIdAndStoreId(customerId, storeId);
        }
        return tasks.findByCustomerIdAndStoreIdAndStatus(customerId, storeId, status);
    }

    /** 提交点检记录：写入结果、置任务为 done。 */
    public InspectionRecord submitRecord(Long taskId, Long customerId, Long storeId, RecordRequest req) {
        InspectionTask task = tasks.findByIdAndCustomerIdAndStoreId(taskId, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        if (InspectionTask.STATUS_CLOSED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task already closed");
        }
        InspectionRecord record = records.save(InspectionRecord.builder()
                .taskId(taskId).deviceId(req.deviceId() != null ? req.deviceId() : task.getDeviceId())
                .results(req.results()).status(InspectionTask.STATUS_DONE)
                .inspectedBy(req.inspectedBy()).inspectedAt(Instant.now()).note(req.note())
                .customerId(customerId).storeId(storeId).createdAt(Instant.now()).build());
        task.setStatus(InspectionTask.STATUS_DONE);
        tasks.save(task);
        return record;
    }

    public InspectionTask closeTask(Long taskId, Long customerId, Long storeId) {
        InspectionTask task = tasks.findByIdAndCustomerIdAndStoreId(taskId, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        task.setStatus(InspectionTask.STATUS_CLOSED);
        return tasks.save(task);
    }

    public List<InspectionRecord> listRecords(Long taskId, Long customerId, Long storeId) {
        tasks.findByIdAndCustomerIdAndStoreId(taskId, customerId, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        return records.findByTaskId(taskId);
    }
}
