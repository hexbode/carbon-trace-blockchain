package com.carbon.trace.service;

import com.carbon.trace.entity.User;

import java.util.List;
import java.util.Map;

/**
 * 用户服务（含登录）。
 * 统一管理四类角色（企业/审核员/监管员/管理员）账号的增删改查、密码管理与登录鉴权；
 * 登录成功产出 JWT 与用户信息，供前端持久化会话；密码重置/修改为敏感操作，需校验旧密码或由管理员触发。
 */
public interface UserService {

    /** 按账号、角色条件分页/过滤查询用户列表 */
    List<User> list(String username, String role);

    /** 按主键查询用户 */
    User getById(Integer id);

    /** 新增用户（密码为演示用明文；同一账号应唯一） */
    User create(User user);

    /** 按主键更新用户基础资料 */
    User update(Integer id, User user);

    /** 按主键删除用户 */
    void delete(Integer id);

    /** 重置指定用户密码（管理员操作） */
    User resetPassword(Integer id);

    /** 修改密码：旧密码与库中当前值不符抛 400，成功后写入新密码（演示用明文） */
    void changePassword(Integer id, String oldPassword, String newPassword);

    /** 按账号精确查询用户（登录/去重用） */
    User findByUsername(String username);

    /** 登录：校验账号 + 密码 + 角色 + 状态，成功返回 {token, user} */
    Map<String, Object> login(String username, String password, String role);

    /** 当前登录用户信息（从登录态上下文还原） */
    Map<String, Object> currentUser();
}
