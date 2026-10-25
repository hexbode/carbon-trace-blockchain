package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.common.UserScopes;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.service.ReportRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 报告提交记录接口：企业碳排放报告的提交、审核与认证申请。
 *
 * 暴露接口：
 *   GET /api/report-records             报告记录列表（可按企业/状态/关键字过滤）；
 *   GET /api/report-records/{id}        单条报告记录；
 *   PUT /api/report-records/{id}/audit  审核报告（body {result: certified|rejected, opinion}）；
 *   PUT /api/report-records/{id}/apply  认证申请：把被驳回报告改回待审核 pending。
 * 角色约定：查询登录即可，企业端仅见本企业记录（UserScopes）；审核仅 auditor / admin；
 * 认证申请仅 enterprise / admin 且限于本企业报告。
 *
 * 依赖：{@link ReportRecordService}（报告状态流转；内部还会联动更新排放记录，并在审核通过时触发认证上链）。
 * 安全边界：
 *   1. 查询做企业维度过滤与归属校验，企业端无法读取他企业报告；
 *   2. 审核与认证申请分别对应不同的角色集合，且审核人取自令牌（UserContext.currentName），不接受前端传入；
 *   3. 报告与排放记录同编号（三表同号），审核结果会同步写两张表，因此审核接口在 Service 内使用事务；
 *   4. 审核通过会生成认证记录并写链，链写在事务提交后执行；认证申请本身不写链。
 */
@RestController
@RequestMapping("/api/report-records")
public class ReportRecordController {

    private final ReportRecordService reportRecordService;

    /** 构造注入报告记录服务；控制器无状态 */
    public ReportRecordController(ReportRecordService reportRecordService) {
        this.reportRecordService = reportRecordService;
    }

    /**
     * GET /api/report-records —— 报告记录列表。
     *
     * 权限：登录即可；enterprise 角色的 enterpriseId 被强制覆盖为本企业，其余角色按参数过滤。
     * 参数：enterpriseId / status / keyword，均可省略。
     * 返回：List&lt;ReportRecord&gt;，按提交时间倒序。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<ReportRecord> list(@RequestParam(required = false) String enterpriseId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String keyword) {
        // 企业端只能看自己的报告记录
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return reportRecordService.list(enterpriseId, status, keyword);
    }

    /**
     * GET /api/report-records/{id} —— 单条报告记录详情。
     *
     * 权限：登录即可；enterprise 角色仅能查看本企业记录，越权 403。
     * 参数：路径 id 为报告编号（与排放记录同号）。
     * 返回：ReportRecord。
     * 错误码：401 未登录；403 跨企业访问；404 报告记录不存在（Service 先抛 404）。
     * 链上写：无。
     */
    @GetMapping("/{id}")
    public ReportRecord get(@PathVariable String id) {
        ReportRecord record = reportRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的报告记录");
        return record;
    }

    /**
     * PUT /api/report-records/{id}/audit —— 审核报告。
     *
     * 权限：仅 auditor / admin（requireAuditorRole）。
     * 参数：路径 id 为报告编号；body {result 必填：certified / rejected, opinion 选填}。
     * 返回：审核后重新读取的 ReportRecord（含审核人、审核时间、审核意见）。
     * 错误码：400 审核结果非 certified / rejected；401 未登录；403 角色不足；404 报告记录不存在；
     *        409 链上状态冲突（由链层映射）；502/503 认证上链失败。
     * 链上写：是。仅当 result=certified 时生成认证记录并写链（事务提交后执行）；
     * 同时会把关联排放记录的状态同步为审核结果，事务内一并提交。
     */
    @PutMapping("/{id}/audit")
    public ReportRecord audit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAuditorRole();
        return reportRecordService.audit(id, body);
    }

    /**
     * PUT /api/report-records/{id}/apply —— 重新发起认证申请。
     *
     * 权限：仅 enterprise / admin（requireEnterpriseRole）；enterprise 角色仅限本企业报告，越权 403。
     * 参数：路径 id 为报告编号。
     * 返回：状态已改为 pending 的 ReportRecord（审核人 / 审核时间 / 审核意见被清空）。
     * 错误码：401 未登录；403 角色不足或跨企业申请；404 报告记录不存在。
     * 链上写：无（只改本地状态）。
     *
     * 实现备注：Service 未校验报告当前状态，因此对已认证（certified）的报告调用本接口也会被改回 pending；
     * 若业务只允许“被驳回后重新申请”，该校验需在 Service 侧补充。
     */
    @PutMapping("/{id}/apply")
    public ReportRecord apply(@PathVariable String id) {
        requireEnterpriseRole();
        ReportRecord record = reportRecordService.getById(id);
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "对其他企业的报告发起认证申请");
        return reportRecordService.apply(id);
    }

    /**
     * 角色限制：审核操作仅审核端（auditor）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、监管端等其他角色
     */
    private void requireAuditorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"auditor".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行审核操作");
        }
    }

    /**
     * 角色限制：认证申请仅企业端（enterprise）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 审核端、监管端等其他角色
     */
    private void requireEnterpriseRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"enterprise".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行认证申请");
        }
    }
}
