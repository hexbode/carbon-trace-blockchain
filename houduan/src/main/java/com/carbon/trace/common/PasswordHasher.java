package com.carbon.trace.common;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希与校验。
 *
 * 存储格式：{bcrypt}<BCrypt 哈希>；以 {bcrypt} 前缀区分新旧数据，
 * 旧明文密码在启动时由 PasswordMigrationRunner 幂等迁移。
 *
 * 安全边界：
 *   1. BCrypt 自带随机盐与工作因子，同一明文多次编码结果不同，比对必须走 matches 而非字符串相等；
 *   2. 无前缀分支的明文比对仅用于兼容未迁移的历史数据，迁移完成后该分支实际不会命中，
 *      保留导致的后果是“明文密码仍可登录”，因此不应在生产环境长期存在未迁移数据。
 */
public final class PasswordHasher {

    /** 密文前缀，用于区分 {bcrypt} 哈希与历史遗留明文 */
    private static final String PREFIX = "{bcrypt}";

    /** 全局复用的 BCrypt 编码器，encode 与 matches 共用同一实例保证结果一致 */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * 私有构造器：本类只提供静态编码/校验方法，全部复用共享的 {@link #ENCODER}，禁止实例化。
     */
    private PasswordHasher() {
    }

    /**
     * 编码明文密码（含 {bcrypt} 前缀，便于识别与迁移判断）。
     *
     * @param raw 明文密码
     * @return 带前缀的 BCrypt 哈希
     */
    public static String encode(String raw) {
        return PREFIX + ENCODER.encode(raw);
    }

    /**
     * 校验密码：数据库中已是 {bcrypt} 哈希则走 BCrypt 匹配，否则按明文比对（兼容未迁移数据）。
     *
     * @param raw    用户提交的明文密码
     * @param stored 数据库中存储的密码串
     * @return true 表示校验通过；stored 为 null 时一律 false
     */
    public static boolean matches(String raw, String stored) {
        if (stored == null) {
            return false;
        }
        if (stored.startsWith(PREFIX)) {
            return ENCODER.matches(raw, stored.substring(PREFIX.length()));
        }
        return stored.equals(raw);
    }

    /**
     * 判断存储值是否需要迁移为 BCrypt。
     *
     * @param stored 数据库中的密码串
     * @return true 表示非空且尚未带 {bcrypt} 前缀（即仍为明文）
     */
    public static boolean needsMigration(String stored) {
        return stored != null && !stored.startsWith(PREFIX);
    }
}