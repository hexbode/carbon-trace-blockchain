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
 *
 * 依赖：{@link EnterpriseService}（企业档案读写；编号生成、默认状态与存在性校验在 Service 内）。
 * 安全边界：
 *   1. 查询对企业端开放：企业列表会返回全部企业档案（含联系方式、统一社会信用代码），
 *      若需限制企业端只看本企业，应在 Service 侧补过滤；
 *   2. 写操作仅监管/管理员，避免企业自行修改行业、信用代码等影响配额与核算口径的字段；
 *   3. 本控制器只读写数据库，不触发链上写操作。
 */
@RestController
@RequestMapping("/api/enterprises")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    /** 构造注入企业服务；控制器无状态 */
    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    /**
     * GET /api/enterprises —— 企业列表。
     *
     * 权限：登录即可（未按企业归属限制）。
     * 参数：name 企业名模糊过滤、industry 行业精确过滤，均可省略。
     * 返回：List&lt;Enterprise&gt;，按企业编号升序。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<Enterprise> list(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) String industry) {
        return enterpriseService.list(name, industry);
    }

    /**
     * GET /api/enterprises/industries —— 行业下拉字典。
     *
     * 权限：登录即可。
     * 返回：去重、升序的行业名称列表（全表读取后在内存去重，无企业数据时为空列表）。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping("/industries")
    public List<String> industries() {
        return enterpriseService.industries();
    }

    /**
     * GET /api/enterprises/{id} —— 企业详情。
     *
     * 权限：登录即可。
     * 参数：路径 id 为企业编号（ENT 开头）。
     * 返回：Enterprise 实体。
     * 错误码：401 未登录；404 企业不存在。
     * 链上写：无。
     */
    @GetMapping("/{id}")
    public Enterprise get(@PathVariable String id) {
        return enterpriseService.getById(id);
    }

    /**
     * POST /api/enterprises —— 新建企业。
     *
     * 权限：仅 regulator / admin。
     * 参数（JSON body）：Enterprise 实体（name / industry / creditCode 为库表非空字段）；
     * id 为空时按 ENT + 年份 + 4 位序号生成，status 缺省 active，注册时间缺省当日。
     * 返回：落库后的 Enterprise。
     * 错误码：400 必填字段缺失、信用代码重复或编号冲突；401 未登录；403 角色不足。
     * 链上写：无。
     */
    @PostMapping
    public Enterprise create(@RequestBody Enterprise enterprise) {
        requireManagerRole();
        return enterpriseService.create(enterprise);
    }

    /**
     * PUT /api/enterprises/{id} —— 修改企业档案。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为准（覆盖 body 中的 id，防止改写其他企业）；null 字段保持原值。
     * 返回：更新后回查的 Enterprise。
     * 错误码：401 未登录；403 角色不足；404 企业不存在；400 字段非法。
     * 链上写：无。
     */
    @PutMapping("/{id}")
    public Enterprise update(@PathVariable String id, @RequestBody Enterprise enterprise) {
        requireManagerRole();
        return enterpriseService.update(id, enterprise);
    }

    /**
     * DELETE /api/enterprises/{id} —— 删除企业。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为企业编号。
     * 返回：空 Map。
     * 错误码：401 未登录；403 角色不足；404 企业不存在；400 仍被用户账号 / 排放记录引用导致外键拒绝。
     * 链上写：无。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireManagerRole();
        enterpriseService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：企业的新增、修改、删除仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、审核端等其他角色
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行企业管理操作");
        }
    }
}
