package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.BlockchainUtil;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.entity.EmissionSource;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.mapper.CertificationRecordMapper;
import com.carbon.trace.mapper.EmissionRecordMapper;
import com.carbon.trace.mapper.EmissionSourceMapper;
import com.carbon.trace.mapper.EnterpriseMapper;
import com.carbon.trace.mapper.ReportRecordMapper;
import com.carbon.trace.service.AlertService;
import com.carbon.trace.service.BlockchainService;
import com.carbon.trace.service.EmissionRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 碳排放数据记录服务实现
 */
@Service
public class EmissionRecordServiceImpl implements EmissionRecordService {

    private static final String UNIT = "tCO₂";

    private final EmissionRecordMapper emissionRecordMapper;
    private final EmissionSourceMapper emissionSourceMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final CertificationRecordMapper certificationRecordMapper;
    private final AlertService alertService;
    private final BlockchainService blockchainService;

    public EmissionRecordServiceImpl(EmissionRecordMapper emissionRecordMapper,
                                     EmissionSourceMapper emissionSourceMapper,
                                     EnterpriseMapper enterpriseMapper,
                                     ReportRecordMapper reportRecordMapper,
                                     CertificationRecordMapper certificationRecordMapper,
                                     AlertService alertService,
                                     BlockchainService blockchainService) {
        this.emissionRecordMapper = emissionRecordMapper;
        this.emissionSourceMapper = emissionSourceMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.certificationRecordMapper = certificationRecordMapper;
        this.alertService = alertService;
        this.blockchainService = blockchainService;
    }

    @Override
    public List<EmissionRecord> list(String enterpriseId, String period, String sourceCode, String status, String keyword) {
        LambdaQueryWrapper<EmissionRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(EmissionRecord::getEnterpriseId, enterpriseId);
        }
        if (StringUtils.hasText(period)) {
            wrapper.eq(EmissionRecord::getPeriod, period);
        }
        if (StringUtils.hasText(sourceCode)) {
            wrapper.eq(EmissionRecord::getSourceCode, sourceCode);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(EmissionRecord::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(EmissionRecord::getId, keyword)
                    .or().like(EmissionRecord::getSourceName, keyword)
                    .or().like(EmissionRecord::getReporter, keyword));
        }
        wrapper.orderByDesc(EmissionRecord::getCreatedAt);
        return emissionRecordMapper.selectList(wrapper);
    }

    @Override
    public EmissionRecord getById(String id) {
        EmissionRecord record = emissionRecordMapper.selectById(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "排放记录不存在");
        }
        return record;
    }

    @Override
    @Transactional
    public EmissionRecord create(Map<String, Object> body) {
        String enterpriseId = str(body.get("enterpriseId"));
        String enterpriseName = str(body.get("enterpriseName"));
        String period = str(body.get("period"));
        String sourceCode = str(body.get("sourceCode"));
        BigDecimal activityData = toDecimal(body.get("activityData"));

        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)
                || !StringUtils.hasText(sourceCode) || activityData == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业、报告期、排放源、活动数据不能为空");
        }

        EmissionSource source = emissionSourceMapper.selectById(sourceCode);
        if (source == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "排放源不存在：" + sourceCode);
        }

        // 企业名称缺失时从企业表补全
        if (!StringUtils.hasText(enterpriseName)) {
            Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
            if (enterprise != null) {
                enterpriseName = enterprise.getName();
            }
        }

        // 排放量 = 活动数据 × 排放因子，保留 2 位小数
        BigDecimal factor = source.getFactor();
        BigDecimal amount = activityData.multiply(factor).setScale(2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        String reporter = UserContext.currentName();

        EmissionRecord record = new EmissionRecord();
        record.setId(nextRecordId());
        record.setEnterpriseId(enterpriseId);
        record.setEnterpriseName(enterpriseName);
        record.setPeriod(period);
        record.setSourceCode(sourceCode);
        record.setSourceName(source.getName());
        record.setActivityData(activityData);
        record.setActivityUnit(activityUnitOf(source.getUnit()));
        record.setEmissionFactor(factor);
        record.setAmount(amount);
        record.setUnit(UNIT);
        record.setStatus("pending");
        record.setCreatedAt(now);
        record.setReporter(reporter);
        // 电子防伪码：12 位随机字符串（大小写字母 + 数字）
        record.setFingerprint(BlockchainUtil.generateAntiFakeCode());
        emissionRecordMapper.insert(record);

        // 同步生成报告提交记录（编号与排放记录一致，三表同号）
        ReportRecord report = new ReportRecord();
        report.setId(record.getId());
        report.setEmissionId(record.getId());
        report.setEnterpriseId(enterpriseId);
        report.setEnterpriseName(enterpriseName);
        report.setPeriod(period);
        report.setSourceName(source.getName());
        report.setAmount(amount);
        report.setStatus("pending");
        report.setFingerprint(record.getFingerprint());
        report.setSubmitter(reporter);
        report.setSubmittedAt(now);
        // 报告记录可能已由数据库触发器在插入排放记录时自动生成，这里先判存在再插入，避免主键冲突
        if (reportRecordMapper.selectById(report.getId()) == null) {
            reportRecordMapper.insert(report);
        }

        // 写入时判定：按规则检查本次填报是否触发异常告警（数据异常 / 因子不符 / 迟报）
        alertService.evaluateOnEmissionCreate(record);

        return record;
    }

    @Override
    @Transactional
    public void delete(String id) {
        EmissionRecord record = emissionRecordMapper.selectById(id);
        // 先清理引用该记录的认证记录与报告记录，避免外键约束失败
        certificationRecordMapper.delete(new LambdaQueryWrapper<CertificationRecord>()
                .eq(CertificationRecord::getEmissionId, id));
        reportRecordMapper.delete(new LambdaQueryWrapper<ReportRecord>()
                .eq(ReportRecord::getEmissionId, id));
        emissionRecordMapper.deleteById(id);
        // 链上交易不在数据库中，需在代码层同步移除
        blockchainService.removeByEmissionId(id);
        // 记录被撤销后，该企业该报告期由规则产生的告警也应一并失效
        if (record != null) {
            alertService.removeByEnterpriseAndPeriod(record.getEnterpriseId(), record.getPeriod());
        }
    }

    /**
     * 生成统一编号：CR + 6 位序号。
     * 排放记录、报告记录、认证记录三表共用同一编号，因此需同时避开三张表中已占用的编号。
     */
    private String nextRecordId() {
        long seq = emissionRecordMapper.selectCount(null);
        String id;
        do {
            seq++;
            id = "CR" + String.format("%06d", seq);
        } while (emissionRecordMapper.selectById(id) != null
                || reportRecordMapper.selectById(id) != null
                || certificationRecordMapper.selectById(id) != null);
        return id;
    }

    /** 从排放因子单位中解析活动数据单位，如 tCO₂/t煤 → t煤 */
    private String activityUnitOf(String factorUnit) {
        if (!StringUtils.hasText(factorUnit)) {
            return null;
        }
        int index = factorUnit.indexOf('/');
        return index >= 0 ? factorUnit.substring(index + 1) : factorUnit;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
