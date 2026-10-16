package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 认证上链入参
 */
@Data
public class CertifyCommand {

    private String certId;
    private String enterpriseId;
    private String enterpriseName;
    private String period;
    private BigDecimal amount;
    private String fingerprint;
    private String auditor;
    private int complianceFlags;
}
