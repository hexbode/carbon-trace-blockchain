package com.carbon.trace.chain.migration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.config.ChainProperties;
import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.mapper.CertificationRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史数据上链导入器（一次性工具）。
 *
 * 迁移范围以业务库为准：逐条把 certification_records 写入链上存证合约，已撤销的补一条 revoke。
 * 旧自实现链的 data/blockchain.json 仅作为「电子防伪码」的补充来源（业务库缺失时兜底），
 * 不迁移其中的区块/交易结构。
 *
 * 开关：carbon.chain.migration.enabled=true（默认关闭）。
 *
 * 运行约束：
 *  - 仅在 {@code webase} 模式下执行；stub 模式直接跳过（迁移目标必须是真实链）；
 *  - 每次启动都会全量重放，重复认证会被合约拒绝并计入「跳过」，因此首次迁移完成后应关闭开关；
 *  - 单条失败只告警不中断，最终以汇总日志呈现成功/跳过数量；
 *  - 迁移期间会产生大量写交易，必须在业务低峰执行，并注意 WeBASE-Front 的 gas/权限配置。
 */
@Component
@ConditionalOnProperty(name = "carbon.chain.migration.enabled", havingValue = "true")
public class LegacyChainImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyChainImporter.class);

    private final ChainGateway chainGateway;
    private final ChainProperties properties;
    private final CertificationRecordMapper certificationRecordMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 装配导入器。
     *
     * @param chainGateway              链网关（迁移要求 webase 实现）
     * @param properties                链配置，读取 migration.legacy-file
     * @param certificationRecordMapper 认证记录 Mapper，迁移数据源
     */
    public LegacyChainImporter(ChainGateway chainGateway,
                              ChainProperties properties,
                              CertificationRecordMapper certificationRecordMapper) {
        this.chainGateway = chainGateway;
        this.properties = properties;
        this.certificationRecordMapper = certificationRecordMapper;
    }

    /**
     * 迁移主流程：校验链模式 → 读取旧链防伪码兜底 → 逐条写链（已撤销补 revoke）→ 汇总计数日志；单条失败只告警不中断。
     *
     * 记录按认证编号升序处理，保证重放顺序稳定、日志可对照。
     * 每条记录都必须有防伪码：业务库为空时才会从旧链文件兜底，两者都缺则跳过（合约不接受空防伪码）。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!"webase".equals(chainGateway.mode())) {
            log.warn("历史数据上链已开启，但当前链模式为 {}，跳过迁移（迁移目标是真实联盟链）。", chainGateway.mode());
            return;
        }

        Map<String, String> legacyFingerprints = loadLegacyFingerprints();
        List<CertificationRecord> records = certificationRecordMapper.selectList(
                new LambdaQueryWrapper<CertificationRecord>().orderByAsc(CertificationRecord::getId));

        int migrated = 0;
        int revoked = 0;
        int skipped = 0;
        for (CertificationRecord record : records) {
            String fingerprint = StringUtils.hasText(record.getFingerprint())
                    ? record.getFingerprint()
                    : legacyFingerprints.get(record.getId());
            if (!StringUtils.hasText(fingerprint)) {
                log.warn("跳过认证记录 {}：业务库与旧链文件中均无电子防伪码，无法上链。", record.getId());
                skipped++;
                continue;
            }
            try {
                CertifyCommand command = new CertifyCommand();
                command.setCertId(record.getId());
                command.setEnterpriseId(record.getEnterpriseId());
                command.setEnterpriseName(record.getEnterpriseName());
                command.setPeriod(record.getPeriod());
                command.setAmount(record.getAmount());
                command.setFingerprint(fingerprint);
                command.setAuditor(record.getAuditor());
                // 历史记录未保存判定结果，统一以 0 上链，避免凭空生成合规告警
                command.setComplianceFlags(0);
                chainGateway.certify(command);
                if ("revoked".equals(record.getStatus())) {
                    // 已撤销记录必须先有效认证再撤销，才能得到与业务库一致的状态轨迹
                    chainGateway.revoke(record.getId(),
                            StringUtils.hasText(record.getRevokeReason()) ? record.getRevokeReason() : "历史数据迁移：已撤销");
                    revoked++;
                }
                migrated++;
            } catch (RuntimeException e) {
                // 重复迁移时最常见的失败是防伪码/编号已存在，计入跳过即可，不中断整体迁移
                skipped++;
                log.warn("认证记录 {} 上链失败，已跳过：{}", record.getId(), e.getMessage());
            }
        }

        log.info("历史数据上链完成：成功 {} 条（其中已撤销 {} 条），跳过 {} 条；链上计数 total/valid/revoked/invalid = {}/{}/{}/{}",
                migrated, revoked, skipped,
                // 注意：此处会连续发起 4 次链上 count 调用，仅发生在一次性迁移结束时
                chainGateway.countCertificates().getTotal(),
                chainGateway.countCertificates().getValid(),
                chainGateway.countCertificates().getRevoked(),
                chainGateway.countCertificates().getInvalid());
    }

    /**
     * 读取旧自实现链文件，构建「认证编号 → 电子防伪码」兜底映射。
     *
     * 文件结构预期为顶层 blocks 数组，每个区块的 transactions 数组元素包含
     * {@code emissionId} 与 {@code fingerprint} 字段；只提取这两个字段，忽略区块哈希/交易结构。
     * 文件不存在、结构不符或解析失败都返回空映射，不影响迁移主流程（视为没有兜底数据）。
     *
     * @return 编号 → 防伪码映射，恒不为 null；同一编号出现多次时以首次为准
     */
    private Map<String, String> loadLegacyFingerprints() {
        Map<String, String> result = new HashMap<>();
        Path file = Path.of(properties.getMigration().getLegacyFile());
        if (!Files.exists(file)) {
            log.info("旧链数据文件不存在，跳过防伪码兜底读取：{}", file.toAbsolutePath());
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode blocks = root.get("blocks");
            if (blocks == null || !blocks.isArray()) {
                return result;
            }
            for (JsonNode block : blocks) {
                JsonNode transactions = block.get("transactions");
                if (transactions == null || !transactions.isArray()) {
                    continue;
                }
                for (JsonNode tx : transactions) {
                    String emissionId = tx.hasNonNull("emissionId") ? tx.get("emissionId").asText() : null;
                    String fingerprint = tx.hasNonNull("fingerprint") ? tx.get("fingerprint").asText() : null;
                    // putIfAbsent：同一编号在旧链中若出现多次，保留最早的一条，避免覆盖
                    if (StringUtils.hasText(emissionId) && StringUtils.hasText(fingerprint)) {
                        result.putIfAbsent(emissionId, fingerprint);
                    }
                }
            }
            log.info("旧链数据文件解析完成：可提供 {} 条电子防伪码兜底。", result.size());
        } catch (Exception e) {
            log.warn("旧链数据文件解析失败（不影响迁移）：{}", e.getMessage());
        }
        return result;
    }
}
