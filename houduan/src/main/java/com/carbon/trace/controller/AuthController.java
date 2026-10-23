package com.carbon.trace.controller;

import com.carbon.trace.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 鉴权接口（login 位于 WebMvcConfig 拦截器放行白名单，无需登录即可访问）。
 *
 * 暴露接口：
 *   POST /api/auth/login   登录，body {username, password, role}，成功返回令牌与用户信息；
 *   GET  /api/auth/me      返回当前登录用户信息；
 *   POST /api/auth/logout  退出登录（JWT 无状态，此接口为空操作，供前端清理本地令牌）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 登录：body {username, password, role}；role 表示用户所选登录身份。
     * 成功返回含 token 与用户信息的 Map；账号/密码错误或角色不匹配由 Service 抛业务异常统一处理。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return userService.login(
                str(body.get("username")),
                str(body.get("password")),
                str(body.get("role")));
    }

    /** 返回当前登录用户信息（由 AuthInterceptor 从 token 解析并写入 UserContext） */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return userService.currentUser();
    }

    /** 退出登录：JWT 无状态，服务端不保留会话，仅返回空 Map，前端自行清除本地令牌 */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Collections.emptyMap();
    }

    /** 安全地把请求体字段转字符串：字段缺失返回 null，避免空指针 */
    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
