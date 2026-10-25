package com.carbon.trace.controller;

import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.chain.support.ComplianceFlags;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.service.ComplianceService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合规判定接口：排放合规性由链上智能合约执行判定，本控制器负责聚合入参、解读判定结果
 * 以及维护链上治理参数（年度配额与判定阈值）。
 *
 * 暴露接口：
 *   POST /api/compliance/check             试算判定（不落库），body {enterpriseId,period,sourceCode,activityData,factor}；
 *   GET  /api/compliance/certificate/{certId}  证书的链上合规快照；
 *   GET  /api/compliance/quota             查询企业某年度配额（参数 enterpriseId、year）；
 *   PUT  /api/compliance/quota             设置年度配额（写合约，body {enterpriseId,year,quota}）；
 *   GET  /api/compliance/threshold         查询治理参数（环比阈值、迟报月数）；
 *   PUT  /api/compliance/threshold         修改治理参数（写合约，body {thresholdBp?, lateReportMonths?}）。
 * 角色约定：查询登录即可；写合约（配额、阈值）仅 regulator / admin（requireRegulatorRole）。
 *
 * 依赖：{@link ComplianceService}（链上判定与治理参数读写）、{@link ComplianceFlags}（位掩码 → 规则明细）。
 * 安全边界：
 *   1. 判定与查询接口会实时访问链节点，链不可达时返回 502/503（判定结果不落库，无本地回退）；
 *   2. 写接口（配额、阈值）直接改链上治理参数，本层先做角色判断，再交由 Service 做取值范围校验；
 *   3. 试算与配额查询未做企业归属校验：企业端可传入他企业编号做“只读试算/查询”，
 *      这是当前设计口径（试算不落库、配额本身属于链上公开治理数据），若需收紧应在此补 UserScopes；
 *   4. 写接口会直接写链，没有本地库事务，链写失败即整体失败（异常原样上抛）。
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceService complianceService;

    /** 构造注入合规服务；控制器无状态 */
    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    /**
     * POST /api/compliance/check —— 排放合规试算（不落库）。
     *
     * 权限：登录即可。
     * 参数（JSON body）：enterpriseId、period 必填；sourceCode、activityData、factor 选填
     * （缺失时无法折算排放量，仅按现有镜像判定）。
     * 返回：{flags, onChain, compliant, notice, deltaBp, deltaPercent, previousFactor, quota,
     *        yearToDate, thresholdBp, thresholdPercent, items[]}。
     * 错误码：400 企业编号或报告期为空；401 未登录；502/503 链层不可用。
     * 链上写：无（只读试算，不落库、不写链）。
     */
    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        String enterpriseId = str(body.get("enterpriseId"));
        String period = str(body.get("period"));
        String sourceCode = str(body.get("sourceCode"));
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号与报告期不能为空");
        }
        ComplianceResult result = complianceService.evaluateForCheck(
                enterpriseId, period, sourceCode, decimal(body.get("activityData")), decimal(body.get("factor")));
        return describe(result);
    }

    /**
     * GET /api/compliance/certificate/{certId} —— 证书上链时的合规判定快照。
     *
     * 权限：登录即可。
     * 参数：路径 certId 为认证编号。
     * 返回：{certId, flags, compliant, items[]}。
     * 错误码：401 未登录；502/503 链层不可用。
     * 注意：链上查不到该编号时不会被判为 404，而是按 flags=0（无违规旗标）返回；
     * 前端若要区分“无此证书”，需另行校验证书本身是否存在。
     * 链上写：无（只读）。
     */
    @GetMapping("/certificate/{certId}")
    public Map<String, Object> certificate(@PathVariable String certId) {
        Map<String, Object> result = describeFlags(complianceService.certificateFlags(certId));
        result.put("certId", certId);
        return result;
    }

    /**
     * GET /api/compliance/quota —— 查询企业某年度碳排放配额。
     *
     * 权限：登录即可（未按企业归属限制）。
     * 参数：enterpriseId、year 均为必填查询参数。
     * 返回：{enterpriseId, year, quota}；未配置配额时由链侧按默认规则返回。
     * 错误码：400 缺少必需查询参数；401 未登录；502/503 链层不可用。
     * 链上写：无（只读）。
     */
    @GetMapping("/quota")
    public Map<String, Object> quota(@RequestParam String enterpriseId, @RequestParam int year) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseId", enterpriseId);
        result.put("year", year);
        result.put("quota", complianceService.quotaOf(enterpriseId, year));
        return result;
    }

    /**
     * PUT /api/compliance/quota —— 设置企业年度配额。
     *
     * 权限：仅 regulator / admin（requireRegulatorRole）。
     * 参数（JSON body）：enterpriseId、year、quota；Service 进一步校验年份范围 2000~2100 与配额非负。
     * 返回：写入后回查的 {enterpriseId, year, quota}。
     * 错误码：400 企业编号/年份缺失、年份非法、配额为负；401 未登录；403 角色不足；
     *        502/503 链写失败。
     * 链上写：是（调用合约 setAnnualQuota），无本地库事务，失败即整体失败。
     */
    @PutMapping("/quota")
    public Map<String, Object> setQuota(@RequestBody Map<String, Object> body) {
        requireRegulatorRole();
        String enterpriseId = str(body.get("enterpriseId"));
        BigDecimal quota = decimal(body.get("quota"));
        Integer year = integer(body.get("year"));
        if (!StringUtils.hasText(enterpriseId) || year == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号与年份不能为空");
        }
        complianceService.setQuota(enterpriseId, year, quota);
        return quota(enterpriseId, year);
    }

    /**
     * GET /api/compliance/threshold —— 查询链上治理参数。
     *
     * 权限：登录即可。
     * 返回：{thresholdBp, thresholdPercent, lateReportMonths}（基点 2500 表示 25%）。
     * 错误码：401 未登录；502/503 链层不可用。
     * 链上写：无（只读）。
     */
    @GetMapping("/threshold")
    public Map<String, Object> threshold() {
        return describeThreshold();
    }

    /**
     * PUT /api/compliance/threshold —— 修改链上治理参数。
     *
     * 权限：仅 regulator / admin（requireRegulatorRole）。
     * 参数（JSON body）：thresholdBp（1~10000 基点）与 lateReportMonths（0~24）至少提供一项；
     * 两项都提供时按“先阈值、后月数”分别写链，属于两次独立链上写操作。
     * 返回：写入后回查的 {thresholdBp, thresholdPercent, lateReportMonths}。
     * 错误码：400 两项都未提供或取值越界；401 未登录；403 角色不足；502/503 链写失败。
     * 链上写：是（可能触发两次合约写入）；若第二次失败，第一次已生效，需按返回值/日志人工核对。
     */
    @PutMapping("/threshold")
    public Map<String, Object> setThreshold(@RequestBody Map<String, Object> body) {
        requireRegulatorRole();
        Integer bp = integer(body.get("thresholdBp"));
        Integer months = integer(body.get("lateReportMonths"));
        if (bp == null && months == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "环比阈值与迟报月数至少提供一项");
        }
        if (bp != null) {
            complianceService.setFluctuationThresholdBp(bp);
        }
        if (months != null) {
            complianceService.setLateReportMonths(months);
        }
        return describeThreshold();
    }

    // ---------------- 内部 ----------------

    /** 组装治理参数响应：环比阈值（基点 + 百分比文本）与迟报月数 */
    private Map<String, Object> describeThreshold() {
        Map<String, Object> result = new LinkedHashMap<>();
        int bp = complianceService.fluctuationThresholdBp();
        result.put("thresholdBp", bp);
        result.put("thresholdPercent", percentOf(bp));
        result.put("lateReportMonths", complianceService.lateReportMonths());
        return result;
    }

    /** 把 ComplianceResult 展开为响应 Map：旗标/是否合规/环比增量/配额/累计排放/阈值/规则明细等 */
    private Map<String, Object> describe(ComplianceResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flags", result.getFlags());
        body.put("onChain", result.isOnChain());
        body.put("compliant", result.isOnChain() ? result.isCompliant() : null);
        body.put("notice", result.getNotice());
        body.put("deltaBp", result.getDeltaBp());
        body.put("deltaPercent", percentOf(result.getDeltaBp()));
        body.put("previousFactor", result.getPreviousFactor());
        body.put("quota", result.getQuota());
        body.put("yearToDate", result.getYearToDate());
        body.put("thresholdBp", result.getThresholdBp());
        body.put("thresholdPercent", percentOf(result.getThresholdBp()));
        body.put("items", items(result.getFlags()));
        return body;
    }

    /**
     * 把合规旗标 int 展开为响应 Map（flags 为负表示无有效快照，compliant 置 null）。
     *
     * @param flags 合约返回的位掩码；0 表示未命中任何违规规则
     * @return {flags, compliant, items[]}
     */
    private Map<String, Object> describeFlags(int flags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flags", flags);
        body.put("compliant", flags < 0 ? null : ComplianceFlags.compliant(flags));
        body.put("items", items(flags));
        return body;
    }

    /** 把合规旗标展开为逐条规则明细（规则编码/类型/中文标签/级别），供前端列表展示 */
    private List<Map<String, Object>> items(int flags) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ComplianceFlags.Rule rule : ComplianceFlags.rules(flags)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule", rule.rule());
            item.put("type", rule.type());
            item.put("typeLabel", rule.typeLabel());
            item.put("level", rule.level());
            items.add(item);
        }
        return items;
    }

    /**
     * 基点 → 百分比文本（2000 → "20.00%"）。
     *
     * @param bp 基点值（10000 基点 = 100%）
     * @return 保留两位小数的百分比字符串
     */
    private static String percentOf(long bp) {
        return BigDecimal.valueOf(bp).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP) + "%";
    }

    /**
     * 对象安全转 String。
     *
     * @param value 任意 JSON 值
     * @return null 原样返回；其余按字符串形态返回
     */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 对象安全转 BigDecimal：缺失或非法时返回 null（由调用方继续校验必填）。
     *
     * @param value 任意 JSON 值
     * @return 合法数值；空串、空白或不可解析时返回 null
     */
    private static BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 对象安全转 Integer：缺失或非法时返回 null。
     * 先转 BigDecimal 再取 intValue，因此 "20.9" 会得到 20（截断而非四舍五入）。
     *
     * @param value 任意 JSON 值
     * @return 合法整数；空串、空白或不可解析时返回 null
     */
    private static Integer integer(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value)).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 治理参数修改仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、审核端等其他角色
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权修改链上合规治理参数");
        }
    }
}
