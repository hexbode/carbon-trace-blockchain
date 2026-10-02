package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.Alert;
import com.carbon.trace.service.AlertService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 异常告警接口
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<Alert> list(@RequestParam(required = false) String level,
                            @RequestParam(required = false) String status) {
        return alertService.list(level, status);
    }

    @GetMapping("/{id}")
    public Alert get(@PathVariable String id) {
        return alertService.getById(id);
    }

    /** 处理告警 */
    @PutMapping("/{id}/resolve")
    public Alert resolve(@PathVariable String id) {
        requireRegulatorRole();
        return alertService.resolve(id);
    }

    /**
     * 角色限制：处理告警仅监管端（regulator）与系统管理员（admin）可执行。
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权处理告警");
        }
    }
}
