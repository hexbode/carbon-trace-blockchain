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
 * 审核机构接口：审核机构档案的查询与增删改。
 *
 * 暴露接口：
 *   GET    /api/audit-organizations        机构列表（可按 name 过滤）；
 *   POST   /api/audit-organizations        新建机构；
 *   PUT    /api/audit-organizations/{id}   修改机构；
 *   DELETE /api/audit-organizations/{id}   删除机构。
 * 角色约定：查询登录即可；新增/修改/删除仅 regulator 与 admin（requireManagerRole）。
 *
 * 依赖：{@link AuditOrganizationService}（档案读写；编号生成、默认状态与存在性校验都在 Service 内）。
 * 安全边界：
 *   1. 查询对所有登录角色开放，机构档案不含企业数据，但含联系方式，接口未按机构隔离；
 *   2. 写操作的角色判断在本层，Service 不做鉴权，因此新增端点时必须沿用 requireManagerRole；
 *   3. 本控制器只读写数据库，不触发链上写操作。
 */
@RestController
@RequestMapping("/api/audit-organizations")
public class AuditOrganizationController {

    private final AuditOrganizationService auditOrganizationService;

    /** 构造注入审核机构服务；控制器无状态 */
    public AuditOrganizationController(AuditOrganizationService auditOrganizationService) {
        this.auditOrganizationService = auditOrganizationService;
    }

    /**
     * GET /api/audit-organizations —— 审核机构列表。
     *
     * 权限：登录即可。
     * 参数：name 机构名模糊过滤，可省略。
     * 返回：List&lt;AuditOrganization&gt;，按编号升序。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<AuditOrganization> list(@RequestParam(required = false) String name) {
        return auditOrganizationService.list(name);
    }

    /**
     * POST /api/audit-organizations —— 新建审核机构。
     *
     * 权限：仅 regulator / admin。
     * 参数（JSON body）：AuditOrganization 实体；id 为空时自动生成 AUD + 3 位序号，
     * status 缺省 active，createdAt 缺省当日。
     * 返回：落库后的 AuditOrganization。
     * 错误码：400 必填字段缺失 / 编号冲突；401 未登录；403 角色不足。
     * 链上写：无。
     */
    @PostMapping
    public AuditOrganization create(@RequestBody AuditOrganization organization) {
        requireManagerRole();
        return auditOrganizationService.create(organization);
    }

    /**
     * PUT /api/audit-organizations/{id} —— 修改审核机构。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为准（会覆盖 body 中的 id，防止改写其他机构）；body 为待更新字段，
     * null 字段保持原值。
     * 返回：更新后回查的 AuditOrganization。
     * 错误码：401 未登录；403 角色不足；404 机构不存在；400 字段非法。
     * 链上写：无。
     */
    @PutMapping("/{id}")
    public AuditOrganization update(@PathVariable String id, @RequestBody AuditOrganization organization) {
        requireManagerRole();
        return auditOrganizationService.update(id, organization);
    }

    /**
     * DELETE /api/audit-organizations/{id} —— 删除审核机构。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为机构编号。
     * 返回：空 Map。
     * 错误码：401 未登录；403 角色不足；404 机构不存在；400 仍被审核员账号的 orgId 引用导致外键拒绝。
     * 链上写：无。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireManagerRole();
        auditOrganizationService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：审核机构的新增、修改、删除仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、审核端等其他角色
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行审核机构管理操作");
        }
    }
}
