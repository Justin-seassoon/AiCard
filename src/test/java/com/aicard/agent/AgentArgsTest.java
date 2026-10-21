package com.aicard.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentArgsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsMissingRequiredArgs() throws Exception {
        var node = mapper.readTree("{\"title\":\"x\"}");
        assertThat(AgentArgs.missing(node, List.of("title", "lines"))).containsExactly("lines");
        assertThat(AgentArgs.missing(node, List.of("title"))).isEmpty();
        assertThat(AgentArgs.missing(null, List.of("a"))).containsExactly("a");
    }

    @Test
    void parsesInvalidJsonAsEmptyObject() {
        assertThat(AgentArgs.parse("not-json").isObject()).isTrue();
        assertThat(AgentArgs.parse(null).isObject()).isTrue();
    }
}
