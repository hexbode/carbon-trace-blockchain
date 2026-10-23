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
 * 碳排放数据记录接口：企业排放数据的填报、查询与删除。
 *
 * 暴露接口：
 *   GET    /api/emission-records         排放记录列表（可按企业/报告期/排放源/状态/关键字过滤）；
 *   GET    /api/emission-records/{id}    单条排放记录；
 *   POST   /api/emission-records         新增填报（body 含 enterpriseId/enterpriseName/period/sourceCode/activityData）；
 *   DELETE /api/emission-records/{id}    删除记录。
 * 角色约定：企业端只能填报并查看/删除本企业数据（UserScopes 强制归属过滤与归属校验）；
 * 填报与删除仅 enterprise / admin 可执行，监管、审核等其他角色无权写入。
 */
@RestController
@RequestMapping("/api/emission-records")
public class EmissionRecordController {

    private final EmissionRecordService emissionRecordService;

    public EmissionRecordController(EmissionRecordService emissionRecordService) {
        this.emissionRecordService = emissionRecordService;
    }

    /** 排放记录列表：企业端强制只看本企业（忽略传入 enterpriseId），其他角色按条件过滤 */
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

    /** 查询单条排放记录：企业端仅能查看归属本企业的记录，越权返回 403 */
    @GetMapping("/{id}")
    public EmissionRecord get(@PathVariable String id) {
        EmissionRecord record = emissionRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的排放记录");
        return record;
    }

    /**
     * 新增排放记录填报：body 含 enterpriseId、enterpriseName、period、sourceCode、activityData。
     * 仅 enterprise / admin 可执行；企业端强校验 enterpriseId 必须等于本人所属企业，防止替别家企业填报。
     */
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

    /** 删除排放记录：企业端仅能删除本企业数据，越权返回 403；仅 enterprise / admin 可写 */
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
