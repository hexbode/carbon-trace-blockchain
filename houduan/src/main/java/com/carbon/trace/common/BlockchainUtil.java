package com.carbon.trace.common;

import java.security.SecureRandom;

/**
 * 电子防伪码生成工具。
 *
 * 说明：链上存证、交易哈希、区块哈希等已由 FISCO BCOS 联盟链与智能合约负责，
 * 本类只保留与业务相关的电子防伪码生成（12 位随机串，是链上存址的映射索引）。
 *
 * 安全边界：防伪码是“可公开的查询索引”，不是口令或密钥，即使被枚举也只暴露公开核验信息；
 * 其唯一性由链上合约（FINGERPRINT_DUPLICATE）兜底校验，本类只保证随机性不保证全局唯一。
 */
public final class BlockchainUtil {

    /**
     * 私有构造器：本类只提供静态防伪码生成方法，禁止实例化。
     */
    private BlockchainUtil() {
    }

    /** 电子防伪码字符集：大小写字母 + 数字，共 62 个 */
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** 防伪码长度 */
    private static final int CODE_LENGTH = 12;

    /** 随机源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成电子防伪码：12 位随机字符串，由大小写字母与数字组成。
     * 同一条业务记录的排放记录、报告、认证记录、链上存证共用同一个防伪码。
     */
    public static String generateAntiFakeCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
