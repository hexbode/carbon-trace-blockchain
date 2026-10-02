package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.AuditOrganization;
import com.carbon.trace.service.AuditOrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 审核机构接口
 */
@RestController
@RequestMapping("/api/audit-organizations")
public class AuditOrganizationController {

    private final AuditOrganizationService auditOrganizationService;

    public AuditOrganizationController(AuditOrganizationService auditOrganizationService) {
        this.auditOrganizationService = auditOrganizationService;
    }

    @GetMapping
    public List<AuditOrganization> list(@RequestParam(required = false) String name) {
        return auditOrganizationService.list(name);
    }

    @PostMapping
    public AuditOrganization create(@RequestBody AuditOrganization organization) {
        requireManagerRole();
        return auditOrganizationService.create(organization);
    }

    @PutMapping("/{id}")
    public AuditOrganization update(@PathVariable String id, @RequestBody AuditOrganization organization) {
        requireManagerRole();
        return auditOrganizationService.update(id, organization);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireManagerRole();
        auditOrganizationService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：审核机构的新增、修改、删除仅监管端（regulator）与系统管理员（admin）可执行。
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行审核机构管理操作");
        }
    }
}
