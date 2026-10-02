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
 * 企业接口
 */
@RestController
@RequestMapping("/api/enterprises")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    @GetMapping
    public List<Enterprise> list(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) String industry) {
        return enterpriseService.list(name, industry);
    }

    @GetMapping("/industries")
    public List<String> industries() {
        return enterpriseService.industries();
    }

    @GetMapping("/{id}")
    public Enterprise get(@PathVariable String id) {
        return enterpriseService.getById(id);
    }

    @PostMapping
    public Enterprise create(@RequestBody Enterprise enterprise) {
        requireManagerRole();
        return enterpriseService.create(enterprise);
    }

    @PutMapping("/{id}")
    public Enterprise update(@PathVariable String id, @RequestBody Enterprise enterprise) {
        requireManagerRole();
        return enterpriseService.update(id, enterprise);
    }

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
