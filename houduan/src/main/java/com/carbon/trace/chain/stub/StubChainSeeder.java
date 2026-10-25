package com.carbon.trace.chain.stub;

import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.mapper.CertificationRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 离线桩播种器：启动时把业务库中已有的认证记录灌入桩，保证存量证书（含示例证书 CR000001~CR000003）
 * 在桩模式下仍可按电子防伪码查询与核验。
 *
 * 仅 stub 模式生效；webase 模式下链上存证由真实链提供，不需要播种。
 *
 * 约束：
 *  - 播种只发生在启动阶段，之后新增的认证记录由正常业务流程写入桩，无需再次播种；
 *  - 播种不写业务库，也不校验业务库与桩的一致性，仅做单向复制；
 *  - 已撤销记录会先认证再补 revoke，以模拟「先有效后撤销」的链上轨迹；
 *  - 单条记录失败只告警并继续，不阻断启动（例如已存在的防伪码冲突）。
 */
@Component
@ConditionalOnProperty(name = "carbon.chain.mode", havingValue = "stub", matchIfMissing = true)
public class StubChainSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StubChainSeeder.class);

    private final StubChainGateway stubChainGateway;
    private final CertificationRecordMapper certificationRecordMapper;

    /**
     * 装配播种器。
     *
     * @param stubChainGateway         桩实现（仅 stub 模式下存在该 Bean）
     * @param certificationRecordMapper 认证记录 Mapper，用于读取存量数据
     */
    public StubChainSeeder(StubChainGateway stubChainGateway,
                           CertificationRecordMapper certificationRecordMapper) {
        this.stubChainGateway = stubChainGateway;
        this.certificationRecordMapper = certificationRecordMapper;
    }

    /**
     * 启动时把业务库已有认证记录逐条灌入桩；已撤销记录补一条 revoke，单条失败不阻断其余。
     *
     * 合规位掩码统一置 0：历史记录未保存判定结果，重新判定需要业务数据，播种阶段不做推断。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        List<CertificationRecord> records = certificationRecordMapper.selectList(null);
        int seeded = 0;
        for (CertificationRecord record : records) {
            CertifyCommand command = new CertifyCommand();
            command.setCertId(record.getId());
            command.setEnterpriseId(record.getEnterpriseId());
            command.setEnterpriseName(record.getEnterpriseName());
            command.setPeriod(record.getPeriod());
            command.setAmount(record.getAmount());
            command.setFingerprint(record.getFingerprint());
            command.setAuditor(record.getAuditor());
            command.setComplianceFlags(0);
            try {
                stubChainGateway.certify(command);
                if ("revoked".equals(record.getStatus())) {
                    // 先按有效认证写入，再补撤销，保证桩内状态与业务库最终状态一致
                    stubChainGateway.revoke(record.getId(), record.getRevokeReason());
                }
                seeded++;
            } catch (RuntimeException e) {
                // 单条失败（如防伪码冲突）不阻断启动，只记录并继续下一条
                log.warn("离线桩播种失败，跳过认证记录 {}：{}", record.getId(), e.getMessage());
            }
        }
        log.info("离线桩已播种 {} 条认证记录（离线桩仅为开发脚手架，重启即清空）", seeded);
    }
}
