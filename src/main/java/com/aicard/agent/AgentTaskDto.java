package com.aicard.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/** 任务视图（steps 以 JSON 数组原样返回，便于前端渲染时间线）。 */
public record AgentTaskDto(Long id, String goal, String mode, String status, JsonNode steps,
                           String result, String error, boolean hasArtifact, String artifactName,
                           Instant createdAt) {

    public static AgentTaskDto from(AgentTask t, ObjectMapper mapper) {
        JsonNode steps;
        try {
            steps = (t.getSteps() == null || t.getSteps().isBlank())
                    ? mapper.createArrayNode() : mapper.readTree(t.getSteps());
        } catch (Exception e) {
            steps = mapper.createArrayNode();
        }
        return new AgentTaskDto(t.getId(), t.getGoal(), t.getMode(), t.getStatus(), steps,
                t.getResult(), t.getError(), t.getArtifactBase64() != null, t.getArtifactName(), t.getCreatedAt());
    }
}
