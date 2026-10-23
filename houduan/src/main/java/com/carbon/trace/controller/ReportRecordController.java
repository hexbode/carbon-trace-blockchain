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
 * 报告提交记录接口
 */
@RestController
@RequestMapping("/api/report-records")
public class ReportRecordController {

    private final ReportRecordService reportRecordService;

    public ReportRecordController(ReportRecordService reportRecordService) {
        this.reportRecordService = reportRecordService;
    }

    @GetMapping
    public List<ReportRecord> list(@RequestParam(required = false) String enterpriseId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String keyword) {
        // 企业端只能看自己的报告记录
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return reportRecordService.list(enterpriseId, status, keyword);
    }

    @GetMapping("/{id}")
    public ReportRecord get(@PathVariable String id) {
        ReportRecord record = reportRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的报告记录");
        return record;
    }

    /** 审核：body {result:'certified'|'rejected', opinion} */
    @PutMapping("/{id}/audit")
    public ReportRecord audit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAuditorRole();
        return reportRecordService.audit(id, body);
    }

    /** 认证申请：把已驳回的报告改回 pending */
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
