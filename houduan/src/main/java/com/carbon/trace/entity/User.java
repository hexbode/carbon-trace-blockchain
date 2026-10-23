package com.carbon.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;

/**
 * 系统用户表。
 * 对应表 users，主键 id 为数据库自增。
 * 业务职责：作为系统的统一登录主体，通过 {@code role} 字段区分企业、审核员、监管员、管理员四类角色；
 * 各角色通过 {@code enterpriseId}/{@code orgId} 关联所属企业/机构，登录态载荷由 {@code UserContext} 从 JWT 还原。
 * 字段契约：{@code password} 采用 WRITE_ONLY 序列化策略——仅允许请求体写入（新建/重置），任何接口响应都不再回显密码。
 */
@Data
@TableName("users")
public class User {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 登录账号 */
    private String username;

    /**
     * 登录密码（演示用明文）。
     * WRITE_ONLY：允许通过请求体写入（新建/重置），但任何接口响应都不再输出密码。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 姓名 */
    private String name;

    /** 角色：enterprise 企业 / auditor 审核员 / regulator 监管员 / admin 管理员；决定菜单与越权拦截范围。 */
    private String role;

    /** 所属企业（企业用户）；仅 enterprise 角色填充，关联 enterprises.id。 */
    private String enterpriseId;

    /** 所属机构（审核人员）；仅 auditor/regulator 角色填充，关联审核机构或监管机构的 id。 */
    private String orgId;

    /** 联系电话 */
    private String phone;

    /** 电子邮箱 */
    private String email;

    /** 个性签名 */
    private String signature;

    /** 头像地址（上传后的图片相对路径，如 /uploads/avatar/xxx.jpg） */
    private String avatar;

    /** 状态 */
    private String status;

    /** 创建时间 */
    private LocalDate createdAt;
}
