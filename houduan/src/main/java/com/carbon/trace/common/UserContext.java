package com.carbon.trace.common;

/**
 * 基于 ThreadLocal 的当前登录用户上下文。
 *
 * 职责：在单个请求线程内暂存当前登录人（LoginUser），避免显式把用户信息层层传参；
 * 写入由 AuthInterceptor 在 preHandle 发起，清除由 afterCompletion 执行。
 * 生命周期严格限制在一次请求内，防止 Servlet 线程池复用造成串号。
 */
public final class UserContext {

    /** 每个线程持有独立的登录用户上下文，key 为线程，value 为当前登录人 */
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 静态工具类，禁止实例化 */
    private UserContext() {
    }

    /** 写入当前登录用户（由 AuthInterceptor 在请求开始时调用） */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 读取当前登录用户；未登录或非拦截范围内的请求返回 null */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 清除当前线程上下文（由 AuthInterceptor 在请求结束时调用，防止线程复用串数据） */
    public static void clear() {
        HOLDER.remove();
    }

    /** 获取当前登录用户姓名，未登录或姓名为空时返回空串 */
    public static String currentName() {
        LoginUser user = HOLDER.get();
        return user == null || user.getName() == null ? "" : user.getName();
    }
}
