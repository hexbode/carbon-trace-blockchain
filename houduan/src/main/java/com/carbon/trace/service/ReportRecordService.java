package com.carbon.trace.service;

import com.carbon.trace.entity.ReportRecord;

import java.util.List;
import java.util.Map;

/**
 * 报告提交记录服务。
 * 记录企业对外报送排放报告及审核流转：审核通过与排放记录认证联动（生成/更新认证记录并上链），
 * 审核驳回后可再次申请（apply 将状态改回 pending）。
 * 报告记录与排放记录、认证记录三表同号；列表/详情会按 emissionId 联查排放记录补全活动数据与因子。
 * 事务与链：审核、重新申请开启数据库事务；审核通过时的认证写入在事务内完成，
 * 链上存证由 {@code ChainSyncService.afterCommit} 在提交后执行。
 * 权限：查询受企业归属限制；审核仅 auditor / admin；认证申请仅 enterprise / admin 且限本企业（Controller 校验）。
 */
public interface ReportRecordService {

    /**
     * 按企业、审核状态、关键字过滤查询报告列表，按提交时间倒序。
     * 关键字匹配报告编号、企业名称与排放源名称；结果会联查排放记录补全活动数据、因子与单位（仅展示字段）。
     *
     * @param enterpriseId 企业编号，为空表示不过滤
     * @param status       审核状态（pending/certified/rejected），为空表示不过滤
     * @param keyword      关键字，为空表示不过滤
     * @return 报告记录列表；无匹配时返回空列表
     */
    List<ReportRecord> list(String enterpriseId, String status, String keyword);

    /**
     * 按报告编号查询详情，并补全来自排放记录的活动数据与因子。
     *
     * @param id 报告编号（CR + 6 位序号）
     * @return 报告记录实体
     * @throws com.carbon.trace.common.ApiException 记录不存在时抛出 404
     */
    ReportRecord getById(String id);

    /**
     * 审核报告：写入审核结果、审核人、审核时间与意见，并同步关联排放记录的状态。
     * 事务：本方法开启事务，报告与排放记录在同一事务内更新。
     * 链上副作用：仅当结果为 certified 时调用认证服务，生成/更新认证记录并在事务提交后上链；
     * 链上失败会重试并最终生成 high 级告警，不影响已提交的审核结果。rejected 不产生链上写入。
     * 权限：仅 auditor / admin。
     *
     * @param id   报告编号
     * @param body 审核数据，result 必须为 certified 或 rejected，opinion 为可选审核意见
     * @return 审核后的报告记录
     * @throws com.carbon.trace.common.ApiException 审核结果非法时抛出 400；报告不存在时抛出 404
     */
    ReportRecord audit(String id, Map<String, Object> body);

    /**
     * 认证申请：将报告及关联排放记录状态置回 pending，并清空审核人、审核时间与审核意见。
     * 事务：本方法开启事务，报告与排放记录在同一事务内更新。
     * 链上副作用：无。已存在的链上认证存证不会因重新申请而失效；如需失效须走认证撤销流程。
     * 权限：仅 enterprise / admin，且限于本企业报告。
     *
     * @param id 报告编号
     * @return 更新后的报告记录
     * @throws com.carbon.trace.common.ApiException 报告不存在时抛出 404
     */
    ReportRecord apply(String id);
}
