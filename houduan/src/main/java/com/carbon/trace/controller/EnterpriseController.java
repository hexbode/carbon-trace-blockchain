package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.service.EnterpriseService;
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
 * 企业接口：企业档案的查询、行业字典与增删改。
 *
 * 暴露接口：
 *   GET    /api/enterprises               企业列表（可按 name、industry 过滤）；
 *   GET    /api/enterprises/industries    行业下拉字典（去重后的行业列表）；
 *   GET    /api/enterprises/{id}          按企业编号查询详情；
 *   POST   /api/enterprises               新建企业；
 *   PUT    /api/enterprises/{id}          修改企业；
 *   DELETE /api/enterprises/{id}          删除企业。
 * 角色约定：查询接口登录即可访问；新增/修改/删除仅 regulator 与 admin（requireManagerRole）。
 */
@RestController
@RequestMapping("/api/enterprises")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    /** 企业列表：可按企业名 name、行业 industry 过滤，登录用户即可访问 */
    @GetMapping
    public List<Enterprise> list(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) String industry) {
        return enterpriseService.list(name, industry);
    }

    /** 行业字典：返回去重后的行业列表，用于企业表单/筛选下拉选择 */
    @GetMapping("/industries")
    public List<String> industries() {
        return enterpriseService.industries();
    }

    /** 按企业编号查询企业详情 */
    @GetMapping("/{id}")
    public Enterprise get(@PathVariable String id) {
        return enterpriseService.getById(id);
    }

    /** 新建企业，body 为 Enterprise 实体；仅监管/管理员可执行 */
    @PostMapping
    public Enterprise create(@RequestBody Enterprise enterprise) {
        requireManagerRole();
        return enterpriseService.create(enterprise);
    }

    /** 修改企业档案；仅监管/管理员可执行 */
    @PutMapping("/{id}")
    public Enterprise update(@PathVariable String id, @RequestBody Enterprise enterprise) {
        requireManagerRole();
        return enterpriseService.update(id, enterprise);
    }

    /** 删除企业；仅监管/管理员可执行 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireManagerRole();
        enterpriseService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：企业的新增、修改、删除仅监管端（regulator）与系统管理员（admin）可执行。
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行企业管理操作");
        }
    }
}
