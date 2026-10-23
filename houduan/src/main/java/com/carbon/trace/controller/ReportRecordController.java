package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.common.UserScopes;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.service.ReportRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 报告提交记录接口：企业碳排放报告的提交、审核与认证申请。
 *
 * 暴露接口：
 *   GET /api/report-records             报告记录列表（可按企业/状态/关键字过滤）；
 *   GET /api/report-records/{id}        单条报告记录；
 *   PUT /api/report-records/{id}/audit  审核报告（body {result: certified|rejected, opinion}）；
 *   PUT /api/report-records/{id}/apply  认证申请：把被驳回报告改回待审核 pending。
 * 角色约定：查询登录即可，企业端仅见本企业记录（UserScopes）；审核仅 auditor / admin；
 * 认证申请仅 enterprise / admin 且限于本企业报告。
 */
@RestController
@RequestMapping("/api/report-records")
public class ReportRecordController {

    private final ReportRecordService reportRecordService;

    public ReportRecordController(ReportRecordService reportRecordService) {
        this.reportRecordService = reportRecordService;
    }

    /** 报告记录列表：企业端强制只看本企业（忽略传入 enterpriseId），其他角色按状态/关键字过滤 */
    @GetMapping
    public List<ReportRecord> list(@RequestParam(required = false) String enterpriseId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String keyword) {
        // 企业端只能看自己的报告记录
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return reportRecordService.list(enterpriseId, status, keyword);
    }

    /** 查询单条报告记录：企业端仅能查看归属本企业的记录，越权返回 403 */
    @GetMapping("/{id}")
    public ReportRecord get(@PathVariable String id) {
        ReportRecord record = reportRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的报告记录");
        return record;
    }

    /** 审核报告：body {result: certified|rejected, opinion}（审核结论与意见）；仅 auditor / admin 可执行 */
    @PutMapping("/{id}/audit")
    public ReportRecord audit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAuditorRole();
        return reportRecordService.audit(id, body);
    }

    /** 认证申请：把被驳回的报告改回待审核 pending；仅 enterprise / admin 且限于本企业报告 */
    @PutMapping("/{id}/apply")
    public ReportRecord apply(@PathVariable String id) {
        requireEnterpriseRole();
        ReportRecord record = reportRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "对其他企业的报告发起认证申请");
        return reportRecordService.apply(id);
    }

    /**
     * 角色限制：审核操作仅审核端（auditor）与系统管理员（admin）可执行。
     */
    private void requireAuditorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"auditor".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行审核操作");
        }
    }

    /**
     * 角色限制：认证申请仅企业端（enterprise）与系统管理员（admin）可执行。
     */
    private void requireEnterpriseRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"enterprise".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行认证申请");
        }
    }
}
