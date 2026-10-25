package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.common.UserScopes;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.service.EmissionRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 碳排放数据记录接口：企业排放数据的填报、查询与删除。
 *
 * 暴露接口：
 *   GET    /api/emission-records         排放记录列表（可按企业/报告期/排放源/状态/关键字过滤）；
 *   GET    /api/emission-records/{id}    单条排放记录；
 *   POST   /api/emission-records         新增填报（body 含 enterpriseId/enterpriseName/period/sourceCode/activityData）；
 *   DELETE /api/emission-records/{id}    删除记录。
 * 角色约定：企业端只能填报并查看/删除本企业数据（UserScopes 强制归属过滤与归属校验）；
 * 填报与删除仅 enterprise / admin 可执行，监管、审核等其他角色无权写入。
 *
 * 依赖：{@link EmissionRecordService}（填报、查询、删除与链上镜像同步）、
 *       {@link UserScopes}（企业维度过滤与归属校验）、{@link UserContext}（取当前登录企业）。
 * 安全边界：
 *   1. 企业隔离是“过滤 + 归属校验”双保险：列表覆盖查询条件，详情/删除校验记录归属；
 *   2. 填报时额外强校验 body 中的 enterpriseId 必须等于令牌中的企业编号，防止伪造他企业数据；
 *   3. 写接口仅 enterprise / admin，审核端与监管端即使登录也不能改排放数据；
 *   4. 链上写不是直接调用：填报提交后同步合规镜像与因子，删除后作废存证并重算镜像，
 *      均通过事务提交后的回调执行，避免本地回滚导致链上写入无法撤销。
 */
@RestController
@RequestMapping("/api/emission-records")
public class EmissionRecordController {

    private final EmissionRecordService emissionRecordService;

    /** 构造注入排放记录服务；控制器无状态 */
    public EmissionRecordController(EmissionRecordService emissionRecordService) {
        this.emissionRecordService = emissionRecordService;
    }

    /**
     * GET /api/emission-records —— 排放记录列表。
     *
     * 权限：登录即可；enterprise 角色的 enterpriseId 被强制覆盖为本企业，其余角色按参数过滤。
     * 参数：enterpriseId / period / sourceCode / status / keyword，均可省略。
     * 返回：List&lt;EmissionRecord&gt;，按提交时间倒序。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<EmissionRecord> list(@RequestParam(required = false) String enterpriseId,
                                     @RequestParam(required = false) String period,
                                     @RequestParam(required = false) String sourceCode,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        // 企业端只能看自己企业的数据（忽略请求里的 enterpriseId）
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return emissionRecordService.list(enterpriseId, period, sourceCode, status, keyword);
    }

    /**
     * GET /api/emission-records/{id} —— 单条排放记录详情。
     *
     * 权限：登录即可；enterprise 角色仅能查看本企业记录，越权 403。
     * 参数：路径 id 为排放记录编号。
     * 返回：EmissionRecord。
     * 错误码：401 未登录；403 跨企业访问；404 排放记录不存在（Service 先抛 404）。
     * 链上写：无。
     */
    @GetMapping("/{id}")
    public EmissionRecord get(@PathVariable String id) {
        EmissionRecord record = emissionRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的排放记录");
        return record;
    }

    /**
     * POST /api/emission-records —— 新增排放记录填报。
     *
     * 权限：仅 enterprise / admin（requireEnterpriseRole）。
     * 参数（JSON body）：enterpriseId、period、sourceCode、activityData 必填；enterpriseName 选填
     * （缺失时从企业表补全）。排放量由服务端按 “活动数据 × 排放源因子” 计算，不接受前端传入。
     * 返回：落库后的 EmissionRecord（含自动生成的编号与电子防伪码，并同步生成同号报告记录）。
     * 错误码：400 必填参数缺失；401 未登录；403 角色不足或伪造他企业 enterpriseId；
     *        404 排放源不存在；500 编号分配冲突等未知异常。
     * 链上写：是（事务提交后同步链上本期/年度合计镜像与最近使用因子，并触发合规判定生成告警）；
     * 链不可用时按降级策略处理，不阻断填报。
     *
     * 实现备注：企业端强校验 body 中 enterpriseId 必须等于令牌中的企业编号；
     * 前置条件是该账号已绑定企业（mine 非空），未绑定企业会在 equals 处 NPE 并被兜底为 500。
     */
    @PostMapping
    public EmissionRecord create(@RequestBody Map<String, Object> body) {
        requireEnterpriseRole();
        // 企业端只能为自己企业填报（攻击验证：曾可通过伪造 enterpriseId 替别的企业创建记录/报告）
        if (UserScopes.isEnterprise()) {
            // claimed 来自请求体（不可信）；mine 来自已验签令牌，比较时必须用令牌值作为基准
            String claimed = body.get("enterpriseId") == null ? null : String.valueOf(body.get("enterpriseId"));
            String mine = UserContext.get().getEnterpriseId();
            if (!mine.equals(claimed)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "只能为本企业填报排放数据");
            }
        }
        return emissionRecordService.create(body);
    }

    /**
     * DELETE /api/emission-records/{id} —— 删除排放记录。
     *
     * 权限：仅 enterprise / admin；enterprise 角色仅能删除本企业数据，越权 403。
     * 参数：路径 id 为排放记录编号。
     * 返回：空 Map。
     * 错误码：401 未登录；403 角色不足或跨企业删除；404 排放记录不存在。
     * 链上写：是。链上存证不能物理删除，只能标记作废（invalidateByEmissionId），
     * 并在事务提交后重算该企业该报告期的链上合计镜像；同时联动清理同编号的认证/报告记录与告警。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireEnterpriseRole();
        EmissionRecord record = emissionRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "删除其他企业的排放记录");
        emissionRecordService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：数据填报与删除仅企业端（enterprise）与系统管理员（admin）可执行。
     *
     * 审核端、监管端属于“审阅方”，不参与数据写入，因此即使登录也会被拒绝。
     *
     * @throws ApiException 403 除 enterprise / admin 外的角色
     */
    private void requireEnterpriseRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if ("admin".equals(role)) {
            return;
        }
        if (!"enterprise".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作碳排放数据");
        }
    }
}
