package com.carbon.trace.service;

import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.entity.EmissionRecord;

import java.math.BigDecimal;

/**
 * 合规判定服务：告警规则的判定由智能合约执行，本服务负责聚合输入与结果解读。
 * 判定规则包括年度配额、环比波动、活动数据漏填、因子不符与迟报；输入所需的合计值由业务库聚合，
 * 结果位掩码可随认证存证上链形成合规快照。
 * 链不可用时按配置回退本地规则（返回 onChain=false），或原样抛出链异常。
 * 除治理参数（配额、阈值）与镜像同步外，本服务不写业务库；治理参数与镜像均直接写链。
 * 权限：查询登录即可；配额/阈值写入由 Controller 限制为 regulator / admin。
 */
public interface ComplianceService {

    /**
     * 直接入参判定。
     * 链不可用且配置允许回退时返回 onChain=false、flags 未知的结果，否则原样抛出链异常。
     * 只读业务库、不落库；判定本身不写链。
     *
     * @param input 判定输入（企业、报告期、活动数据、因子与合计值）
     * @return 合规判定结果（位掩码、环比增量、配额、年度累计与是否链上判定）
     */
    ComplianceResult evaluate(ComplianceInput input);

    /**
     * 按「已入库」的排放记录判定（合计中已包含该记录，不再叠加增量）。
     *
     * @param record 已落库的排放记录
     * @return 合规判定结果
     */
    ComplianceResult evaluateForRecord(EmissionRecord record);

    /**
     * 按排放记录编号判定：先查库再聚合输入。
     *
     * @param emissionId 排放记录编号
     * @return 合规判定结果
     * @throws com.carbon.trace.common.ApiException 排放记录不存在时抛出 404
     */
    ComplianceResult evaluateByEmissionId(String emissionId);

    /**
     * 填报试算：尚未入库的填报数据参与本期与年度合计（排放量按 2 位小数计算），不落库、不写链。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期（YYYY-MM）
     * @param sourceCode   排放源编码
     * @param activityData 活动数据
     * @param factor       排放因子
     * @return 合规判定结果（含本次试算增量）
     */
    ComplianceResult evaluateForCheck(String enterpriseId, String period, String sourceCode,
                                      BigDecimal activityData, BigDecimal factor);

    /**
     * 认证上链时使用的合规位掩码；判定不可用（负值）时按 0 处理，保证存证流程不中断。
     *
     * @param emissionId 排放记录编号
     * @return 非负的合规位掩码
     * @throws com.carbon.trace.common.ApiException 排放记录不存在时抛出 404
     */
    int flagsForCertify(String emissionId);

    /**
     * 读取证书的链上合规快照位掩码，未找到返回 0。
     *
     * @param certId 认证编号
     * @return 链上合规位掩码
     */
    int certificateFlags(String certId);

    /**
     * 按电子防伪码读取链上合规快照位掩码，未命中返回 0。
     *
     * @param fingerprint 电子防伪码
     * @return 链上合规位掩码
     */
    int flagsForFingerprint(String fingerprint);

    /**
     * 把本次填报引发的「本期合计 / 年度合计 / 最近使用因子」镜像同步到链上。
     * 链上无法自行聚合业务库数据，配额与因子规则需要这些链上镜像。
     * 链上副作用：写链（syncPeriodTotal、syncYearTotal、rememberFactor）；无数据库事务语义，
     * 通常由 {@code ChainSyncService.afterCommit} 在填报事务提交后调用。
     *
     * @param record 已落库的排放记录；企业或报告期为空时直接跳过
     */
    void syncMirrors(EmissionRecord record);

    /**
     * 记录被删除后重算该企业该报告期的合计镜像，避免链上镜像残留偏高导致配额误判。
     * 链上副作用：写链（本期合计与年度合计）；企业或报告期为空时直接跳过。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期（YYYY-MM）
     */
    void syncMirrors(String enterpriseId, String period);

    // ---------------- 可治理参数 ----------------

    /**
     * 读取企业某年度碳排放配额（读链）；链侧未配置时返回默认配额规则。
     *
     * @param enterpriseId 企业编号
     * @param year         年份（如 2025）
     * @return 年度配额
     */
    BigDecimal quotaOf(String enterpriseId, int year);

    /**
     * 设置企业某年度配额（写链，立即生效）。
     * 权限：仅 regulator / admin。
     *
     * @param enterpriseId 企业编号
     * @param year         年份（2000～2100）
     * @param quota        配额，必须为非负数
     * @throws com.carbon.trace.common.ApiException 企业编号为空、年份越界或配额为负时抛出 400
     */
    void setQuota(String enterpriseId, int year, BigDecimal quota);

    /**
     * 读取环比波动阈值（基点，2500 表示 25%）。
     *
     * @return 阈值基点
     */
    int fluctuationThresholdBp();

    /**
     * 设置环比波动阈值（写链）。
     * 权限：仅 regulator / admin。
     *
     * @param bp 阈值基点，需大于 0 且不超过 10000
     * @throws com.carbon.trace.common.ApiException 取值越界时抛出 400
     */
    void setFluctuationThresholdBp(int bp);

    /**
     * 读取迟报判定月数阈值。
     *
     * @return 允许迟报的月数
     */
    int lateReportMonths();

    /**
     * 设置迟报判定月数阈值（写链）。
     * 权限：仅 regulator / admin。
     *
     * @param months 迟报月数，取值 0～24
     * @throws com.carbon.trace.common.ApiException 取值越界时抛出 400
     */
    void setLateReportMonths(int months);
}
