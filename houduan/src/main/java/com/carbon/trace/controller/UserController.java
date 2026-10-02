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
 * 用户管理接口
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

    @GetMapping
    public List<User> list(@RequestParam(required = false) String username,
                           @RequestParam(required = false) String role) {
        return userService.list(username, role);
    }

    @PostMapping
    public User create(@RequestBody User user) {
        requireManagerRole();
        return userService.create(user);
    }

    @PutMapping("/{id}")
    public User update(@PathVariable Integer id, @RequestBody User user) {
        requireSelfOrManager(id);
        return userService.update(id, user);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        requireManagerRole();
        userService.delete(id);
        return Collections.emptyMap();
    }

    /** 重置密码为 123456 */
    @PostMapping("/{id}/reset-password")
    public User resetPassword(@PathVariable Integer id) {
        requireManagerRole();
        return userService.resetPassword(id);
    }

    /** 修改密码：body {oldPassword, newPassword}，旧密码不符返回 400 */
    @PutMapping("/{id}/password")
    public Map<String, Object> changePassword(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        requireSelfOrManager(id);
        userService.changePassword(id, str(body.get("oldPassword")), str(body.get("newPassword")));
        return Collections.emptyMap();
    }

    /** 获取账号设置（返回 4 个布尔开关） */
    @GetMapping("/{id}/settings")
    public Map<String, Object> getSettings(@PathVariable Integer id) {
        requireSelfOrManager(id);
        return userSettingsService.getSettings(id);
    }

    /** 更新账号设置 */
    @PutMapping("/{id}/settings")
    public Map<String, Object> updateSettings(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        requireSelfOrManager(id);
        return userSettingsService.updateSettings(id, body);
    }

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
