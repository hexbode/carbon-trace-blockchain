package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 区块内交易视图（对外契约与旧实现保持一致）
 */
@Data
public class ChainTxView {

    /** 交易哈希 */
    private String txHash;

    /** 关联排放记录编号（=认证编号） */
    private String emissionId;

    /** 企业名称 */
    private String enterpriseName;

    /** 报告期 YYYY-MM */
    private String period;

    /** 认证排放量（链上定点还原后的十进制值） */
    private BigDecimal amount;

    /** 电子防伪码 */
    private String fingerprint;

    /** 审核人 */
    private String auditor;
}
