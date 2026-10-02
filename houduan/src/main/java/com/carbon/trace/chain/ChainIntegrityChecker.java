package com.carbon.trace.chain;

import com.carbon.trace.mapper.CertificationRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时校验链上数据与业务库是否一致。
 *
 * 链数据保存在文件中，若该文件被误删，应用会以空链（仅创世区块）启动，
 * 此时业务库中的认证记录在链上将查不到存证。这里主动检测并输出告警，便于及时发现。
 */
@Component
public class ChainIntegrityChecker {

    private static final Logger log = LoggerFactory.getLogger(ChainIntegrityChecker.class);

    private final ChainStore chainStore;
    private final CertificationRecordMapper certificationRecordMapper;

    public ChainIntegrityChecker(ChainStore chainStore, CertificationRecordMapper certificationRecordMapper) {
        this.chainStore = chainStore;
        this.certificationRecordMapper = certificationRecordMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        int txCount = chainStore.txCount();
        Long countResult = certificationRecordMapper.selectCount(null);
        long certCount = countResult == null ? 0L : countResult;

        // 链被清空但业务库仍有认证记录：历史存证可能已丢失，需人工介入
        if (txCount == 0 && certCount > 0) {
            log.error("链上数据与业务库严重不一致：链上没有任何交易，但认证记录有 {} 条。"
                            + "请检查链数据文件（{}）是否被误删后重建，历史存证可能已丢失。",
                    certCount, chainStore.file());
            return;
        }
        // 数量对不上：例如应用在事务提交后、写链前异常退出
        if (txCount != certCount) {
            log.warn("链上数据与业务库数量不一致：链上交易 {} 笔，认证记录 {} 条，请核实。", txCount, certCount);
            return;
        }
        log.info("链上数据与业务库一致性校验通过：{} 笔交易 / {} 条认证记录", txCount, certCount);
    }
}
