package com.carbon.trace.chain.model;

/**
 * 链上证书状态（与合约 CarbonCertRegistry 的状态常量一一对应）
 */
public enum ChainCertStatus {

    NONE(0, "不存在"),
    VALID(1, "有效"),
    REVOKED(2, "已撤销"),
    INVALID(3, "已作废");

    private final int code;
    private final String label;

    /** 状态码 → 链上存储的整数编号，必须与合约 CarbonCertRegistry 中状态常量保持一致，否则核验展示会错乱 */
    ChainCertStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 状态在链上的整数编码 */
    public int code() {
        return code;
    }

    /** 状态的中文展示名 */
    public String label() {
        return label;
    }

    /** 整数编码 → 状态枚举；空值或未识别编码一律按「不存在」处理（安全缺省） */
    public static ChainCertStatus fromCode(Integer code) {
        if (code == null) {
            return NONE;
        }
        for (ChainCertStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return NONE;
    }
}
