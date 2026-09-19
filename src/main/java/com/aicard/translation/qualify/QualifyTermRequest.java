package com.aicard.translation.qualify;

/** 添加词条请求。type：whitelist | brand。 */
public record QualifyTermRequest(String type, String term) {}
