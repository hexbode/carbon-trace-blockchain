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
import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.service.AlertService;
import com.carbon.trace.service.ChainSyncService;
import com.carbon.trace.service.ComplianceService;
import com.carbon.trace.service.EmissionRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
 * 碳排放数据记录服务实现。
 * 事务边界：新增填报、删除记录开启事务（涉及排放记录/报告记录/认证记录多表联动）。
 * 核心职责：
 *  1. 新增时计算排放量（活动数据×因子，保留 2 位小数）、生成 12 位电子防伪码、同号派生报告记录；
 *  2. 触发合规判定（链上合约，回退本地规则）与「事务提交后」的合规镜像同步上链；
 *  3. 删除时按「认证 → 报告 → 排放」顺序清理，随后将链上存证标记作废并重算合计镜像，业务库里同步清理该期告警。
 * 编号契约：排放记录/报告记录/认证记录三表同号，且编号需避开链上已存在编号（链上只追加不删除）。
 */
@Service
public class EmissionRecordServiceImpl implements EmissionRecordService {

    private static final Logger log = LoggerFactory.getLogger(EmissionRecordServiceImpl.class);

    private static final String UNIT = "tCO₂";

    /**
     * 取号与落库的互斥锁。
     * 编号由「查占用 + 取下一个」得出，并发填报若不加锁会算出同一个编号，
     * 后插入者撞主键并向前端返回「数据已存在」（实测 6 并发有 5 个失败）。
     * 锁只覆盖取号与插入这一小段；跨实例场景由 DuplicateKeyException 重试兜底。
     */
    private final Object recordIdLock = new Object();

    /** 编号自增计数器：-1 表示尚未初始化，首次取号时以业务库现有最大编号为起点 */
    private final java.util.concurrent.atomic.AtomicLong recordSeq =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    private final EmissionRecordMapper emissionRecordMapper;
    private final EmissionSourceMapper emissionSourceMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final CertificationRecordMapper certificationRecordMapper;
    private final AlertService alertService;
    private final ChainGateway chainGateway;
    private final ChainSyncService chainSyncService;
    private final ComplianceService complianceService;

    public EmissionRecordServiceImpl(EmissionRecordMapper emissionRecordMapper,
                                     EmissionSourceMapper emissionSourceMapper,
                                     EnterpriseMapper enterpriseMapper,
                                     ReportRecordMapper reportRecordMapper,
                                     CertificationRecordMapper certificationRecordMapper,
                                     AlertService alertService,
                                     ChainGateway chainGateway,
                                     ChainSyncService chainSyncService,
                                     ComplianceService complianceService) {
        this.emissionRecordMapper = emissionRecordMapper;
        this.emissionSourceMapper = emissionSourceMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.certificationRecordMapper = certificationRecordMapper;
        this.alertService = alertService;
        this.chainGateway = chainGateway;
        this.chainSyncService = chainSyncService;
        this.complianceService = complianceService;
    }

    /** 多条件过滤查询排放记录，按提交时间倒序 */
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

    /** 按编号查询详情，不存在抛 404 */
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
        // 编号是「查总数 + 跳过占用」的读改写序列，并发填报必须串行取号并落库，否则会撞主键
        synchronized (recordIdLock) {
            record.setId(nextRecordId());
            try {
                emissionRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                log.warn("排放编号 {} 已被占用，重新取号后重试", record.getId());
                record.setId(nextRecordId());
                emissionRecordMapper.insert(record);
            }
        }

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

        // 写入时判定：合规规则由链上合约执行，按判定结果生成异常告警（链不可用时回退本地规则）
        alertService.evaluateOnEmissionCreate(record);

        // 链上合规镜像（本期合计 / 年度合计 / 最近使用因子）在事务提交后同步，供配额与因子规则使用
        chainSyncService.afterCommit("合规镜像同步上链", record.getEnterpriseId(), record.getEnterpriseName(),
                record.getPeriod(), () -> complianceService.syncMirrors(record));

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
        // 链上不可删除：删除排放记录只能把链上存证标记为作废（作废状态保留在链上，历史留痕不可抹除）
        String enterpriseId = record == null ? null : record.getEnterpriseId();
        String enterpriseName = record == null ? null : record.getEnterpriseName();
        String period = record == null ? null : record.getPeriod();
        chainSyncService.afterCommit("排放记录作废上链", enterpriseId, enterpriseName, period,
                () -> chainGateway.invalidateByEmissionId(id, "排放记录被企业删除"));
        // 删除后重算链上合计镜像，避免镜像残留偏高导致后续配额误判
        chainSyncService.afterCommit("合规镜像重算上链", enterpriseId, enterpriseName, period,
                () -> complianceService.syncMirrors(enterpriseId, period));
        // 记录被撤销后，该企业该报告期由规则产生的告警也应一并失效
        if (record != null) {
            alertService.removeByEnterpriseAndPeriod(record.getEnterpriseId(), record.getPeriod());
        }
    }

    /**
     * 生成统一编号：CR + 6 位序号。
     * 排放记录、报告记录、认证记录三表共用同一编号，因此需同时避开三张表中已占用的编号；
     * 此外还要避开**链上已存在的编号**：链上只追加不删除，编号一旦上链（即使业务记录已被删除）
     * 也不能复用，否则新认证会与链上遗留的「已撤销 / 已作废」存证同号而无法上链。
     *
     * 取号用进程内自增计数器而不是 selectCount：并发事务彼此看不到对方未提交的插入，
     * 按总数推算会让多个请求算出同一个编号并撞主键（实测 6 并发只成功 1 个）。
     */
    private String nextRecordId() {
        long seq = recordSeq.get();
        if (seq < 0) {
            // 首次使用时以业务库现有最大编号为起点（之后只增不减，天然满足「编号不复用」）
            recordSeq.compareAndSet(-1L, maxExistingSeq());
            seq = recordSeq.get();
        }
        String id;
        do {
            seq = recordSeq.incrementAndGet();
            id = "CR" + String.format("%06d", seq);
        } while (emissionRecordMapper.selectById(id) != null
                || reportRecordMapper.selectById(id) != null
                || certificationRecordMapper.selectById(id) != null
                || chainGateway.findByEmissionId(id).isPresent());
        return id;
    }

    /**
     * 三张同号表中已有的最大编号序号（编号零填充 6 位，按字符串倒序取首条即可）。
     * 任一张表无数据时按 0 处理；编号格式异常同样按 0 处理，避免首次取号失败。
     */
    private long maxExistingSeq() {
        List<EmissionRecord> emission = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .select(EmissionRecord::getId).orderByDesc(EmissionRecord::getId).last("limit 1"));
        List<ReportRecord> report = reportRecordMapper.selectList(new LambdaQueryWrapper<ReportRecord>()
                .select(ReportRecord::getId).orderByDesc(ReportRecord::getId).last("limit 1"));
        List<CertificationRecord> certification = certificationRecordMapper
                .selectList(new LambdaQueryWrapper<CertificationRecord>()
                        .select(CertificationRecord::getId).orderByDesc(CertificationRecord::getId).last("limit 1"));
        long max = 0L;
        if (!emission.isEmpty()) {
            max = Math.max(max, seqOf(emission.get(0).getId()));
        }
        if (!report.isEmpty()) {
            max = Math.max(max, seqOf(report.get(0).getId()));
        }
        if (!certification.isEmpty()) {
            max = Math.max(max, seqOf(certification.get(0).getId()));
        }
        return max;
    }

    /** 解析「CR000123」中的序号，非法格式返回 0 */
    private static long seqOf(String id) {
        if (id == null || id.length() <= 2) {
            return 0L;
        }
        try {
            return Long.parseLong(id.substring(2));
        } catch (NumberFormatException e) {
            return 0L;
        }
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
