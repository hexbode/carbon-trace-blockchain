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
 *
 * 响应头策略：
 *   - 全站（API 与静态资源统一）：X-Content-Type-Options: nosniff；
 *   - 仅 /uploads/**：Content-Security-Policy: default-src 'none'; sandbox（禁止加载任何外部资源、
 *     禁止脚本执行、以沙箱方式对待文档）以及 X-Frame-Options: DENY（禁止被任意页面 iframe 嵌套）；
 *   - 不下发 HSTS / 证书相关头：TLS 终止与 HSTS 由外层反向代理统一负责，避免重复与冲突；
 *   - 响应头在 chain.doFilter 之前写入，保证后续异常响应、静态资源响应同样带上安全头。
 *
 * 安全边界：本过滤器只做“浏览器侧加固”，不负责鉴权与文件类型校验；真正的上传校验在 FileController。
 */
@Component
public class SecurityHeaderFilter extends OncePerRequestFilter {

    /** 上传文件目录的访问前缀 */
    private static final String UPLOAD_PREFIX = "/uploads/";

    /**
     * 为每个请求统一写入安全响应头。
     * 继承 OncePerRequestFilter，保证同一请求（含 forward / include）只执行一次，避免重复设置响应头。
     *
     * @param request  当前请求，用 URI 前缀判断是否为上传资源
     * @param response 当前响应，在放行前写入安全头
     * @param chain    过滤器链，写完响应头后继续放行
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 全站生效：禁止浏览器对响应做 MIME 嗅探（即使上传了伪装文件也不会被当成 HTML 执行）
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getRequestURI().startsWith(UPLOAD_PREFIX)) {
            // 上传内容只允许作为静态资源返回，禁止脚本执行、禁止被其他页面内嵌
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            response.setHeader("X-Frame-Options", "DENY");
        }
        chain.doFilter(request, response);
    }
}
