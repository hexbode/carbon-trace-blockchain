package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.User;
import com.carbon.trace.service.UserService;
import com.carbon.trace.service.UserSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户管理接口：账号的增删改查、密码重置/修改与账号设置维护。
 *
 * 暴露接口：
 *   GET    /api/users                         用户列表（可按 username / role 过滤）；
 *   POST   /api/users                         新建用户；
 *   PUT    /api/users/{id}                    修改用户资料；
 *   DELETE /api/users/{id}                    删除用户；
 *   POST   /api/users/{id}/reset-password     重置密码为初始值 123456；
 *   PUT    /api/users/{id}/password           修改密码（body {oldPassword, newPassword}）；
 *   GET    /api/users/{id}/settings           查询账号设置（4 个布尔开关）；
 *   PUT    /api/users/{id}/settings           更新账号设置。
 * 角色约定：新增、删除、重置密码等管理操作仅 regulator / admin；修改资料、改密、账号设置
 * 允许本人操作（requireSelfOrManager）；用户目录含联系方式等敏感字段，仅监管/管理员可查。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserSettingsService userSettingsService;

    public UserController(UserService userService, UserSettingsService userSettingsService) {
        this.userService = userService;
        this.userSettingsService = userSettingsService;
    }

    /** 用户列表：可按 username、role 过滤；目录含联系方式等敏感字段，仅监管/管理员可查（否则 403） */
    @GetMapping
    public List<User> list(@RequestParam(required = false) String username,
                           @RequestParam(required = false) String role) {
        // 用户目录含账号、联系方式等信息，仅监管/管理员可查
        requireManagerRole();
        return userService.list(username, role);
    }

    /** 新建用户，body 为 User 实体（含 username/password/role/name 等字段）；仅监管/管理员可执行 */
    @PostMapping
    public User create(@RequestBody User user) {
        requireManagerRole();
        return userService.create(user);
    }

    /** 修改用户资料：监管/管理员可改任意用户，普通用户仅能改本人（否则 403） */
    @PutMapping("/{id}")
    public User update(@PathVariable Integer id, @RequestBody User user) {
        requireSelfOrManager(id);
        return userService.update(id, user);
    }

    /** 删除用户：仅监管/管理员可执行 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        requireManagerRole();
        userService.delete(id);
        return Collections.emptyMap();
    }

    /** 重置指定账号密码为初始值 123456（用于用户忘记密码时管理员代操作）；仅监管/管理员可执行 */
    @PostMapping("/{id}/reset-password")
    public User resetPassword(@PathVariable Integer id) {
        requireManagerRole();
        return userService.resetPassword(id);
    }

    /** 修改密码：body {oldPassword, newPassword}；本人或监管/管理员可操作，旧密码校验失败返回 400 */
    @PutMapping("/{id}/password")
    public Map<String, Object> changePassword(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        requireSelfOrManager(id);
        userService.changePassword(id, str(body.get("oldPassword")), str(body.get("newPassword")));
        return Collections.emptyMap();
    }

    /** 查询账号设置：返回 4 个布尔开关（提醒/订阅类偏好）；本人或监管/管理员可查看 */
    @GetMapping("/{id}/settings")
    public Map<String, Object> getSettings(@PathVariable Integer id) {
        requireSelfOrManager(id);
        return userSettingsService.getSettings(id);
    }

    /** 更新账号设置，body 为 4 个布尔开关字段；本人或监管/管理员可修改 */
    @PutMapping("/{id}/settings")
    public Map<String, Object> updateSettings(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        requireSelfOrManager(id);
        return userSettingsService.updateSettings(id, body);
    }

    /** 安全地把请求体字段转字符串：字段缺失返回 null */
    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 角色限制：用户的新增、删除、重置密码等管理操作仅监管端（regulator）与系统管理员（admin）可执行。
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行用户管理操作");
        }
    }

    /**
     * 权限限制：仅监管端/系统管理员可操作任意用户，其他角色只能操作本人账号
     * （用于个人中心修改资料、密码与账号设置）。
     */
    private void requireSelfOrManager(Integer id) {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if ("regulator".equals(role) || "admin".equals(role)) {
            return;
        }
        User target = userService.getById(id);
        String username = current == null ? null : current.getUsername();
        if (username == null || !username.equals(target.getUsername())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作其他用户信息");
        }
    }
}
