package com.aicard.agent;

/**
 * 单步执行结果。{@code artifact} 非空表示该步交付了一个文件（会被存为任务产物）。
 */
public record ToolResult(String output, byte[] artifact, String artifactName, String artifactMime) {

    public static ToolResult of(String output) {
        return new ToolResult(output, null, null, null);
    }

    public static ToolResult artifact(String output, byte[] artifact, String name, String mime) {
        return new ToolResult(output, artifact, name, mime);
    }
}
