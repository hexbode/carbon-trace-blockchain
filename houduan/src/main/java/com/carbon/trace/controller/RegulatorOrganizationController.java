package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.RegulatorOrganization;
import com.carbon.trace.service.RegulatorOrganizationService;
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
 * 监管机构接口（监管端）
 */
@RestController
@RequestMapping("/api/regulator-organizations")
public class RegulatorOrganizationController {

    private final RegulatorOrganizationService regulatorOrganizationService;

    public RegulatorOrganizationController(RegulatorOrganizationService regulatorOrganizationService) {
        this.regulatorOrganizationService = regulatorOrganizationService;
    }

    @GetMapping
    public List<RegulatorOrganization> list(@RequestParam(required = false) String name) {
        requireRegulatorRole();
        return regulatorOrganizationService.list(name);
    }

    @PostMapping
    public RegulatorOrganization create(@RequestBody RegulatorOrganization organization) {
        requireRegulatorRole();
        return regulatorOrganizationService.create(organization);
    }

    @PutMapping("/{id}")
    public RegulatorOrganization update(@PathVariable String id, @RequestBody RegulatorOrganization organization) {
        requireRegulatorRole();
        return regulatorOrganizationService.update(id, organization);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireRegulatorRole();
        regulatorOrganizationService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：监管机构信息仅监管端（regulator）与系统管理员（admin）可操作，
     * 企业端、审核端等其他登录角色返回 403。
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作监管机构信息");
        }
    }
}
