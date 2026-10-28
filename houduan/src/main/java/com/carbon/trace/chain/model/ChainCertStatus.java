package com.carbon.trace.chain.model;

/**
 * 链上证书状态（与合约 CertRegistryData / CertRegistryLogicV* 的状态常量一一对应）。
 *
 * 状态迁移是单向的：VALID → REVOKED（监管撤销）或 VALID → INVALID（排放记录删除），
 * 两个终局状态之间不再互相转换，也不存在删除操作。NONE 仅用于「链上不存在」。
 */
public enum ChainCertStatus {

    NONE(0, "不存在"),
    VALID(1, "有效"),
    REVOKED(2, "已撤销"),
    INVALID(3, "已作废");

    private final int code;
    private final String label;

    /** 状态码 → 链上存储的整数编号，必须与合约 CertRegistryData 中状态常量保持一致，否则核验展示会错乱 */
    ChainCertStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 状态在链上的整数编码。
     *
     * @return 与合约常量一致的编码值
     */
    public int code() {
        return code;
    }

    /**
     * 状态的中文展示名。
     *
     * @return 可直接用于前端的简体中文标签
     */
    public String label() {
        return label;
    }

    /**
     * 整数编码 → 状态枚举。
     *
     * 空值或未识别编码一律按「不存在」处理（安全缺省），避免因合约升级新增状态码
     * 而在展示层抛出异常。
     *
     * @param code 链上状态编码，可为 null
     * @return 对应状态；无法识别时返回 {@link #NONE}
     */
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
