package com.carbon.trace.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息（由 JWT 解析得到）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {
    private String username;
    private String name;
    private String role;
    private String enterpriseId;
    private String orgId;
}
