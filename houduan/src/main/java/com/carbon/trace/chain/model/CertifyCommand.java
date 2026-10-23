package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 认证上链入参
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
