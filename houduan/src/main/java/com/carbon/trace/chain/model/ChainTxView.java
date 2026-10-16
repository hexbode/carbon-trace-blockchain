package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 区块内交易视图（对外契约与旧实现保持一致）
 */
@Data
public class ChainTxView {

    private String txHash;
    private String emissionId;
    private String enterpriseName;
    private String period;
    private BigDecimal amount;
    private String fingerprint;
    private String auditor;
}
