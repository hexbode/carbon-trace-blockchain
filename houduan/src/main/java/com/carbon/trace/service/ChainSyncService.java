package com.carbon.trace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 链上同步服务：统一处理「事务提交后写链 + 失败重试 + 最终失败告警」。
 *
 * 链上数据不受数据库事务保护，必须等事务提交后再写链，否则事务回滚（如并发死锁）会在链上留下
 * 没有业务记录的孤儿存证。把这段逻辑收敛到一处，避免散落在各业务方法里。
 */
@Service
public class ChainSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChainSyncService.class);

    /** 最大尝试次数（含首次） */
    private static final int MAX_ATTEMPTS = 3;

    /** 退避基数：第 n 次失败后等待 n × 基数 */
    private static final long BASE_BACKOFF_MS = 500L;

    private final AlertService alertService;

    public ChainSyncService(AlertService alertService) {
        this.alertService = alertService;
    }

    /** 链上写入任务 */
    @FunctionalInterface
    public interface ChainTask {
        void run();
    }

    /**
     * 事务提交后执行链上写入；无事务时立即执行。
     *
     * @param action         动作描述（用于日志与失败告警）
     * @param enterpriseId   关联企业（失败告警用，可空）
     * @param enterpriseName 企业名称（失败告警用，可空）
     * @param period         报告期（失败告警用，可空）
     */
    public void afterCommit(String action, String enterpriseId, String enterpriseName, String period, ChainTask task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    execute(action, enterpriseId, enterpriseName, period, task);
                }
            });
        } else {
            execute(action, enterpriseId, enterpriseName, period, task);
        }
    }

    /** 立即执行（本身已在事务外时使用） */
    public void execute(String action, String enterpriseId, String enterpriseName, String period, ChainTask task) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                task.run();
                if (attempt > 1) {
                    log.info("链上同步在重试后成功（{}，第 {} 次尝试）", action, attempt);
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("链上同步失败（{}，第 {}/{} 次尝试）：{}", action, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(BASE_BACKOFF_MS * attempt);
                }
            }
        }
        String message = action + " 失败：" + (last == null ? "未知原因" : last.getMessage());
        log.error("链上同步最终失败（{}），已生成待处理告警：{}", action, message);
        try {
            alertService.reportChainFailure(enterpriseId, enterpriseName, period, message);
        } catch (RuntimeException e) {
            log.error("链上同步失败告警落库失败：{}", e.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
