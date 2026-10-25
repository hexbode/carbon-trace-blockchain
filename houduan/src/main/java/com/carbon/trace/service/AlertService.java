package com.carbon.trace.service;

import com.carbon.trace.entity.Alert;
import com.carbon.trace.entity.EmissionRecord;

import java.util.List;

/**
 * 异常告警服务。
 * 负责告警的查询与处理闭环，并在排放数据写入/删除时按合规规则生成或清理告警；
 * 规则判定由链上智能合约执行（链不可用时回退本地规则），本服务只读取判定结果，不直接写链。
 * 规则告警的落库与调用方业务处于同一事务（如排放记录新增事务），落库异常会向上抛出并回滚业务。
 */
public interface AlertService {

    /**
     * 按级别、状态过滤查询告警，按触发时间倒序。
     *
     * @param level  告警级别（high/medium/low），为空表示不过滤
     * @param status 告警状态（pending/resolved），为空表示不过滤
     * @return 告警列表；无匹配时返回空列表
     */
    List<Alert> list(String level, String status);

    /**
     * 按告警编号查询详情。
     *
     * @param id 告警编号（ALT + 3 位序号）
     * @return 告警实体
     * @throws com.carbon.trace.common.ApiException 记录不存在时抛出 404
     */
    Alert getById(String id);

    /**
     * 处理告警：将状态置为 resolved。
     * 权限：Controller 仅允许 regulator / admin 调用。单表更新，无显式事务与链上副作用。
     *
     * @param id 告警编号
     * @return 更新后的告警（status=resolved）
     * @throws com.carbon.trace.common.ApiException 记录不存在时抛出 404
     */
    Alert resolve(String id);

    /**
     * 写入时判定：企业新增排放记录后，按规则检查是否产生异常告警。
     * 规则覆盖告警类型：数据异常（data_anomaly）、因子不符（factor_mismatch）、迟报（late_report），
     * 另含配额超限与活动数据为 0 两类数据异常。判定优先调用链上合约，链不可用时回退本地规则；
     * 仅写业务库告警表，不产生链上写入。
     * 由填报流程在事务内调用，告警落库异常会向上抛出并回滚填报。
     *
     * @param record 已落库的排放记录（读取企业、报告期、排放源、活动数据与因子用于判定）
     */
    void evaluateOnEmissionCreate(EmissionRecord record);

    /**
     * 删除排放记录时，清理该企业该报告期下的告警，避免残留失效告警。
     * 当前实现按企业 + 报告期全量删除（包含预置告警与链上同步失败告警）。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期（YYYY-MM）
     */
    void removeByEnterpriseAndPeriod(String enterpriseId, String period);

    /**
     * 记录一条链上同步失败告警（type=chain_failed / level=high）。
     * 链上写入重试仍失败时调用，纳入监管端待处理告警流，便于及时发现链外存证缺失；
     * 企业编号或报告期为空时无法满足落库约束，仅记录错误日志。
     *
     * @param enterpriseId   企业编号
     * @param enterpriseName 企业名称
     * @param period         报告期（YYYY-MM）
     * @param message        失败描述，作为告警内容
     */
    void reportChainFailure(String enterpriseId, String enterpriseName, String period, String message);
}
