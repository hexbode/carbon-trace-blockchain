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

    ChainCertStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

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
