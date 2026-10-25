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

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param alertService 告警服务；链上写入重试全部失败后，用它落一条 chain_failed 高优先级告警
     */
    public ChainSyncService(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 链上写入任务。
     * 约定为无返回值、无受检异常：运行时异常视为失败并触发重试；
     * 有返回值的结果（如交易哈希）由调用方在任务外自行缓存。
     */
    @FunctionalInterface
    public interface ChainTask {
        /**
         * 执行一次链上写入；抛出运行时异常即视为本次尝试失败并触发重试。
         */
        void run();
    }

    /**
     * 事务提交后执行链上写入；无事务（未开启事务同步）时立即执行。
     * 事务回滚时 afterCommit 不会触发，因此不会在链上留下无业务记录的孤儿存证。
     * 链上失败不向调用方抛出（失败已重试并落告警），不影响已提交的本地事务。
     *
     * @param action         动作描述（用于日志与失败告警文案）
     * @param enterpriseId   关联企业编号（失败告警用，可空）
     * @param enterpriseName 企业名称（失败告警用，可空）
     * @param period         报告期（失败告警用，可空）
     * @param task           链上写操作
     */
    public void afterCommit(String action, String enterpriseId, String enterpriseName, String period, ChainTask task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 有事务：推迟到提交成功后再写链；回滚则整段跳过
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

    /**
     * 立即执行链上写入，失败按退避重试。
     * 最多尝试 3 次，第 n 次失败后等待 n × 500ms（500ms、1000ms）；只重试运行时异常。
     * 全部失败后生成 chain_failed 高优先级告警；告警落库自身失败只记日志，绝不向外抛出，
     * 避免链故障反向影响业务线程。
     * 注意：本方法在调用线程同步执行（事务场景下即请求线程的 afterCommit 回调），
     * 最坏会额外阻塞约 1.5 秒加上三次链调用耗时。
     *
     * @param action         动作描述（用于日志与失败告警文案）
     * @param enterpriseId   关联企业编号（失败告警用，可空）
     * @param enterpriseName 企业名称（失败告警用，可空）
     * @param period         报告期（失败告警用，可空）
     * @param task           链上写操作
     */
    public void execute(String action, String enterpriseId, String enterpriseName, String period, ChainTask task) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                task.run();
                if (attempt > 1) {
                    // 重试成功也要留痕，便于观察链抖动频率
                    log.info("链上同步在重试后成功（{}，第 {} 次尝试）", action, attempt);
                }
                return;
            } catch (RuntimeException e) {
                // 只捕获运行时异常：Error 等不可恢复问题不重试
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
            // 告警落库失败不能再抛：链已失败，避免把异常扩散回业务线程
            log.error("链上同步失败告警落库失败：{}", e.getMessage());
        }
    }

    /**
     * 重试退避等待：吞掉 {@link InterruptedException}，但通过恢复中断标记把中断状态交还给上层。
     *
     * 等待期间被中断时不向外抛出中断，是因为链上写入已进入收尾阶段，中断继续传播只会让异常扩散到
     * 业务线程；恢复标记可保证调用方在本次同步结束后仍能感知到线程已被中断。
     *
     * @param millis 等待毫秒数
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
