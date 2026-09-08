package com.aicard.admin.auth;

import com.aicard.common.tenant.Tenant;
import com.aicard.common.tenant.TenantContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * demo 版后台 RBAC：从请求头 X-Customer-Id / X-Store-Id 推导登录用户的租户 scope 写入 TenantContext，
 * 后台 controller 只信任这里的租户，不信任请求体里的 customer/store 字段。真实 SSO/登录后置。
 */
@Component
public class AdminContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String customerId = req.getHeader("X-Customer-Id");
        String storeId = req.getHeader("X-Store-Id");
        if (customerId != null && storeId != null) {
            TenantContext.set(Tenant.of(Long.valueOf(customerId), Long.valueOf(storeId)));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
