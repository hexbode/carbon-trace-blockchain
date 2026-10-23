package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.common.UserScopes;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.service.EmissionRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 碳排放数据记录接口
 */
@RestController
@RequestMapping("/api/emission-records")
public class EmissionRecordController {

    private final EmissionRecordService emissionRecordService;

    public EmissionRecordController(EmissionRecordService emissionRecordService) {
        this.emissionRecordService = emissionRecordService;
    }

    @GetMapping
    public List<EmissionRecord> list(@RequestParam(required = false) String enterpriseId,
                                     @RequestParam(required = false) String period,
                                     @RequestParam(required = false) String sourceCode,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        // 企业端只能看自己企业的数据（忽略请求里的 enterpriseId）
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return emissionRecordService.list(enterpriseId, period, sourceCode, status, keyword);
    }

    @GetMapping("/{id}")
    public EmissionRecord get(@PathVariable String id) {
        EmissionRecord record = emissionRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的排放记录");
        return record;
    }

    /** 新增填报：body {enterpriseId, enterpriseName, period, sourceCode, activityData} */
    @PostMapping
    public EmissionRecord create(@RequestBody Map<String, Object> body) {
        requireEnterpriseRole();
        // 企业端只能为自己企业填报（攻击验证：曾可通过伪造 enterpriseId 替别的企业创建记录/报告）
        if (UserScopes.isEnterprise()) {
            String claimed = body.get("enterpriseId") == null ? null : String.valueOf(body.get("enterpriseId"));
            String mine = UserContext.get().getEnterpriseId();
            if (!mine.equals(claimed)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "只能为本企业填报排放数据");
            }
        }
        return emissionRecordService.create(body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireEnterpriseRole();
        EmissionRecord record = emissionRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "删除其他企业的排放记录");
        emissionRecordService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：数据填报与删除仅企业端（enterprise）与系统管理员（admin）可执行。
     */
    private void requireEnterpriseRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if ("admin".equals(role)) {
            return;
        }
        if (!"enterprise".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作碳排放数据");
        }
    }
}
