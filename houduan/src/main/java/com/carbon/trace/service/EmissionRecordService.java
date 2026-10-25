package com.carbon.trace.service;

import com.carbon.trace.entity.EmissionRecord;

import java.util.List;
import java.util.Map;

/**
 * 碳排放数据记录服务。
 * 维护企业填报的排放核算明细，是认证流程源头；新增时自动计算排放量、生成防伪码并派生报告记录，
 * 删除时按「认证 → 报告 → 排放」清理本地数据，并同步清理链上合规镜像与相关告警。
 * 事务与链：{@link #create(Map)}、{@link #delete(String)} 开启数据库事务；链上写入均通过
 * {@code ChainSyncService.afterCommit} 在事务提交后执行（删除时为存证作废、镜像重算），
 * 避免事务回滚后在链上留下孤儿存证。
 * 权限：Controller 将列表/详情限制为本企业可见，填报/删除仅 enterprise / admin 可执行。
 */
public interface EmissionRecordService {

    /**
     * 按企业、报告期、排放源、认证状态、关键字等条件过滤查询记录列表，按提交时间倒序。
     * 关键字匹配记录编号、排放源名称与填报人；企业端调用时 enterpriseId 由 Controller 强制为本企业。
     *
     * @param enterpriseId 企业编号，为空表示不过滤
     * @param period       报告期（YYYY-MM），为空表示不过滤
     * @param sourceCode   排放源编码，为空表示不过滤
     * @param status       认证状态（pending/certified/rejected），为空表示不过滤
     * @param keyword      关键字，为空表示不过滤
     * @return 排放记录列表；无匹配时返回空列表
     */
    List<EmissionRecord> list(String enterpriseId, String period, String sourceCode, String status, String keyword);

    /**
     * 按记录编号查询详情。
     *
     * @param id 排放记录编号（CR + 6 位序号）
     * @return 排放记录实体
     * @throws com.carbon.trace.common.ApiException 记录不存在时抛出 404
     */
    EmissionRecord getById(String id);

    /**
     * 新增填报：自动计算排放量（活动数据 × 排放因子，保留 2 位小数）、生成电子防伪码，
     * 并同号派生报告记录；排放量单位固定为 tCO₂，状态初始为 pending。
     * 事务：本方法开启事务，排放记录与报告记录在同一事务内落库；编号在锁内取号并避开三表及链上已用编号。
     * 链上副作用：先按链上合约判定生成告警（链不可用时回退本地规则），再在事务提交后同步
     * 「本期合计 / 年度合计 / 最近使用因子」合规镜像；镜像同步失败会重试并最终生成 high 级告警。
     * 权限：仅 enterprise / admin，企业端 enterpriseId 必须等于本人所属企业（Controller 校验）。
     *
     * @param body 填报数据，需包含 enterpriseId、period、sourceCode、activityData；
     *             enterpriseName 可缺省（由企业档案补全）
     * @return 落库后的排放记录
     * @throws com.carbon.trace.common.ApiException 关键字段缺失时抛出 400；排放源不存在时抛出 404
     */
    EmissionRecord create(Map<String, Object> body);

    /**
     * 按编号删除记录：依次删除关联认证记录、报告记录与本条排放记录，并清理该企业该报告期的告警。
     * 事务：本方法开启事务，三表删除在同一事务内完成。
     * 链上副作用：链上不可删除，事务提交后按排放编号将存证标记作废，并重算该企业该报告期的合计镜像；
     * 写入失败会重试，最终失败生成 high 级告警，但不回滚已提交的删除。
     * 权限：仅 enterprise / admin，且只能删除本企业记录（Controller 校验）。
     * 记录不存在时本方法静默返回（Controller 已先查询并返回 404）。
     *
     * @param id 排放记录编号
     */
    void delete(String id);
}
