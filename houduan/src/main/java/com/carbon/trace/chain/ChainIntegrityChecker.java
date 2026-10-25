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
 *
 * 依赖：{@link ChainGateway}（取链上计数与运行模式）与 {@link CertificationRecordMapper}（取业务库计数）。
 * 触发时机：应用就绪后一次 + 固定延迟的定时任务，二者共用 {@link #check()}。
 *
 * 约束与易错点：
 *  - 本类只读不写，发现不一致仅记日志，不会自动修复；链上数据不可删除，修复只能人工介入；
 *  - 链上 valid/revoked 与业务库同状态计数应严格相等；若业务库删除了排放记录，
 *    其链上存证会转为 invalid，此时「链上总数 - invalid」才等于业务库记录数；
 *  - 计数为空（null）时按 0 处理，避免自动拆箱 NPE；
 *  - 定时任务的 initialDelay 与 fixedDelay 使用同一配置项，首次执行会被推迟一个完整周期，
 *    启动阶段的一致性由 {@link #checkOnStartup()} 覆盖。
 */
@Component
public class ChainIntegrityChecker {

    private static final Logger log = LoggerFactory.getLogger(ChainIntegrityChecker.class);

    private final ChainGateway chainGateway;
    private final CertificationRecordMapper certificationRecordMapper;

    /**
     * 装配对账器。
     *
     * @param chainGateway             链网关（只会是当前 mode 对应的唯一实现）
     * @param certificationRecordMapper 认证记录 Mapper，用于统计业务库 valid/revoked 数量
     */
    public ChainIntegrityChecker(ChainGateway chainGateway, CertificationRecordMapper certificationRecordMapper) {
        this.chainGateway = chainGateway;
        this.certificationRecordMapper = certificationRecordMapper;
    }

    /**
     * 应用就绪后立即对账一次，第一时间暴露链库不一致而非等下一次定时任务。
     *
     * 此时 Web 容器已启动，但其他 {@code ApplicationRunner}（如历史迁移、桩播种）的完成顺序
     * 不一定在本方法之前；若刚执行过迁移，计数可能短暂不一致，属于预期。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        check();
    }

    /**
     * 定期复核。
     *
     * 间隔由 {@code carbon.chain.integrity-interval-ms} 控制（默认 300000ms），
     * 首次执行同样延迟一个周期，避免与启动对账重复打链。
     * 异常处理全部下沉到 {@link #check()}：非 webase 模式、链探活失败或计数获取失败均只记日志后返回，
     * 本方法不抛异常，避免单次对账失败中断后续定时任务。
     */
    @Scheduled(fixedDelayString = "${carbon.chain.integrity-interval-ms:300000}",
            initialDelayString = "${carbon.chain.integrity-interval-ms:300000}")
    public void checkPeriodically() {
        check();
    }

    /**
     * 对账主流程：非 webase 或链探活失败直接跳过；否则取链上计数与业务库 valid/revoked 数量比对，
     * 差额解释为已删除排放记录的作废存证；链被清空而业务库仍有记录时视为严重不一致并告警。
     *
     * 判定顺序：
     *  1. 非 webase 模式直接返回（stub 无真实存证，比对没有意义）；
     *  2. 链上计数获取失败按「跳过」处理，不抛出，避免定时任务因链抖动反复报错；
     *  3. 链上 total=0 而业务库有记录 → 记录 error 并提前返回（疑似合约地址配错或误部署新合约）；
     *  4. 分别比对 valid、revoked；不一致只告警，不中断，允许两项同时报出。
     *
     * 本方法不抛异常；所有失败均以日志呈现。
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
            // 一致时给出完整口径，便于运维确认「链上剔除作废后」与业务库记录数是否也对得上
            log.info("链上数据与业务库一致性校验通过：有效 {} / 已撤销 {} / 已作废 {}；"
                            + "业务库认证记录 {} 条（链上剔除作废后 {} 笔）。",
                    count.getValid(), count.getRevoked(), count.getInvalid(), totalRecords, activeRecords);
        } else if (activeRecords != totalRecords) {
            // 状态计数一致但总量有差额：差额应恰好等于作废数（已删除排放记录），属正常业务结果
            log.info("链上存证（剔除已作废 {} 笔）共 {} 笔，业务库认证记录 {} 条，差额为已删除排放记录对应的作废存证，属预期。",
                    count.getInvalid(), activeRecords, totalRecords);
        }
    }

    /**
     * 按认证记录状态统计业务库条数，供与链上对应状态计数逐一比对。
     *
     * @param status 业务库状态值（{@code valid} / {@code revoked}），直接与列值等值匹配
     * @return 记录数；查询结果为 null（无记录）时按 0 处理
     */
    private long countByStatus(String status) {
        Long value = certificationRecordMapper.selectCount(new LambdaQueryWrapper<CertificationRecord>()
                .eq(CertificationRecord::getStatus, status));
        return value == null ? 0L : value;
    }
}
