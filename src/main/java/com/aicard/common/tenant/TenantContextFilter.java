package com.aicard.common.tenant;

import jakarta.servlet.*;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 每请求清理租户上下文。真正的 tenant 写入由鉴权层（P3 gateway）在鉴权成功后
 * 调用 TenantContext.set(...) 完成；本 Filter 仅在请求结束时清理，防止线程池复用串租户。
 */
@Component
public class TenantContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
