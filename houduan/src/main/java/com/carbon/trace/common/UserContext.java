package com.carbon.trace.common;

/**
 * 基于 ThreadLocal 的当前登录用户上下文
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 获取当前登录用户姓名，未登录返回空串 */
    public static String currentName() {
        LoginUser user = HOLDER.get();
        return user == null || user.getName() == null ? "" : user.getName();
    }
}
