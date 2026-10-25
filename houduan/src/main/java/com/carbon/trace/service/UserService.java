package com.carbon.trace.service;

import com.carbon.trace.entity.User;

import java.util.List;
import java.util.Map;

/**
 * 用户服务（含登录）。
 * 统一管理四类角色（企业/审核员/监管员/管理员）账号的增删改查、密码管理与登录鉴权；
 * 登录成功产出 JWT 与用户信息，供前端持久化会话；密码重置/修改为敏感操作，需校验旧密码或由管理员触发。
 * 安全约定：密码以 BCrypt 哈希存储（兼容迁移前明文），实体序列化不回显密码；更新资料采用白名单字段，
 * 防止通过资料接口提权或篡改账号凭据；登录连续失败 5 次锁定 5 分钟（内存计数，重启清空）。
 * 事务：仅删除用户开启事务（先清账号设置再删用户），其余单表写操作无显式事务。
 * 权限：列表、新增、删除、重置密码仅 regulator / admin；修改资料/密码/设置允许本人或 regulator / admin。
 */
public interface UserService {

    /**
     * 按账号、角色条件过滤查询用户列表，按主键升序。
     * 权限：仅 regulator / admin（用户目录含联系方式等敏感字段）。
     *
     * @param username 登录账号关键字（模糊匹配），为空表示不过滤
     * @param role     角色（enterprise/auditor/regulator/admin），为空表示不过滤
     * @return 用户列表；无匹配时返回空列表（密码字段不参与序列化输出）
     */
    List<User> list(String username, String role);

    /**
     * 按主键查询用户。
     *
     * @param id 用户主键
     * @return 用户实体
     * @throws com.carbon.trace.common.ApiException 用户不存在时抛出 404
     */
    User getById(Integer id);

    /**
     * 新增用户；密码为空时使用默认密码 123456，状态默认 active，注册日期默认当日。
     * 密码统一以 BCrypt 哈希落库；服务层不显式校验账号唯一性，需依赖库约束或调用方保证。
     * 权限：仅 regulator / admin。
     *
     * @param user 用户数据（username/password/role/name 等）
     * @return 落库后的用户实体（密码不回显）
     */
    User create(User user);

    /**
     * 按主键更新用户资料，采用白名单字段：
     * 任何角色只能改姓名、电话、邮箱、签名、头像；仅 regulator / admin 可改 role、status、
     * enterpriseId、orgId；username/password 不可通过本接口修改。
     * 权限：Controller 允许本人或 regulator / admin；服务层根据当前登录角色决定可写字段。
     *
     * @param id   用户主键
     * @param user 待更新的资料
     * @return 更新后的用户实体
     * @throws com.carbon.trace.common.ApiException 用户不存在时抛出 404
     */
    User update(Integer id, User user);

    /**
     * 按主键删除用户：开启事务，先清理 user_settings 再删除用户；系统管理员账号 admin 禁止删除。
     * 权限：仅 regulator / admin。
     *
     * @param id 用户主键
     * @throws com.carbon.trace.common.ApiException 用户不存在时抛出 404；删除 admin 账号时抛出 400
     */
    void delete(Integer id);

    /**
     * 重置指定用户密码为初始值 123456（BCrypt 存储），并解除该账号的内存登录失败锁定。
     * 权限：仅 regulator / admin；单表更新，无链上副作用。
     *
     * @param id 用户主键
     * @return 更新后的用户实体（密码不回显）
     * @throws com.carbon.trace.common.ApiException 用户不存在时抛出 404
     */
    User resetPassword(Integer id);

    /**
     * 修改密码：校验旧密码与库中当前值一致，成功后写入新密码并解除登录失败锁定。
     * 权限：本人或 regulator / admin（Controller 校验）。
     *
     * @param id          用户主键
     * @param oldPassword 旧密码（与库中哈希比对）
     * @param newPassword 新密码，不能为空且长度不少于 6 位
     * @throws com.carbon.trace.common.ApiException 用户不存在时抛出 404；旧密码不符或新密码不合规时抛出 400
     */
    void changePassword(Integer id, String oldPassword, String newPassword);

    /**
     * 按账号精确查询用户（登录/去重用）；账号为空或不存在时返回 null。
     *
     * @param username 登录账号
     * @return 用户实体，未找到时为 null
     */
    User findByUsername(String username);

    /**
     * 登录：校验账号 + 密码 + 角色 + 状态四要素，成功生成 JWT。
     * 任一要素不匹配统一返回 400；连续失败 5 次后锁定 5 分钟并返回 429（内存计数，重启清空）。
     * 权限：登录接口公开（无需登录态）。
     *
     * @param username 登录账号
     * @param password 登录密码（与库中 BCrypt 哈希比对）
     * @param role     期望登录的角色，必须与账号角色一致
     * @return 登录结果 Map：token（JWT）、user（用户名/姓名/角色/企业/机构等载荷）
     * @throws com.carbon.trace.common.ApiException 凭据不匹配时抛出 400；账号锁定时抛出 429
     */
    Map<String, Object> login(String username, String password, String role);

    /**
     * 当前登录用户信息（从登录态上下文还原，不查询数据库）。
     *
     * @return 当前用户载荷 Map：username/name/role/enterpriseId/orgId
     * @throws com.carbon.trace.common.ApiException 未登录时抛出 401
     */
    Map<String, Object> currentUser();
}
