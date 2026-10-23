package com.carbon.trace.common;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 鉴权拦截器：在请求进入 Controller 前校验 Authorization: Bearer <token> 请求头。
 *
 * 职责：解析并校验 JWT，把其中的用户名、角色、企业/机构编号封装为 LoginUser 写入
 * UserContext（ThreadLocal），供后续 Controller / Service 获取当前登录人做鉴权与数据隔离；
 * 请求处理完成后清空 UserContext，避免线程复用导致串数据。
 * 判定语义：请求头缺失、不是 Bearer 格式或令牌解析失败，一律返回 401 JSON 并中断请求
 * （preHandle 返回 false）。
 * 注册范围见 WebMvcConfig：拦截 /api/**，放行登录、公共查询端与公众免登录的认证校验接口。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 请求前置处理：校验并解析令牌，成功则把用户信息写入 UserContext 并返回 true。
     * 返回 false 表示请求被拦截（已写 401 响应），Spring MVC 不再调用后续处理器。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return false;
        }
        String token = header.substring(7).trim();
        try {
            Claims claims = jwtUtil.parse(token);
            LoginUser user = new LoginUser(
                    claims.getSubject(),
                    claims.get("name", String.class),
                    claims.get("role", String.class),
                    claims.get("enterpriseId", String.class),
                    claims.get("orgId", String.class));
            UserContext.set(user);
            return true;
        } catch (Exception e) {
            writeUnauthorized(response);
            return false;
        }
    }

    /** 请求结束（无论成功与否）清空线程上下文，防止 ThreadLocal 残留导致后续请求串号 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /** 统一写出 401 未登录响应体，拦截后直接结束请求，不再进入后续处理器 */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"未登录或登录已过期\"}");
    }
}
