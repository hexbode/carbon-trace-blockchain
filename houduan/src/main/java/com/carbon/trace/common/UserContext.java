package com.carbon.trace.common;

/**
 * 基于 ThreadLocal 的当前登录用户上下文。
 *
 * 职责：在单个请求线程内暂存当前登录人（LoginUser），避免显式把用户信息层层传参；
 * 写入由 AuthInterceptor 在 preHandle 发起，清除由 afterCompletion 执行。
 * 生命周期严格限制在一次请求内，防止 Servlet 线程池复用造成串号。
 *
 * 安全边界：
 *   1. ThreadLocal 不跨线程传递：@Async、定时任务、手动新建的线程中 get() 均为 null，
 *      这些场景必须显式传参，不能依赖本类拿登录人；
 *   2. 只在 AuthInterceptor 放行后才可能有值，免登录接口（登录、公共查询端）get() 返回 null，
 *      调用方必须先判空再取字段，否则 NPE 会被兜底为 500。
 */
public final class UserContext {

    /** 每个线程持有独立的登录用户上下文，key 为线程，value 为当前登录人 */
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 静态工具类，禁止实例化 */
    private UserContext() {
    }

    /**
     * 写入当前登录用户（由 AuthInterceptor 在请求开始时调用）。
     *
     * @param user 已从 JWT 解析出的登录用户
     */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * 读取当前登录用户。
     *
     * @return 当前线程的登录用户；未登录、免登录接口或异步线程中返回 null
     */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程上下文（由 AuthInterceptor 在请求结束时调用）。
     * 使用 remove 而非 set(null)，避免线程池长期持有空条目。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 获取当前登录用户姓名，用于落库记录填报人 / 审核人。
     *
     * @return 显示姓名；未登录或姓名为空时返回空串（便于直接写入非空字段）
     */
    public static String currentName() {
        LoginUser user = HOLDER.get();
        return user == null || user.getName() == null ? "" : user.getName();
    }
}
