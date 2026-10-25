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
 * 异常告警接口：告警的列表、详情与处理（形成闭环）。
 *
 * 暴露接口：
 *   GET /api/alerts             告警列表（可按级别 level、状态 status 过滤）；
 *   GET /api/alerts/{id}        单条告警详情；
 *   PUT /api/alerts/{id}/resolve  处理告警（标记已处理）。
 * 角色约定：查询登录即可；处理告警仅 regulator / admin（requireRegulatorRole）。
 *
 * 依赖：{@link AlertService}（告警查询与处理；告警本身由排放填报时的合规判定生成）。
 * 安全边界：
 *   1. 查询接口只校验“已登录”，不按企业维度过滤，企业端登录后可看到全量告警；
 *      若需对企业端隐藏其他企业的告警，应在 Service 查询条件中补 UserScopes 过滤；
 *   2. 处理（resolve）会写处理人与时间，属于状态变更，因此仅监管/管理员可执行；
 *   3. 告警的处理结果不回写链上，链上只保留判定结果本身。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    /** 构造注入告警服务；控制器无状态 */
    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * GET /api/alerts —— 告警列表。
     *
     * 权限：登录即可（企业端、审核端、监管端、管理员均可查看）。
     * 参数：level 告警级别（high/medium/low）、status 处理状态（pending/resolved），均可省略。
     * 返回：List&lt;Alert&gt;，按触发时间倒序。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<Alert> list(@RequestParam(required = false) String level,
                            @RequestParam(required = false) String status) {
        return alertService.list(level, status);
    }

    /**
     * GET /api/alerts/{id} —— 单条告警详情。
     *
     * 权限：登录即可。
     * 参数：路径 id 为告警编号（ALT 开头）。
     * 返回：Alert 实体。
     * 错误码：401 未登录；404 告警不存在。
     * 链上写：无。
     */
    @GetMapping("/{id}")
    public Alert get(@PathVariable String id) {
        return alertService.getById(id);
    }

    /**
     * PUT /api/alerts/{id}/resolve —— 处理告警（标记为已处理并记录处理人 / 处理时间）。
     *
     * 权限：仅 regulator / admin（requireRegulatorRole）。
     * 参数：路径 id 为告警编号。
     * 返回：处理后的 Alert。
     * 错误码：401 未登录；403 角色不足；404 告警不存在。
     * 链上写：无（仅更新本地告警状态）。
     */
    @PutMapping("/{id}/resolve")
    public Alert resolve(@PathVariable String id) {
        requireRegulatorRole();
        return alertService.resolve(id);
    }

    /**
     * 角色限制：处理告警仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、审核端等其他角色
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权处理告警");
        }
    }
}
