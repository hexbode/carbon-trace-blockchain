package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.common.UserScopes;
import com.carbon.trace.entity.CbamDeclaration;
import com.carbon.trace.entity.CbamGoods;
import com.carbon.trace.entity.CbamInstallation;
import com.carbon.trace.entity.CbamProductEmission;
import com.carbon.trace.entity.CbamReconciliationLog;
import com.carbon.trace.entity.CbamVerificationReport;
import com.carbon.trace.entity.CbamVerifier;
import com.carbon.trace.service.CbamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CBAM 合规接口（P0）。
 *
 * 接口分组：
 *   产品目录   GET/POST /api/cbam/goods，PUT/DELETE /api/cbam/goods/{id}
 *   生产装置   GET/POST /api/cbam/installations，PUT/DELETE /api/cbam/installations/{id}
 *   产品排放   GET/POST /api/cbam/emissions，PUT/DELETE /api/cbam/emissions/{id}
 *   核查机构   GET/POST /api/cbam/verifiers
 *   核查报告   GET/POST /api/cbam/verification-reports
 *   年度申报   POST /api/cbam/declarations/generate、GET /api/cbam/declarations、
 *              GET /api/cbam/declarations/{id}、GET /api/cbam/declarations/{id}/export、
 *              POST /api/cbam/declarations/{id}/submit、POST /api/cbam/declarations/{id}/review
 *   统计       GET /api/cbam/stats
 *
 * 角色边界：
 *   * 产品目录、核查机构、核查报告、申报确认、统计：仅 regulator / admin（基础数据与监管职责）；
 *   * 装置与产品排放：企业端维护本企业数据，监管端只读；
 *   * 申报包：企业端生成/提交，监管端确认/退回；
 *   * 企业端所有读写均通过 UserScopes 强制按令牌归属过滤，越权返回 403。
 */
@RestController
@RequestMapping("/api/cbam")
public class CbamController {

    private final CbamService cbamService;

    public CbamController(CbamService cbamService) {
        this.cbamService = cbamService;
    }

    // ==================== 产品目录 ====================

    @GetMapping("/goods")
    public List<CbamGoods> listGoods(@RequestParam(required = false) String sector,
                                     @RequestParam(required = false) String keyword) {
        return cbamService.listGoods(sector, keyword);
    }

    @PostMapping("/goods")
    public CbamGoods createGoods(@RequestBody CbamGoods goods) {
        requireManagerRole();
        return cbamService.createGoods(goods);
    }

    @PutMapping("/goods/{id}")
    public CbamGoods updateGoods(@PathVariable Integer id, @RequestBody CbamGoods goods) {
        requireManagerRole();
        return cbamService.updateGoods(id, goods);
    }

    @DeleteMapping("/goods/{id}")
    public Map<String, Object> deleteGoods(@PathVariable Integer id) {
        requireManagerRole();
        cbamService.deleteGoods(id);
        return Map.of();
    }

    // ==================== 生产装置 ====================

    @GetMapping("/installations")
    public List<CbamInstallation> listInstallations(@RequestParam(required = false) String enterpriseId) {
        return cbamService.listInstallations(UserScopes.scopedEnterpriseId(enterpriseId));
    }

    @PostMapping("/installations")
    public CbamInstallation createInstallation(@RequestBody CbamInstallation installation) {
        if (UserScopes.isEnterprise()) {
            installation.setEnterpriseId(currentEnterpriseId());
        } else {
            requireManagerRole();
        }
        return cbamService.createInstallation(installation);
    }

    @PutMapping("/installations/{id}")
    public CbamInstallation updateInstallation(@PathVariable Integer id,
                                               @RequestBody CbamInstallation installation) {
        CbamInstallation existing = cbamService.getInstallation(id);
        UserScopes.requireOwnership(existing.getEnterpriseId(), "维护该 CBAM 生产装置");
        if (!UserScopes.isEnterprise()) {
            requireManagerRole();
        }
        return cbamService.updateInstallation(id, installation);
    }

    @DeleteMapping("/installations/{id}")
    public Map<String, Object> deleteInstallation(@PathVariable Integer id) {
        CbamInstallation existing = cbamService.getInstallation(id);
        UserScopes.requireOwnership(existing.getEnterpriseId(), "删除该 CBAM 生产装置");
        if (!UserScopes.isEnterprise()) {
            requireManagerRole();
        }
        cbamService.deleteInstallation(id);
        return Map.of();
    }

    // ==================== 产品级排放 ====================

    @GetMapping("/emissions")
    public List<CbamProductEmission> listEmissions(@RequestParam(required = false) String enterpriseId,
                                                   @RequestParam(required = false) Integer year,
                                                   @RequestParam(required = false) Integer goodsId) {
        return cbamService.listEmissions(UserScopes.scopedEnterpriseId(enterpriseId), year, goodsId);
    }

    @GetMapping("/emissions/{id}")
    public CbamProductEmission getEmission(@PathVariable Long id) {
        CbamProductEmission emission = cbamService.getEmission(id);
        UserScopes.requireOwnership(emission.getEnterpriseId(), "查看该 CBAM 排放记录");
        return emission;
    }

    @PostMapping("/emissions")
    public CbamProductEmission createEmission(@RequestBody CbamProductEmission emission) {
        requireEnterpriseRole();
        emission.setEnterpriseId(currentEnterpriseId());
        return cbamService.createEmission(emission);
    }

    @PutMapping("/emissions/{id}")
    public CbamProductEmission updateEmission(@PathVariable Long id,
                                              @RequestBody CbamProductEmission emission) {
        requireEnterpriseRole();
        CbamProductEmission existing = cbamService.getEmission(id);
        UserScopes.requireOwnership(existing.getEnterpriseId(), "修改该 CBAM 排放记录");
        return cbamService.updateEmission(id, emission);
    }

    @DeleteMapping("/emissions/{id}")
    public Map<String, Object> deleteEmission(@PathVariable Long id) {
        requireEnterpriseRole();
        CbamProductEmission existing = cbamService.getEmission(id);
        UserScopes.requireOwnership(existing.getEnterpriseId(), "删除该 CBAM 排放记录");
        cbamService.deleteEmission(id);
        return Map.of();
    }

    // ==================== 核查机构与报告 ====================

    @GetMapping("/verifiers")
    public List<CbamVerifier> listVerifiers() {
        return cbamService.listVerifiers();
    }

    @PostMapping("/verifiers")
    public CbamVerifier createVerifier(@RequestBody CbamVerifier verifier) {
        requireManagerRole();
        return cbamService.createVerifier(verifier);
    }

    @GetMapping("/verification-reports")
    public List<CbamVerificationReport> listVerificationReports(
            @RequestParam(required = false) String enterpriseId,
            @RequestParam(required = false) Integer year) {
        return cbamService.listVerificationReports(UserScopes.scopedEnterpriseId(enterpriseId), year);
    }

    @PostMapping("/verification-reports")
    public CbamVerificationReport createVerificationReport(@RequestBody CbamVerificationReport report) {
        requireManagerRole();
        return cbamService.createVerificationReport(report);
    }

    // ==================== 年度申报包 ====================

    @PostMapping("/declarations/generate")
    public CbamDeclaration generateDeclaration(@RequestBody Map<String, Object> body) {
        requireEnterpriseRole();
        Integer year = intValue(body.get("year"));
        if (year == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "申报年度不能为空");
        }
        return cbamService.generateDeclaration(currentEnterpriseId(), year, strValue(body.get("importerName")));
    }

    @GetMapping("/declarations")
    public List<CbamDeclaration> listDeclarations(@RequestParam(required = false) String enterpriseId,
                                                  @RequestParam(required = false) Integer year,
                                                  @RequestParam(required = false) String status) {
        return cbamService.listDeclarations(UserScopes.scopedEnterpriseId(enterpriseId), year, status);
    }

    @GetMapping("/declarations/{id}")
    public CbamDeclaration getDeclaration(@PathVariable Long id) {
        CbamDeclaration declaration = cbamService.getDeclaration(id);
        UserScopes.requireOwnership(declaration.getEnterpriseId(), "查看该 CBAM 申报包");
        return declaration;
    }

    @GetMapping("/declarations/{id}/export")
    public ResponseEntity<byte[]> exportDeclaration(@PathVariable Long id) {
        CbamDeclaration declaration = cbamService.getDeclaration(id);
        UserScopes.requireOwnership(declaration.getEnterpriseId(), "导出该 CBAM 申报包");
        byte[] content = cbamService.exportDeclaration(id).getBytes(StandardCharsets.UTF_8);
        String filename = declaration.getDeclarationNo() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(content);
    }

    @PostMapping("/declarations/{id}/submit")
    public CbamDeclaration submitDeclaration(@PathVariable Long id) {
        requireEnterpriseRole();
        CbamDeclaration declaration = cbamService.getDeclaration(id);
        UserScopes.requireOwnership(declaration.getEnterpriseId(), "提交该 CBAM 申报包");
        return cbamService.submitDeclaration(id);
    }

    @PostMapping("/declarations/{id}/review")
    public CbamDeclaration reviewDeclaration(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireManagerRole();
        return cbamService.reviewDeclaration(id, boolValue(body.get("approved")), strValue(body.get("comment")));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(required = false) Integer year) {
        requireManagerRole();
        return cbamService.stats(year);
    }

    /** 手动触发对账（定时任务同样调用该逻辑，默认每小时整点）。 */
    @PostMapping("/reconcile")
    public Map<String, Object> reconcile() {
        requireManagerRole();
        return cbamService.reconcile();
    }

    /** 对账日志：按年度/申报包筛选，默认最近 50 条。 */
    @GetMapping("/reconciliation-logs")
    public List<CbamReconciliationLog> reconciliationLogs(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long declarationId,
            @RequestParam(required = false) Integer limit) {
        requireManagerRole();
        return cbamService.listReconciliationLogs(year, declarationId, limit);
    }

    /** 手动归档并清理过期对账日志（默认保留 90 天）。 */
    @PostMapping("/reconciliation-logs/cleanup")
    public Map<String, Object> cleanupReconciliationLogs(
            @RequestParam(required = false) Integer retentionDays) {
        requireManagerRole();
        return cbamService.cleanupReconciliationLogs(retentionDays);
    }

    // ==================== 角色与入参工具 ====================

    private void requireEnterpriseRole() {
        if (!UserScopes.isEnterprise()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该操作仅企业端可执行");
        }
    }

    private String currentEnterpriseId() {
        LoginUser user = UserContext.get();
        if (user == null || !StringUtils.hasText(user.getEnterpriseId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前账号未绑定企业，无法操作 CBAM 数据");
        }
        return user.getEnterpriseId();
    }

    private void requireManagerRole() {
        LoginUser user = UserContext.get();
        String role = user == null ? null : user.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行 CBAM 监管操作");
        }
    }

    private String strValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "数值格式不正确");
        }
    }

    private boolean boolValue(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
