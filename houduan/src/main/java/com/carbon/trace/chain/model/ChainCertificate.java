package com.carbon.trace.chain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 链上认证凭证视图。
 *
 * 该对象是「链上存证」在后端的唯一表示：业务层核验时以它为准，
 * 其中 txHash / blockIndex 不在数据库落库，仅用于展示。
 *
 * 状态语义：{@code exists=false} 时除 status=NONE 外其余字段均无业务含义；
 * 只有 VALID 可作为有效凭证，REVOKED / INVALID 均为终局状态且不可恢复。
 * amount 已由网关从链上定点还原为十进制（÷100），调用方不要再做缩放。
 */
@Data
public class ChainCertificate {

    /** 证书是否存在（链上命中） */
    private boolean exists;

    /** 链上状态 */
    private ChainCertStatus status = ChainCertStatus.NONE;

    /** 认证编号（与排放记录编号、报告编号三表同号） */
    private String certId;

    /** 关联排放记录编号（当前恒等于 certId） */
    private String emissionId;

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

    /** 撤销/作废原因 */
    private String reason;

    /** 上链时的合规判定结果位掩码 */
    private int complianceFlags;

    /** 首次登记所在区块高度 */
    private Long createdBlock;

    /** 首次登记时间戳（秒） */
    private Long createdAt;

    /** 交易哈希（链上回填，不落库） */
    private String txHash;

    /** 所在区块高度（链上回填，不落库） */
    private Long blockIndex;

    /**
     * 是否仍为有效状态。
     *
     * 仅 VALID 可继续作为核验凭证；已撤销与已作废均视为不可用。
     *
     * @return true 表示 status 为 {@link ChainCertStatus#VALID}
     */
    public boolean isActive() {
        return status == ChainCertStatus.VALID;
    }
}
