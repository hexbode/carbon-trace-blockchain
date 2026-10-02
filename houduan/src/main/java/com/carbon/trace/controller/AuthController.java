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
 * 鉴权接口
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /** 登录：body {username, password, role} */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return userService.login(
                str(body.get("username")),
                str(body.get("password")),
                str(body.get("role")));
    }

    /** 当前登录用户 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return userService.currentUser();
    }

    /** 退出登录（无状态空操作） */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Collections.emptyMap();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
