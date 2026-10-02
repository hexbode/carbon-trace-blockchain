package com.carbon.trace.service;

import com.carbon.trace.entity.User;

import java.util.List;
import java.util.Map;

/**
 * 用户服务（含登录）
 */
public interface UserService {

    List<User> list(String username, String role);

    User getById(Integer id);

    User create(User user);

    User update(Integer id, User user);

    void delete(Integer id);

    User resetPassword(Integer id);

    /** 修改密码：旧密码与库中当前值不符抛 400，成功后写入新密码（演示用明文） */
    void changePassword(Integer id, String oldPassword, String newPassword);

    User findByUsername(String username);

    /** 登录：校验账号 + 密码 + 角色 + 状态，成功返回 {token, user} */
    Map<String, Object> login(String username, String password, String role);

    /** 当前登录用户信息 */
    Map<String, Object> currentUser();
}
