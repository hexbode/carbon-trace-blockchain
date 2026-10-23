package com.carbon.trace.common;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希与校验。
 *
 * 存储格式：{bcrypt}<BCrypt 哈希>；以 {bcrypt} 前缀区分新旧数据，
 * 旧明文密码在启动时由 PasswordMigrationRunner 幂等迁移。
 */
public final class PasswordHasher {

    private static final String PREFIX = "{bcrypt}";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private PasswordHasher() {
    }

    /** 编码明文密码（含前缀，便于识别） */
    public static String encode(String raw) {
        return PREFIX + ENCODER.encode(raw);
    }

    /** 校验：数据库中已是哈希则 BCrypt 匹配，否则按明文比对（兼容未迁移数据） */
    public static boolean matches(String raw, String stored) {
        if (stored == null) {
            return false;
        }
        if (stored.startsWith(PREFIX)) {
            return ENCODER.matches(raw, stored.substring(PREFIX.length()));
        }
        return stored.equals(raw);
    }

    /** 是否需要迁移 */
    public static boolean needsMigration(String stored) {
        return stored != null && !stored.startsWith(PREFIX);
    }
}