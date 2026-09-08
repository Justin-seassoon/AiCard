package com.aicard.skb.model;

import java.util.List;

/** LLM 生成结果：答案 + 引用的命中片段 ID（校验段强制其 ⊆ 命中集合）。 */
public record LlmResult(String answer, List<Long> citedChunkIds) {}
