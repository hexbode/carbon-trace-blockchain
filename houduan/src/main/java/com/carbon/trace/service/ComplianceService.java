package com.carbon.trace.service;

import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.entity.EmissionRecord;

import java.math.BigDecimal;

/**
 * 合规判定服务：告警规则的判定由智能合约执行，本服务负责聚合输入与结果解读。
 */
public interface ComplianceService {

    /** 直接入参判定 */
    ComplianceResult evaluate(ComplianceInput input);

    /** 按「已入库」的排放记录判定（合计中已包含该记录） */
    ComplianceResult evaluateForRecord(EmissionRecord record);

    /** 按排放记录编号判定 */
    ComplianceResult evaluateByEmissionId(String emissionId);

    /** 填报试算：尚未入库的填报数据参与合计，不落库 */
    ComplianceResult evaluateForCheck(String enterpriseId, String period, String sourceCode,
                                      BigDecimal activityData, BigDecimal factor);

    /** 认证上链时使用的合规位掩码（判定不可用时按 0 处理） */
    int flagsForCertify(String emissionId);

    /** 证书的链上合规快照位掩码 */
    int certificateFlags(String certId);

    /** 按电子防伪码取链上合规快照位掩码（未命中返回 0） */
    int flagsForFingerprint(String fingerprint);

    /**
     * 把本次填报引发的「本期合计 / 年度合计 / 最近使用因子」镜像同步到链上。
     * 链上无法自行聚合业务库数据，配额与因子规则需要这些链上镜像。
     */
    void syncMirrors(EmissionRecord record);

    /** 记录被删除后重算该企业该报告期的合计镜像，避免链上镜像残留偏高导致配额误判 */
    void syncMirrors(String enterpriseId, String period);

    // ---------------- 可治理参数 ----------------

    BigDecimal quotaOf(String enterpriseId, int year);

    void setQuota(String enterpriseId, int year, BigDecimal quota);

    int fluctuationThresholdBp();

    void setFluctuationThresholdBp(int bp);

    int lateReportMonths();

    void setLateReportMonths(int months);
}
