package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.common.UserScopes;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.service.CertificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 认证记录接口
 */
@RestController
@RequestMapping("/api/certifications")
public class CertificationController {

    private final CertificationService certificationService;

    public CertificationController(CertificationService certificationService) {
        this.certificationService = certificationService;
    }

    @GetMapping
    public List<CertificationRecord> list(@RequestParam(required = false) String enterpriseId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        // 企业端只能看自己的认证记录（忽略请求里的 enterpriseId）
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return certificationService.list(enterpriseId, status, keyword);
    }

    /** 按电子防伪码精确查询（公共端；不再支持按认证编号查询） */
    @GetMapping("/by-fingerprint/{fingerprint}")
    public CertificationRecord getByFingerprint(@PathVariable String fingerprint) {
        return certificationService.getByFingerprint(fingerprint);
    }

    /** 校验防伪码 / 交易哈希：body {hash} */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> body) {
        Object hash = body.get("hash");
        return certificationService.verify(hash == null ? null : String.valueOf(hash));
    }

    @GetMapping("/{id}")
    public CertificationRecord get(@PathVariable String id) {
        CertificationRecord record = certificationService.getById(id);
        // 企业端只能查看归属自己的认证记录
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的认证记录");
        return record;
    }

    /** 撤销认证：body {reason, detail} */
    @PostMapping("/{id}/revoke")
    public CertificationRecord revoke(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireRegulatorRole();
        return certificationService.revoke(id, body);
    }

    /**
     * 角色限制：撤销认证仅监管端（regulator）与系统管理员（admin）可执行。
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行撤销认证操作");
        }
    }
}
