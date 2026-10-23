package com.carbon.trace.chain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.chain.model.ChainCertCount;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.mapper.CertificationRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 链上存证与业务库的一致性对账。
 *
 * 对账口径（迁移到真链后发生变化）：
 *  - 链上只追加不删除，删除排放记录对应的是「作废」状态，作废存证只存在于链上；
 *    因此不再比对"总数"，而是分别比对 有效 / 已撤销 的数量，并把差额解释为已删除记录的作废存证。
 *  - 链探活失败或处于离线桩模式时跳过对账，避免误报。
 */
@Component
public class ChainIntegrityChecker {

    private static final Logger log = LoggerFactory.getLogger(ChainIntegrityChecker.class);

    private final ChainGateway chainGateway;
    private final CertificationRecordMapper certificationRecordMapper;

    public ChainIntegrityChecker(ChainGateway chainGateway, CertificationRecordMapper certificationRecordMapper) {
        this.chainGateway = chainGateway;
        this.certificationRecordMapper = certificationRecordMapper;
    }

    /** 应用就绪后立即对账一次，第一时间暴露链库不一致而非等下一次定时任务 */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        check();
    }

    /** 定期复核（间隔见 carbon.chain.integrity-interval-ms） */
    @Scheduled(fixedDelayString = "${carbon.chain.integrity-interval-ms:300000}",
            initialDelayString = "${carbon.chain.integrity-interval-ms:300000}")
    public void checkPeriodically() {
        check();
    }

    /**
     * 对账主流程：非 webase 或链探活失败直接跳过；否则取链上计数与业务库 valid/revoked 数量比对，
     * 差额解释为已删除排放记录的作废存证；链被清空而业务库仍有记录时视为严重不一致并告警。
     */
    public void check() {
        if (!"webase".equals(chainGateway.mode())) {
            log.warn("当前为离线桩模式（stub），链上无真实存证，跳过链上一致性对账。");
            return;
        }
        ChainCertCount count;
        try {
            count = chainGateway.countCertificates();
        } catch (RuntimeException e) {
            log.warn("链上一致性对账跳过：链上存证计数获取失败（{}）", e.getMessage());
            return;
        }

        Long totalResult = certificationRecordMapper.selectCount(null);
        long totalRecords = totalResult == null ? 0L : totalResult;
        long validRecords = countByStatus("valid");
        long revokedRecords = countByStatus("revoked");

        // 链被清空或合约地址配错：历史存证可能已丢失，需要人工介入
        if (count.getTotal() == 0 && totalRecords > 0) {
            log.error("链上数据与业务库严重不一致：链上没有任何存证，但业务库认证记录有 {} 条。"
                            + "请检查合约地址配置是否正确、以及是否误部署了新合约。", totalRecords);
            return;
        }

        boolean consistent = true;
        if (count.getValid() != validRecords) {
            consistent = false;
            log.warn("链上与业务库不一致：链上有效存证 {} 笔，业务库有效认证记录 {} 条，请核实。",
                    count.getValid(), validRecords);
        }
        if (count.getRevoked() != revokedRecords) {
            consistent = false;
            log.warn("链上与业务库不一致：链上已撤销存证 {} 笔，业务库已撤销认证记录 {} 条，请核实。",
                    count.getRevoked(), revokedRecords);
        }

        long activeRecords = count.getTotal() - count.getInvalid();
        if (consistent) {
            log.info("链上数据与业务库一致性校验通过：有效 {} / 已撤销 {} / 已作废 {}；"
                            + "业务库认证记录 {} 条（链上剔除作废后 {} 笔）。",
                    count.getValid(), count.getRevoked(), count.getInvalid(), totalRecords, activeRecords);
        } else if (activeRecords != totalRecords) {
            log.info("链上存证（剔除已作废 {} 笔）共 {} 笔，业务库认证记录 {} 条，差额为已删除排放记录对应的作废存证，属预期。",
                    count.getInvalid(), activeRecords, totalRecords);
        }
    }

    /** 按认证记录状态统计业务库条数，供与链上对应状态计数逐一比对 */
    private long countByStatus(String status) {
        Long value = certificationRecordMapper.selectCount(new LambdaQueryWrapper<CertificationRecord>()
                .eq(CertificationRecord::getStatus, status));
        return value == null ? 0L : value;
    }
}
