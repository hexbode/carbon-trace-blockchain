package com.carbon.trace.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 响应安全头过滤器
 *
 * 作用：
 *   1. 全站返回 X-Content-Type-Options: nosniff，禁止浏览器按内容猜测类型；
 *   2. 上传文件的访问路径（/uploads/**）额外返回 CSP 与 X-Frame-Options，
 *      即使攻击者上传了伪装成图片的 HTML/脚本，浏览器也不会执行或嵌入渲染。
 */
@Component
public class SecurityHeaderFilter extends OncePerRequestFilter {

    /** 上传文件目录的访问前缀 */
    private static final String UPLOAD_PREFIX = "/uploads/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getRequestURI().startsWith(UPLOAD_PREFIX)) {
            // 上传内容只允许作为静态资源返回，禁止脚本执行、禁止被其他页面内嵌
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            response.setHeader("X-Frame-Options", "DENY");
        }
        chain.doFilter(request, response);
    }
}
