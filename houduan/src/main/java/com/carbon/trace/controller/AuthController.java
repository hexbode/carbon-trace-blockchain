package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.service.UserService;
import org.springframework.http.HttpStatus;
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
 *   POST /api/auth/login           登录，body {username, password, role}，成功返回令牌、用户信息与
 *                                  mustChangePassword 标记；
 *   POST /api/auth/change-password 修改当前登录用户密码（强制改密会话唯一可写的业务接口）；
 *   GET  /api/auth/me              返回当前登录用户信息；
 *   POST /api/auth/logout          退出登录（JWT 无状态，此接口为空操作，供前端清理本地令牌）。
 *
 * 依赖：{@link UserService}（登录校验、令牌签发、改密与当前用户信息组装）。
 * 安全边界：
 *   1. 只有 /api/auth/login 在 WebMvcConfig 白名单中，/me、/logout 与 change-password 仍需有效令牌；
 *   2. 登录失败统一返回“账号或密码错误，或角色不匹配”，不区分账号不存在 / 密码错误 / 角色不符；
 *   3. 改密接口的账号取自令牌（UserContext），不接受客户端传入用户 id，避免越权改他人密码；
 *   4. 本控制器不写链，登录副作用（失败计数、令牌签发）与改密副作用都在 Service 内完成。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    /** 构造注入用户服务；控制器无状态，可单例复用 */
    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * POST /api/auth/login —— 登录并签发 JWT（位于免登录白名单，不经过 AuthInterceptor）。
     *
     * 参数（JSON body）：username 登录账号、password 明文密码、role 所选登录身份
     * （enterprise / auditor / regulator / admin，必须与账号实际角色一致）。
     * 返回：{token, user:{username, name, role, enterpriseId, orgId}, mustChangePassword}。
     * mustChangePassword 为 true 时令牌只能调用改密与退出接口，前端必须跳转改密页。
     * 错误码：400 账号或密码错误、角色不匹配、账号非 active；429 连续失败达到上限后的临时锁定；
     *        500 未知异常。
     * 安全边界：令牌由服务端签发并携带归属信息，前端只能通过 Authorization 头回传；
     * 本接口不触发链上写操作。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return userService.login(
                str(body.get("username")),
                str(body.get("password")),
                str(body.get("role")));
    }

    /**
     * POST /api/auth/change-password —— 修改当前登录用户密码。
     *
     * 该接口位于 AuthInterceptor 的强制改密白名单内：mustChangePassword=true 的令牌
     * 仅能调用本接口与 /logout，其余 /api/** 一律 403（code=PASSWORD_CHANGE_REQUIRED）。
     * 参数（JSON body）：oldPassword 原密码、newPassword 新密码
     * （≥ 8 位，同时包含大写字母、小写字母与数字，且不得与初始密码 123456 相同）。
     * 返回：空 Map。
     * 错误码：400 原密码不符或新密码不合规；401 未登录 / 令牌失效；404 账号已不存在。
     * 链上写：无。
     * 会话影响：成功后数据库 must_change_password 清零；旧令牌仍带强制改密声明，
     * 前端须清理本地令牌并重新登录，服务端据此拒绝旧会话继续访问业务接口。
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> body) {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        userService.changeOwnPassword(
                current.getUsername(),
                str(body.get("oldPassword")),
                str(body.get("newPassword")));
        return Collections.emptyMap();
    }

    /**
     * GET /api/auth/me —— 当前登录用户信息（需登录）。
     *
     * 返回：{username, name, role, enterpriseId, orgId}，直接取自令牌 claims，不额外查库。
     * 错误码：401 未登录 / 令牌失效。
     * 链上写：无。
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return userService.currentUser();
    }

    /**
     * POST /api/auth/logout —— 退出登录（需登录）。
     *
     * 返回：空 Map。JWT 无状态，服务端不保留会话且无法主动吊销令牌，
     * 前端必须自行删除本地令牌；该令牌在过期前仍可被使用，这是无状态鉴权的固有取舍。
     * 错误码：401 未登录 / 令牌失效。
     * 链上写：无。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Collections.emptyMap();
    }

    /**
     * 安全地把请求体字段转字符串。
     *
     * @param value 任意 JSON 值
     * @return 字段缺失时为 null，避免 String.valueOf(null) 得到 "null" 字样被当作有效账号
     */
    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
