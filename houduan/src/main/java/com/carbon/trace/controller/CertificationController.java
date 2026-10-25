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
 * 认证记录接口：碳排放认证记录的查询、防伪码核验与撤销。
 *
 * 暴露接口：
 *   GET    /api/certifications                               认证记录列表（可按企业/状态/关键字过滤）；
 *   GET    /api/certifications/{id}                          单条认证记录；
 *   GET    /api/certifications/by-fingerprint/{fingerprint}  按电子防伪码精确查询（公共免登录）；
 *   POST   /api/certifications/verify                        校验防伪码 / 交易哈希（公共免登录，body {hash}）；
 *   POST   /api/certifications/{id}/revoke                   撤销认证（body {reason, detail}）。
 * 角色约定：列表/详情登录即可，企业端仅见本企业记录（UserScopes）；撤销仅 regulator / admin；
 * by-fingerprint 与 verify 位于拦截器放行白名单，供公众扫码核验。
 *
 * 依赖：{@link CertificationService}（认证记录查询；认证记录本身由报告审核通过后自动生成，本控制器无“新增认证”端点）。
 * 安全边界：
 *   1. 列表强制覆盖企业过滤，单条详情做归属校验，企业端无法读取他企业证书；
 *   2. by-fingerprint / verify 免登录且以链上存证为准：防伪码是公开索引，链上数据被篡改时以链为准；
 *   3. 撤销是终局状态，既要改库也要写链（afterCommit），链写失败不回滚本地状态；
 *   4. 认证 / 撤销的链上调用由 Service 在事务提交后触发，避免“库已回滚、链已写入”的不一致。
 */
@RestController
@RequestMapping("/api/certifications")
public class CertificationController {

    private final CertificationService certificationService;

    /** 构造注入认证服务；控制器无状态 */
    public CertificationController(CertificationService certificationService) {
        this.certificationService = certificationService;
    }

    /**
     * GET /api/certifications —— 认证记录列表。
     *
     * 权限：登录即可；enterprise 角色的 enterpriseId 被强制覆盖为本企业（UserScopes），其余角色按参数过滤。
     * 参数：enterpriseId / status / keyword，均可省略。
     * 返回：List&lt;CertificationRecord&gt;；链表字段（交易哈希、区块高度）只从本地缓存回填，不逐条回链。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<CertificationRecord> list(@RequestParam(required = false) String enterpriseId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        // 企业端只能看自己的认证记录（忽略请求里的 enterpriseId）
        enterpriseId = UserScopes.scopedEnterpriseId(enterpriseId);
        return certificationService.list(enterpriseId, status, keyword);
    }

    /**
     * GET /api/certifications/by-fingerprint/{fingerprint} —— 按电子防伪码查询证书（免登录）。
     *
     * 权限：无需登录（位于拦截器白名单），供公众扫码核验。
     * 参数：路径 fingerprint 为 12 位电子防伪码。
     * 返回：CertificationRecord，防伪码与链上交易信息以链上存证为准覆盖。
     * 错误码：404 防伪码为空或链上查不到对应存证；502/503 链层调用失败。
     * 链上写：无（只读）。
     */
    @GetMapping("/by-fingerprint/{fingerprint}")
    public CertificationRecord getByFingerprint(@PathVariable String fingerprint) {
        return certificationService.getByFingerprint(fingerprint);
    }

    /**
     * POST /api/certifications/verify —— 核验防伪码 / 交易哈希（免登录）。
     *
     * 权限：无需登录（位于拦截器白名单）。
     * 参数（JSON body）：hash 字段，防伪码与交易哈希都通过该字段传入。
     * 返回：{valid: boolean, msg: 提示文案, cert: 证书信息或 null}；
     * 已撤销 / 已作废的证书 valid=false，但仍返回 cert 供前端展示撤销原因。
     * 错误码：本接口对查不到的记录返回 200 + valid=false；502/503 链层调用失败。
     * 链上写：无（只读，以链上状态为唯一依据）。
     */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> body) {
        Object hash = body.get("hash");
        return certificationService.verify(hash == null ? null : String.valueOf(hash));
    }

    /**
     * GET /api/certifications/{id} —— 单条认证记录详情。
     *
     * 权限：登录即可；enterprise 角色仅能查看本企业记录，越权 403。
     * 参数：路径 id 为认证编号（CR 开头）。
     * 返回：CertificationRecord，并实时回填链上防伪码 / 交易哈希 / 区块信息。
     * 错误码：401 未登录；403 跨企业访问；404 认证记录不存在（Service 先抛 404，归属校验不会收到 null）；
     *        502/503 回链失败。
     * 链上写：无（只读）。
     */
    @GetMapping("/{id}")
    public CertificationRecord get(@PathVariable String id) {
        CertificationRecord record = certificationService.getById(id);
        // 企业端只能查看归属自己的认证记录
        UserScopes.requireOwnership(record == null ? null : record.getEnterpriseId(), "查看其他企业的认证记录");
        return record;
    }

    /**
     * POST /api/certifications/{id}/revoke —— 撤销认证。
     *
     * 权限：仅 regulator / admin（requireRegulatorRole）。
     * 参数：路径 id 为认证编号；body {reason 必填, detail 选填}，二者拼为撤销原因落库并上链。
     * 返回：撤销后的 CertificationRecord（status=revoked，含撤销原因与时间）。
     * 错误码：400 撤销原因缺失；401 未登录；403 角色不足；404 认证记录不存在；
     *        409 链上已是终局状态；502/503 链写失败。
     * 链上写：是。链上状态置为 REVOKED 且不可逆；链写安排在本地事务提交后执行（afterCommit），
     * 因此链写失败时本地已置为 revoked，需要运维按日志补偿。
     */
    @PostMapping("/{id}/revoke")
    public CertificationRecord revoke(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireRegulatorRole();
        return certificationService.revoke(id, body);
    }

    /**
     * 角色限制：撤销认证仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、审核端等其他角色
     */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行撤销认证操作");
        }
    }
}
