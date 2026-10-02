package com.carbon.trace.chain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 区块交易（链上数据结构，仅保存在区块链文件中，不落数据库）
 */
@Data
public class BlockTransaction {

    /** 交易序号 */
    private Long id;

    /** 所属区块高度 */
    private Integer blockIndex;

    /** 交易哈希 */
    private String txHash;

    /** 关联排放记录编号 */
    private String emissionId;

    /** 企业名称 */
    private String enterpriseName;

    /** 报告期 */
    private String period;

    /** 排放量 */
    private BigDecimal amount;

    /** 电子防伪码 */
    private String fingerprint;

    /** 审核人 */
    private String auditor;
}
