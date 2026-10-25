package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 认证上链入参。
 *
 * 由业务层在认证通过后组装，网关负责定标并调用存证合约；本对象只承载一次认证的完整要素，
 * 不包含交易哈希等链上回填字段（见 {@link ChainCertificate}）。
 *
 * 约束：{@code certId} 与 {@code fingerprint} 是合约内的唯一索引，均不可为空；
 * 金额由网关按 ×100 定标，调用方传入原始十进制值即可。
 */
@Data
public class CertifyCommand {

    /** 认证编号（与排放记录编号、报告编号三表同号） */
    private String certId;

    /** 企业编号 */
    private String enterpriseId;

    /** 企业名称 */
    private String enterpriseName;

    /** 报告期 YYYY-MM */
    private String period;

    /** 认证排放量（链上以 ×100 定点存储） */
    private BigDecimal amount;

    /** 电子防伪码（大小写敏感） */
    private String fingerprint;

    /** 审核人 */
    private String auditor;

    /** 上链时的合规判定结果位掩码（见 ComplianceResult.FLAG_*） */
    private int complianceFlags;
}
