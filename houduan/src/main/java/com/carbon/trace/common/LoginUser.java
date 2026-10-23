package com.carbon.trace.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息（由 AuthInterceptor 解析 JWT 的 claims 得到）。
 *
 * 字段含义：username 登录账号；name 显示姓名；role 角色（enterprise 企业端 /
 * auditor 审核端 / regulator 监管端 / admin 管理员）；enterpriseId 归属企业编号
 * （企业端用于数据隔离）；orgId 归属机构编号（审核端 / 监管端使用）。
 * 本对象存于 UserContext（ThreadLocal），随请求全程传递。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {
    /** 登录账号（JWT subject） */
    private String username;
    /** 显示姓名 */
    private String name;
    /** 角色：enterprise / auditor / regulator / admin */
    private String role;
    /** 归属企业编号，仅企业端用户有值，用于数据隔离 */
    private String enterpriseId;
    /** 归属机构编号，审核端 / 监管端用户使用 */
    private String orgId;
}
