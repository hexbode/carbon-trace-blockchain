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
 * 公共查询端接口：无需登录，供公众浏览企业、证书与合规快照（位于拦截器放行白名单）。
 *
 * 暴露接口：
 *   GET /api/public/enterprises                   企业列表；
 *   GET /api/public/enterprise-emissions          企业排放概览；
 *   GET /api/public/qr-resolve                    二维码内容解析（按防伪码返回证书）；
 *   GET /api/public/sample-certs                  固定示例证书（供查询页展示示例防伪码）；
 *   GET /api/public/compliance/{fingerprint}      按防伪码返回上链时记录的合规判定快照。
 * 角色约定：全部接口免登录。
 *
 * 依赖：{@link EnterpriseService}、{@link CertificationService}、{@link StatsService}、
 *       {@link ComplianceService}（均为只读调用）。
 * 安全边界：
 *   1. 整个 /api/public/** 都在拦截器白名单内，任何人不带令牌即可访问，新增接口默认对外公开；
 *   2. 公开范围按“可核验”设计：证书查询以链上存证为准，sample-certs 固定 3 条防止遍历；
 *      但 enterprises 直接返回完整企业实体（含联系方式等），如需脱敏应在 Service 侧处理；
 *   3. 合规快照与证书查询均实时访问链节点，链不可达时返回 502/503，不会降级为“合规”。
 * 链上写：本控制器不触发任何链上写操作。
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final EnterpriseService enterpriseService;
    private final CertificationService certificationService;
    private final StatsService statsService;
    private final ComplianceService complianceService;

    /** 构造注入四个只读服务；控制器无状态 */
    public PublicController(EnterpriseService enterpriseService,
                            CertificationService certificationService,
                            StatsService statsService,
                            ComplianceService complianceService) {
        // 四个服务均为只读查询，控制器不做缓存、不做聚合计算
        this.enterpriseService = enterpriseService;
        this.certificationService = certificationService;
        this.statsService = statsService;
        this.complianceService = complianceService;
    }

    /**
     * GET /api/public/enterprises —— 企业列表（免登录）。
     *
     * 参数：无（固定不传过滤条件，返回全部企业）。
     * 返回：List&lt;Enterprise&gt;，按企业编号升序；实体字段原样返回，含联系方式等档案信息。
     * 错误码：500 未知异常。
     * 链上写：无。
     */
    @GetMapping("/enterprises")
    public List<Enterprise> enterprises() {
        return enterpriseService.list(null, null);
    }

    /**
     * GET /api/public/enterprise-emissions —— 企业排放概览（免登录）。
     *
     * 参数：无。
     * 返回：List&lt;Map&gt;，各企业排放总量等公开聚合指标，供公共端图表展示。
     * 错误码：500 未知异常。
     * 链上写：无。
     */
    @GetMapping("/enterprise-emissions")
    public List<Map<String, Object>> enterpriseEmissions() {
        return statsService.publicEnterpriseEmissions();
    }

    /**
     * GET /api/public/qr-resolve —— 二维码内容解析（免登录）。
     *
     * 参数：fingerprint 必填查询参数，二维码内容即 12 位电子防伪码。
     * 返回：CertificationRecord（以链上存证为准）。
     * 错误码：400 缺少 fingerprint；404 防伪码为空或链上查不到；502/503 链层调用失败。
     * 链上写：无（只读）。
     */
    @GetMapping("/qr-resolve")
    public CertificationRecord qrResolve(@RequestParam String fingerprint) {
        return certificationService.getByFingerprint(fingerprint);
    }

    /**
     * GET /api/public/sample-certs —— 固定示例证书（免登录）。
     *
     * 参数：无。固定返回 CR000001~CR000003 三条演示数据，接口无任何可枚举参数，
     * 公众无法借此遍历真实认证记录。
     * 返回：List&lt;CertificationRecord&gt;（含链上回填信息）。
     * 错误码：502/503 链层调用失败；500 未知异常。
     * 链上写：无（只读）。
     */
    @GetMapping("/sample-certs")
    public List<CertificationRecord> sampleCerts() {
        return certificationService.listSamples();
    }

    /**
     * GET /api/public/compliance/{fingerprint} —— 证书的链上合规判定快照（免登录）。
     *
     * 链上合规判定快照：按电子防伪码返回该证书上链时记录的合规判定结果。
     * 判定结果由智能合约在执行判定时给出并存入存证合约，公众可据此核验企业排放是否合规。
     *
     * 参数：路径 fingerprint 为电子防伪码。
     * 返回：{fingerprint, flags, compliant, items:[{rule, typeLabel, level}]}。
     * 错误码：502/503 链层调用失败；链上查不到该防伪码时 flags=0、compliant=true（不返回 404）。
     * 链上写：无（只读）。
     */
    @GetMapping("/compliance/{fingerprint}")
    public Map<String, Object> compliance(@PathVariable String fingerprint) {
        // 位掩码由合约给出：0 表示未命中任何违规规则；链上无此防伪码时同样返回 0
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
