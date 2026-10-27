package com.carbon.trace.cbam;

import com.carbon.trace.service.CbamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CBAM 申报包定时对账任务。
 *
 * 默认每小时整点执行（carbon.cbam.reconcile-cron 可覆盖），对每个申报包：
 *   1. 读取生成时的 data_version 与当前 cbam_data_versions.version；
 *   2. 重算实时隐含排放合计；
 *   3. 版本或合计不一致标记 stale，并写入 cbam_reconciliation_logs。
 *
 * 监管端「立即对账」调用同一个 CbamService.reconcile()，两条入口行为一致。
 */
@Component
public class CbamReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CbamReconciliationJob.class);

    private final CbamService cbamService;

    @Value("${carbon.cbam.reconcile-retention-days:90}")
    private int retentionDays;

    public CbamReconciliationJob(CbamService cbamService) {
        this.cbamService = cbamService;
    }

    @Scheduled(cron = "${carbon.cbam.reconcile-cron:0 0 * * * ?}")
    public void reconcile() {
        try {
            Map<String, Object> result = cbamService.reconcile();
            log.info("CBAM 定时对账完成: {}", result);
        } catch (Exception e) {
            // 定时任务不能因单次异常中断后续调度，记录后等待下一轮
            log.error("CBAM 定时对账失败", e);
        }
    }

    /** 每天 03:30 归档并清理超过保留期的对账日志（默认 90 天）。 */
    @Scheduled(cron = "${carbon.cbam.reconcile-cleanup-cron:0 30 3 * * ?}")
    public void cleanupReconciliationLogs() {
        try {
            Map<String, Object> result = cbamService.cleanupReconciliationLogs(retentionDays);
            log.info("CBAM 对账日志归档清理完成: retentionDays={}, result={}", retentionDays, result);
        } catch (Exception e) {
            log.error("CBAM 对账日志归档清理失败", e);
        }
    }
}
