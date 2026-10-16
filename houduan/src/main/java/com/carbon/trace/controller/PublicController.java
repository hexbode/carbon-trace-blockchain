package com.carbon.trace.controller;

import com.carbon.trace.chain.support.ComplianceFlags;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.service.CertificationService;
import com.carbon.trace.service.ComplianceService;
import com.carbon.trace.service.EnterpriseService;
import com.carbon.trace.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公共查询端接口（无需登录）
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final EnterpriseService enterpriseService;
    private final CertificationService certificationService;
    private final StatsService statsService;
    private final ComplianceService complianceService;

    public PublicController(EnterpriseService enterpriseService,
                            CertificationService certificationService,
                            StatsService statsService,
                            ComplianceService complianceService) {
        this.enterpriseService = enterpriseService;
        this.certificationService = certificationService;
        this.statsService = statsService;
        this.complianceService = complianceService;
    }

    /** 企业列表 */
    @GetMapping("/enterprises")
    public List<Enterprise> enterprises() {
        return enterpriseService.list(null, null);
    }

    /** 企业排放概览 */
    @GetMapping("/enterprise-emissions")
    public List<Map<String, Object>> enterpriseEmissions() {
        return statsService.publicEnterpriseEmissions();
    }

    /** 二维码验证：二维码内容即电子防伪码，按防伪码返回证书信息 */
    @GetMapping("/qr-resolve")
    public CertificationRecord qrResolve(@RequestParam String fingerprint) {
        return certificationService.getByFingerprint(fingerprint);
    }

    /** 固定示例证书：无参数返回 3 张演示证书，供公共查询页展示示例防伪码 */
    @GetMapping("/sample-certs")
    public List<CertificationRecord> sampleCerts() {
        return certificationService.listSamples();
    }

    /**
     * 链上合规判定快照：按电子防伪码返回该证书上链时记录的合规判定结果。
     * 判定结果由智能合约在执行判定时给出并存入存证合约，公众可据此核验企业排放是否合规。
     */
    @GetMapping("/compliance/{fingerprint}")
    public Map<String, Object> compliance(@PathVariable String fingerprint) {
        int flags = complianceService.flagsForFingerprint(fingerprint);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fingerprint", fingerprint);
        result.put("flags", flags);
        result.put("compliant", ComplianceFlags.compliant(flags));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ComplianceFlags.Rule rule : ComplianceFlags.rules(flags)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule", rule.rule());
            item.put("typeLabel", rule.typeLabel());
            item.put("level", rule.level());
            items.add(item);
        }
        result.put("items", items);
        return result;
    }
}
