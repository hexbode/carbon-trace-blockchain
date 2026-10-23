package com.carbon.trace.common;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * 按角色的数据访问范围控制。
 *
 * 企业端（enterprise）只能访问归属自己企业的数据；审核/监管/管理员不受限。
 * 列表接口用 scopedEnterpriseId 强制过滤，单条/写接口用 requireOwnership 校验归属。
 */
public final class UserScopes {

    private UserScopes() {
    }

    /** 当前用户是否为企业角色 */
    public static boolean isEnterprise() {
        LoginUser user = UserContext.get();
        return user != null && "enterprise".equals(user.getRole());
    }

    /**
     * 列表过滤参数：企业角色忽略请求里的 enterpriseId，强制为自己的企业；
     * 其他角色原样返回（null 表示不过滤）。
     */
    public static String scopedEnterpriseId(String requested) {
        LoginUser user = UserContext.get();
        if (user != null && "enterprise".equals(user.getRole())) {
            return user.getEnterpriseId();
        }
        return StringUtils.hasText(requested) ? requested : null;
    }

    /**
     * 归属校验：企业角色只能操作自己企业的数据，否则 403；其他角色放行。
     */
    public static void requireOwnership(String ownerEnterpriseId, String action) {
        if (isEnterprise() && !StringUtils.hasText(UserContext.get().getEnterpriseId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前账号未绑定企业，无权" + action);
        }
        if (isEnterprise()
                && (ownerEnterpriseId == null
                    || !ownerEnterpriseId.equals(UserContext.get().getEnterpriseId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权" + action);
        }
    }
}