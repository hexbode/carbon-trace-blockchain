package com.carbon.trace.common;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * 按角色的数据访问范围控制。
 *
 * 企业端（enterprise）只能访问归属自己企业的数据；审核/监管/管理员不受限。
 * 列表接口用 scopedEnterpriseId 强制过滤，单条/写接口用 requireOwnership 校验归属。
 *
 * 规则细节与易错点：
 *   1. 过滤方向是“覆盖”而非“取交集”：企业端无论请求里传什么 enterpriseId，都会被替换为令牌中的企业编号；
 *   2. 企业端账号若未绑定企业，scopedEnterpriseId 同样返回 null（语义与“不过滤”相同），
 *      因此列表接口不能只依赖该过滤参数，必须另有角色/绑定校验（写接口由 requireOwnership 兜住）；
 *   3. 本类只做企业维度隔离，不区分审核端 / 监管端各自的机构（orgId）范围，机构范围由角色校验与业务查询条件决定；
 *   4. 角色字段为 null（免登录或异常上下文）时一律按“非企业端”放行，调用前必须确保已经过 AuthInterceptor。
 */
public final class UserScopes {

    /**
     * 私有构造器：本类只提供基于 {@link UserContext} 的静态判定方法，禁止实例化。
     */
    private UserScopes() {
    }

    /**
     * 判断当前登录用户是否为企业角色。
     *
     * @return true 表示企业端（enterprise）；未登录时为 false
     */
    public static boolean isEnterprise() {
        LoginUser user = UserContext.get();
        return user != null && "enterprise".equals(user.getRole());
    }

    /**
     * 列表过滤参数：企业角色忽略请求里的 enterpriseId，强制为自己的企业；
     * 其他角色原样返回（null 表示不过滤）。
     *
     * @param requested 请求中传入的企业编号（不可信）
     * @return 实际用于查询的企业编号；null 表示不按企业过滤
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
     *
     * @param ownerEnterpriseId 目标数据实际归属的企业编号（来自数据库，不可信外部输入）
     * @param action            动作描述，用于拼装“无权 XXX”的错误文案
     * @throws ApiException 403：企业端账号未绑定企业，或目标数据不属于该企业
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