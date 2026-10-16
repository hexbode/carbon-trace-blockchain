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
 */
@Component
@ConditionalOnProperty(name = "carbon.chain.mode", havingValue = "stub", matchIfMissing = true)
public class StubChainSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StubChainSeeder.class);

    private final StubChainGateway stubChainGateway;
    private final CertificationRecordMapper certificationRecordMapper;

    public StubChainSeeder(StubChainGateway stubChainGateway,
                           CertificationRecordMapper certificationRecordMapper) {
        this.stubChainGateway = stubChainGateway;
        this.certificationRecordMapper = certificationRecordMapper;
    }

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
                    stubChainGateway.revoke(record.getId(), record.getRevokeReason());
                }
                seeded++;
            } catch (RuntimeException e) {
                log.warn("离线桩播种失败，跳过认证记录 {}：{}", record.getId(), e.getMessage());
            }
        }
        log.info("离线桩已播种 {} 条认证记录（离线桩仅为开发脚手架，重启即清空）", seeded);
    }
}
