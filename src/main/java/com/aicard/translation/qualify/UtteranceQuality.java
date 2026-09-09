package com.aicard.translation.qualify;

/**
 * 游客发言质量分类（spec §7.4 ②）：仅 NORMAL 参与 visitor_language 记忆，
 * 其余四类过滤（翻译照常播放，但不污染语言记忆）。
 */
public enum UtteranceQuality {
    NORMAL,     // 正常有效语音
    SHORT,      // 语音时长不足或空文本
    NUMERIC,    // 纯数字/符号
    BRAND,      // 命中品牌名/型号词表
    WHITELIST   // 命中短词白名单（直通翻译、不漂移）
}
