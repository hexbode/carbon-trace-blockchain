package com.carbon.trace.chain;

import com.carbon.trace.chain.model.ChainBlockView;
import com.carbon.trace.chain.model.ChainCertCount;
import com.carbon.trace.chain.model.ChainCertificate;
import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 链网关：业务层与「区块链」交互的唯一抽象。
 *
 * 实现有两套：
 *  - {@code WebaseChainGateway}：真实 FISCO BCOS 联盟链（调用 WeBASE-Front REST 接口 + 智能合约），生产使用；
 *  - {@code StubChainGateway}  ：离线开发桩，仅用于本机无 WeBASE 时保证应用可启动与接口回归。
 */
public interface ChainGateway {

    // ---------------- 认证存证（写） ----------------

    /** 认证上链，返回含真实 txHash / 区块高度的凭证 */
    ChainCertificate certify(CertifyCommand command);

    /** 监管撤销（链上终局状态） */
    void revoke(String certId, String reason);

    /** 排放记录被删除 → 链上作废（链上不可删除，仅标记状态） */
    void invalidateByEmissionId(String emissionId, String reason);

    // ---------------- 认证存证（读 / 核验） ----------------

    /** 按电子防伪码精确查询（大小写敏感） */
    Optional<ChainCertificate> findByFingerprint(String fingerprint);

    /** 按认证编号查询 */
    Optional<ChainCertificate> findByCertId(String certId);

    /** 按排放记录编号查询（三表同号，与按认证编号等价） */
    Optional<ChainCertificate> findByEmissionId(String emissionId);

    /** 按交易哈希查询（忽略大小写与 0x 前缀） */
    Optional<ChainCertificate> findByTxHash(String txHash);

    /**
     * 兼容旧语义：入参是电子防伪码（大小写敏感精确匹配）或交易哈希（忽略大小写与 0x 前缀）。
     */
    Optional<ChainCertificate> findAny(String fingerprintOrTxHash);

    /** 按排放记录编号批量取链上凭证 */
    Map<String, ChainCertificate> certificatesByEmissionIds(Collection<String> emissionIds);

    /** 链上证书计数（供一致性核验与统计） */
    ChainCertCount countCertificates();

    // ---------------- 链浏览 / 统计 ----------------

    /** 最新区块高度；无区块时返回 -1 */
    long latestBlockNumber();

    /** 链上总交易数（节点口径，仅作参考） */
    long chainTxCount();

    /** 最近若干个区块（含交易明细） */
    List<ChainBlockView> listRecentBlocks(int limit);

    // ---------------- 合规判定（合约执行） ----------------

    /** 一次调用完成全部链上规则判定 */
    ComplianceResult checkAll(ComplianceInput input);

    /** 设置企业某年度配额（写链，配额规则的阈值来源） */
    void setAnnualQuota(String enterpriseId, int year, BigDecimal quota);

    /** 查询企业某年度配额；未配置返回 0 */
    BigDecimal annualQuotaOf(String enterpriseId, int year);

    /** 同步企业某报告期合计排放量到链上（环比规则依赖上次报告期数据） */
    void syncPeriodTotal(String enterpriseId, String period, BigDecimal amount);

    /** 同步企业某年度累计排放量到链上（配额规则依赖） */
    void syncYearTotal(String enterpriseId, int year, BigDecimal amount);

    /** 记录某企业某排放源最近一次使用的排放因子（因子一致性规则依赖） */
    void rememberFactor(String enterpriseId, String sourceCode, BigDecimal factor);

    /** 设置环比波动阈值（基点，1bp = 0.01%） */
    void setFluctuationThresholdBp(int bp);

    /** 查询环比波动阈值（基点） */
    int fluctuationThresholdBp();

    /** 设置迟报判定月数（报告期早于当前月份减去该月数即视为迟报） */
    void setLateReportMonths(int months);

    /** 查询迟报判定月数 */
    int lateReportMonths();

    // ---------------- 健康 ----------------

    /** 链是否可用（stub 恒为 false） */
    boolean available();

    /** 运行模式：webase / stub */
    String mode();
}
