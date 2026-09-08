package com.aicard.admin.auth;

/** 后台登录上下文（demo 预留，真实 SSO/登录后置）。 */
public record AdminContext(String userId, Role role, Long customerId, Long storeId) {}
