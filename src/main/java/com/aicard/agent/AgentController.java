package com.aicard.agent;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** ② agent 执行后台接口：建任务（异步执行）/ 列表 / 详情 / 下载产物。 */
@RestController
@RequestMapping("/api/agent/tasks")
public class AgentController {

    private final AgentTaskRepository tasks;
    private final AgentRunner runner;
    private final ObjectMapper mapper;

    public AgentController(AgentTaskRepository tasks, AgentRunner runner, ObjectMapper mapper) {
        this.tasks = tasks;
        this.runner = runner;
        this.mapper = mapper;
    }

    @PostMapping
    public AgentTaskDto create(@RequestBody CreateRequest req) {
        Tenant t = TenantContext.require();

        String goal = req.goal() == null ? "" : req.goal().trim();
        if (goal.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "goal required");
        }
        if (goal.length() > 2000) {
            goal = goal.substring(0, 2000);
        }
        String mode = (req.mode() == null || req.mode().isBlank()) ? AgentTask.MODE_AUTO : req.mode();
        if (!List.of(AgentTask.MODE_AUTO, AgentTask.MODE_LLM, AgentTask.MODE_RULE).contains(mode)) {
            mode = AgentTask.MODE_AUTO;
        }

        AgentTask task = tasks.save(AgentTask.builder()
                .customerId(t.customerId()).storeId(t.storeId())
                .goal(goal)
                .mode(mode)
                .status(AgentTask.STATUS_PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        runner.runAsync(task.getId());
        return AgentTaskDto.from(task, mapper);
    }

    @GetMapping
    public List<AgentTaskDto> list() {
        Tenant t = TenantContext.require();
        return tasks.findByCustomerIdAndStoreIdOrderByIdDesc(t.customerId(), t.storeId())
                .stream().map(x -> AgentTaskDto.from(x, mapper)).toList();
    }

    @GetMapping("/{id}")
    public AgentTaskDto get(@PathVariable Long id) {
        return AgentTaskDto.from(scoped(id), mapper);
    }

    @GetMapping("/{id}/artifact")
    public ResponseEntity<byte[]> artifact(@PathVariable Long id) {
        AgentTask task = scoped(id);
        if (task.getArtifactBase64() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no artifact");
        }
        byte[] body = Base64.getDecoder().decode(task.getArtifactBase64());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(task.getArtifactMime()));
        headers.setContentDispositionFormData("attachment",
                task.getArtifactName() == null ? "artifact" : task.getArtifactName());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /** 按当前租户取任务（防跨租户访问）。 */
    private AgentTask scoped(Long id) {
        Tenant t = TenantContext.require();
        return tasks.findById(id)
                .filter(x -> t.customerId().equals(x.getCustomerId()) && t.storeId().equals(x.getStoreId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
    }

    public record CreateRequest(String goal, String mode) {}
}
