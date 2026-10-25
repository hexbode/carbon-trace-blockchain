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
 * 监管机构接口（监管端）：监管机构档案的查询与增删改。
 *
 * 暴露接口：
 *   GET    /api/regulator-organizations        机构列表（可按 name 过滤）；
 *   POST   /api/regulator-organizations        新建机构；
 *   PUT    /api/regulator-organizations/{id}   修改机构；
 *   DELETE /api/regulator-organizations/{id}   删除机构。
 * 角色约定：查询与写操作均仅 regulator 与 admin（requireRegulatorRole），
 * 企业端、审核端等其他登录角色访问返回 403。
 *
 * 依赖：{@link RegulatorOrganizationService}（机构档案读写；必填校验与存在性校验在 Service 内）。
 * 安全边界：
 *   1. 与其他字典接口不同，本控制器的查询也要求 regulator / admin，机构联系方式不向企业端、审核端开放；
 *   2. 角色判断统一放在本层，四个端点都必须调用 requireRegulatorRole，Service 不做鉴权；
 *   3. 本控制器只读写数据库，不触发链上写操作。
 */
@RestController
@RequestMapping("/api/regulator-organizations")
public class RegulatorOrganizationController {

    private final RegulatorOrganizationService regulatorOrganizationService;

    /** 构造注入监管机构服务；控制器无状态 */
    public RegulatorOrganizationController(RegulatorOrganizationService regulatorOrganizationService) {
        this.regulatorOrganizationService = regulatorOrganizationService;
    }

    /**
     * GET /api/regulator-organizations —— 监管机构列表。
     *
     * 权限：仅 regulator / admin（requireRegulatorRole），其他角色 403。
     * 参数：name 机构名模糊过滤，可省略。
     * 返回：List&lt;RegulatorOrganization&gt;，按编号升序。
     * 错误码：401 未登录；403 角色不足；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<RegulatorOrganization> list(@RequestParam(required = false) String name) {
        requireRegulatorRole();
        return regulatorOrganizationService.list(name);
    }

    /**
     * POST /api/regulator-organizations —— 新建监管机构。
     *
     * 权限：仅 regulator / admin。
     * 参数（JSON body）：RegulatorOrganization 实体；机构名称、所属区域、联系人、联系电话、
     * 办公地址、监管职责均为必填，缺一返回 400。
     * 返回：落库后的 RegulatorOrganization（编号等默认值由 Service 补齐）。
     * 错误码：400 必填字段缺失；401 未登录；403 角色不足。
     * 链上写：无。
     */
    @PostMapping
    public RegulatorOrganization create(@RequestBody RegulatorOrganization organization) {
        requireRegulatorRole();
        return regulatorOrganizationService.create(organization);
    }

    /**
     * PUT /api/regulator-organizations/{id} —— 修改监管机构。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为准（覆盖 body 中的 id，防止改写其他机构）；null 字段保持原值。
     * 返回：更新后回查的 RegulatorOrganization。
     * 错误码：400 字段非法；401 未登录；403 角色不足；404 机构不存在。
     * 链上写：无。
     */
    @PutMapping("/{id}")
    public RegulatorOrganization update(@PathVariable String id, @RequestBody RegulatorOrganization organization) {
        requireRegulatorRole();
        return regulatorOrganizationService.update(id, organization);
    }

    /**
     * DELETE /api/regulator-organizations/{id} —— 删除监管机构。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为机构编号。
     * 返回：空 Map。
     * 错误码：401 未登录；403 角色不足；404 机构不存在；400 仍被监管员账号的 orgId 引用导致外键拒绝。
     * 链上写：无。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireRegulatorRole();
        regulatorOrganizationService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：监管机构信息仅监管端（regulator）与系统管理员（admin）可操作，
     * 企业端、审核端等其他登录角色返回 403。
     *
     * @throws ApiException 403 其他角色
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作监管机构信息");
        }
    }
}
