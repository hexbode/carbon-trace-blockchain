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
 * 强制改密门禁：令牌声明 mustChangePassword=true 的会话，除
 * /api/auth/change-password 与 /api/auth/logout 外的所有 /api/** 请求一律返回 403
 * （响应体带 code=PASSWORD_CHANGE_REQUIRED），避免弱密码会话在改密前访问业务数据。
 * 注册范围见 WebMvcConfig：拦截 /api/**，放行登录、公共查询端与公众免登录的认证校验接口。
 *
 * 依赖：{@link JwtUtil}（签名与有效期校验的唯一入口）。
 * 安全边界：
 *   1. 本类只解决“身份是否可信”，不做角色与企业归属判断；角色校验在各 Controller，数据隔离在 UserScopes；
 *   2. 令牌缺失、非 Bearer 前缀、签名错误、已过期等所有失败情形一律返回同一个 401 文案，
 *      不区分原因，避免为令牌探测提供额外信息；
 *   3. 401 / 403 响应由本类直接写出，不经过 GlobalExceptionHandler（拦截器阶段尚未进入异常处理链）；
 *   4. 拦截范围仅 /api/**，非该前缀的路径（如静态资源 /uploads/**）不经过本类。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 强制改密会话唯一允许的业务写接口（仍需有效令牌） */
    private static final String CHANGE_PASSWORD_PATH = "/api/auth/change-password";

    /** 退出登录接口始终放行，便于前端清理本地令牌后重新登录 */
    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final JwtUtil jwtUtil;

    /** 构造注入 JWT 工具；拦截器本身无状态，可作为单例被所有请求线程共享 */
    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 请求前置处理：校验并解析令牌，成功则把用户信息写入 UserContext 并返回 true。
     * 返回 false 表示请求被拦截（已写 401 响应），Spring MVC 不再调用后续处理器。
     *
     * @param request  当前请求，从 Authorization 头取令牌
     * @param response 当前响应，鉴权失败时直接写入 401 JSON
     * @param handler  将被调用的处理器（本类不关心）
     * @return true 表示放行；false 表示已拦截并结束请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        // 必须严格匹配 "Bearer " 前缀；不接受 query 参数或 Cookie 中的令牌
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return false;
        }
        // 去掉 "Bearer " 前缀（7 个字符）后再解析，避免把空格带进令牌
        String token = header.substring(7).trim();
        try {
            Claims claims = jwtUtil.parse(token);
            // 强制改密门禁：弱密码会话只能改密或退出，其余请求一律 403
            if (Boolean.TRUE.equals(claims.get("mustChangePassword", Boolean.class))
                    && !isPasswordChangeAllowed(request)) {
                writePasswordChangeRequired(response);
                return false;
            }
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

    /**
     * 判断当前请求是否属于强制改密会话可访问的路径。
     *
     * @param request 当前请求
     * @return true 表示改密或退出接口，放行；false 表示其余接口，需返回 403
     */
    private boolean isPasswordChangeAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return CHANGE_PASSWORD_PATH.equals(path) || LOGOUT_PATH.equals(path);
    }

    /**
     * 统一写出 403 强制改密响应体。
     * code 字段供前端精确识别并跳转改密页，不必解析中文文案。
     */
    private void writePasswordChangeRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"首次登录需先修改密码\"}");
    }
}
