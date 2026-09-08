package com.aicard.admin.dto;

import java.util.List;

/** 知识库文档导入请求：title + version + domain（skb/vkb，缺省 skb）+ 切片文本列表（embedding 由后端生成）。 */
public record DocumentImportRequest(String title, String version, String domain, List<ChunkIn> chunks) {
    public record ChunkIn(String text) {}
}
