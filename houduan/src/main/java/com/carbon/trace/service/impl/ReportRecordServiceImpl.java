package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.mapper.EmissionRecordMapper;
import com.carbon.trace.mapper.ReportRecordMapper;
import com.carbon.trace.service.CertificationService;
import com.carbon.trace.service.ReportRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报告提交记录服务实现。
 * 事务边界：审核、重新申请开启事务（报告与排放记录两表联动更新）。
 * 审核通过与认证联动：certified 时调用认证服务生成/更新认证记录并上链；驳回后支持重新申请（回退 pending）。
 */
@Service
public class ReportRecordServiceImpl implements ReportRecordService {

    private final ReportRecordMapper reportRecordMapper;
    private final EmissionRecordMapper emissionRecordMapper;
    private final CertificationService certificationService;

    public ReportRecordServiceImpl(ReportRecordMapper reportRecordMapper,
                                   EmissionRecordMapper emissionRecordMapper,
                                   CertificationService certificationService) {
        this.reportRecordMapper = reportRecordMapper;
        this.emissionRecordMapper = emissionRecordMapper;
        this.certificationService = certificationService;
    }

    /**
     * 按企业、审核状态、关键字过滤查询报告记录，按提交时间倒序；参数为空表示该条件不限制。
     * keyword 同时匹配报告编号、企业名称、排放源名称。
     * 查询后统一补全活动数据与排放因子（由排放记录回填，见 {@link #enrich}）。
     *
     * @param enterpriseId 企业编号，空则不筛选
     * @param status       审核状态（pending / certified / rejected），空则不筛选
     * @param keyword      关键字，空则不筛选
     * @return 报告记录列表（已补全活动数据与因子）
     */
    @Override
    public List<ReportRecord> list(String enterpriseId, String status, String keyword) {
        LambdaQueryWrapper<ReportRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(ReportRecord::getEnterpriseId, enterpriseId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(ReportRecord::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ReportRecord::getId, keyword)
                    .or().like(ReportRecord::getEnterpriseName, keyword)
                    .or().like(ReportRecord::getSourceName, keyword));
        }
        wrapper.orderByDesc(ReportRecord::getSubmittedAt);
        List<ReportRecord> records = reportRecordMapper.selectList(wrapper);
        // 报告记录只存排放量，活动数据/排放因子在排放记录中，统一联查补全供前端展示
        enrich(records);
        return records;
    }

    /**
     * 按编号查询报告详情，并补全活动数据与排放因子。
     *
     * @param id 报告编号（三表同号）
     * @return 报告记录（已补全活动数据与因子）
     * @throws ApiException 404：报告记录不存在
     */
    @Override
    public ReportRecord getById(String id) {
        ReportRecord record = reportRecordMapper.selectById(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报告记录不存在");
        }
        enrich(List.of(record));
        return record;
    }

    /**
     * 批量补全报告记录的活动数据与排放因子：
     * 按 emissionId 一次性查询排放记录，避免逐条查询产生 N+1。
     */
    private void enrich(List<ReportRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<String> emissionIds = records.stream()
                .map(ReportRecord::getEmissionId)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        if (emissionIds.isEmpty()) {
            return;
        }
        Map<String, EmissionRecord> emissionMap = emissionRecordMapper.selectBatchIds(emissionIds).stream()
                .collect(Collectors.toMap(EmissionRecord::getId, e -> e, (a, b) -> a));
        for (ReportRecord record : records) {
            EmissionRecord emission = emissionMap.get(record.getEmissionId());
            if (emission != null) {
                record.setActivityData(emission.getActivityData());
                record.setActivityUnit(emission.getActivityUnit());
                record.setEmissionFactor(emission.getEmissionFactor());
                record.setUnit(emission.getUnit());
            }
        }
    }

    /**
     * 审核报告（事务）：写入审核结论、审核人与审核时间，并同步关联排放记录的状态。
     * 审核结果只允许 certified / rejected；certified 时调用认证服务生成/更新认证记录，
     * 认证记录与本次审核在同一事务内落库，链上存证登记在事务提交后写入（失败重试，最终失败生成告警）。
     * 注意：rejected 只改状态，不会撤销此前可能已生成的认证记录；若报告曾认证通过后又被驳回，
     * 需在认证管理中另行撤销，链上才能体现失效。
     *
     * @param id   报告编号
     * @param body 请求体，result 为审核结果（必填，certified / rejected），opinion 为审核意见（可选）
     * @return 重新读取后的报告记录（状态、审核信息已更新）
     * @throws ApiException 400：审核结果缺失或取值非法；404：报告记录不存在
     */
    @Override
    @Transactional
    public ReportRecord audit(String id, Map<String, Object> body) {
        ReportRecord report = getById(id);
        String result = body.get("result") == null ? null : String.valueOf(body.get("result"));
        if (!"certified".equals(result) && !"rejected".equals(result)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "审核结果只能为 certified 或 rejected");
        }
        String opinion = body.get("opinion") == null ? null : String.valueOf(body.get("opinion"));

        report.setStatus(result);
        report.setAuditor(UserContext.currentName());
        report.setAuditedAt(LocalDateTime.now());
        report.setAuditOpinion(opinion);
        reportRecordMapper.updateById(report);

        // 同步更新对应排放记录的状态
        if (StringUtils.hasText(report.getEmissionId())) {
            EmissionRecord emission = emissionRecordMapper.selectById(report.getEmissionId());
            if (emission != null) {
                emission.setStatus(result);
                emissionRecordMapper.updateById(emission);
            }
        }

        // 认证通过：生成认证记录并上链
        if ("certified".equals(result)) {
            certificationService.certify(report);
        }
        return reportRecordMapper.selectById(id);
    }

    /**
     * 认证申请（事务）：把报告与关联排放记录的状态改回 pending，并清空审核人、审核时间与审核意见。
     * 不做原状态校验（已认证/已驳回的报告都可通过本接口重新申请）；
     * 重新审核通过时认证记录按排放编号复用原编号更新，不会产生新的认证编号。
     * 本方法不写链，链上存证不受影响；核验以链上状态为准，撤销需走认证撤销接口。
     *
     * @param id 报告编号
     * @return 重新读取后的报告记录
     * @throws ApiException 404：报告记录不存在
     */
    @Override
    @Transactional
    public ReportRecord apply(String id) {
        ReportRecord report = getById(id);
        // 企业端重新申请认证：状态改回 pending，清空审核信息
        reportRecordMapper.update(null, new LambdaUpdateWrapper<ReportRecord>()
                .eq(ReportRecord::getId, id)
                .set(ReportRecord::getStatus, "pending")
                .set(ReportRecord::getAuditor, null)
                .set(ReportRecord::getAuditedAt, null)
                .set(ReportRecord::getAuditOpinion, null));

        if (StringUtils.hasText(report.getEmissionId())) {
            emissionRecordMapper.update(null, new LambdaUpdateWrapper<EmissionRecord>()
                    .eq(EmissionRecord::getId, report.getEmissionId())
                    .set(EmissionRecord::getStatus, "pending"));
        }
        return reportRecordMapper.selectById(id);
    }
}
