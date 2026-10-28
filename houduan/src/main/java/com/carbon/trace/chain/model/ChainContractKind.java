package com.carbon.trace.chain.model;

/**
 * 平台可治理的链上合约类型。
 *
 * 存证逻辑合约的 owner / writer 状态保存在 CertRegistryData（数据合约），写操作走逻辑合约；
 * 合规合约与 CBAM 锚点合约自带 owner / writers，读写都在本合约。
 */
public enum ChainContractKind {

    /** 认证存证：读数据合约，写逻辑合约 */
    REGISTRY("registry", "认证存证"),

    /** 合规判定合约 */
    COMPLIANCE("compliance", "合规判定"),

    /** CBAM 申报包锚点合约 */
    CBAM_ANCHOR("cbam-anchor", "CBAM 锚点");

    private final String code;
    private final String label;

    ChainContractKind(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 按接口传入的 code 解析合约类型，大小写不敏感。
     *
     * @param code registry / compliance / cbam-anchor
     * @return 对应枚举；无法识别时返回 null，由调用方决定报错文案
     */
    public static ChainContractKind from(String code) {
        if (code == null) {
            return null;
        }
        for (ChainContractKind kind : values()) {
            if (kind.code.equalsIgnoreCase(code.trim())) {
                return kind;
            }
        }
        return null;
    }
}
