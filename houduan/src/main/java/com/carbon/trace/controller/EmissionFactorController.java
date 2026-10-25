package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.EmissionFactor;
import com.carbon.trace.service.EmissionFactorService;
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
 * 碳排放因子库接口：排放因子的查询与维护（因子用于把活动数据换算为碳排放量）。
 *
 * 暴露接口：
 *   GET    /api/emission-factors        因子列表（可按类别 category、关键字 keyword 过滤）；
 *   POST   /api/emission-factors        新建因子；
 *   PUT    /api/emission-factors/{id}   修改因子；
 *   DELETE /api/emission-factors/{id}   删除因子。
 * 角色约定：查询登录即可；新增/修改/删除仅 regulator 与 admin（requireManagerRole）。
 *
 * 依赖：{@link EmissionFactorService}（因子库读写；主键生成、默认状态与存在性校验在 Service 内）。
 * 安全边界：
 *   1. 因子库决定“活动数据 × 因子”的核算口径，属于监管侧基础数据，因此写操作仅限监管/管理员；
 *   2. 因子库没有「排放源 + 类别」唯一约束，允许登记重复口径，重复数据不会报错，需要业务侧自行约束；
 *   3. 本控制器只读写数据库，不触发链上写操作；已落库的排放记录保存的是因子数值快照，改库不影响历史核算。
 */
@RestController
@RequestMapping("/api/emission-factors")
public class EmissionFactorController {

    private final EmissionFactorService emissionFactorService;

    /** 构造注入排放因子服务；控制器无状态 */
    public EmissionFactorController(EmissionFactorService emissionFactorService) {
        this.emissionFactorService = emissionFactorService;
    }

    /**
     * GET /api/emission-factors —— 排放因子列表。
     *
     * 权限：登录即可。
     * 参数：category 类别精确过滤、keyword 排放源名称模糊过滤，均可省略；
     * 停用（inactive）的因子也会返回，是否可选由前端按 status 判断。
     * 返回：List&lt;EmissionFactor&gt;，按主键升序。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<EmissionFactor> list(@RequestParam(required = false) String category,
                                     @RequestParam(required = false) String keyword) {
        return emissionFactorService.list(category, keyword);
    }

    /**
     * POST /api/emission-factors —— 新建排放因子。
     *
     * 权限：仅 regulator / admin。
     * 参数（JSON body）：EmissionFactor 实体（source / category / factor / unit 为库表非空字段）；
     * id 为空时自动生成，status 缺省 active，updatedAt 缺省当日。
     * 返回：落库后的 EmissionFactor。
     * 错误码：400 必填字段缺失或主键冲突；401 未登录；403 角色不足。
     * 链上写：无。
     */
    @PostMapping
    public EmissionFactor create(@RequestBody EmissionFactor factor) {
        requireManagerRole();
        return emissionFactorService.create(factor);
    }

    /**
     * PUT /api/emission-factors/{id} —— 修改排放因子。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为准（覆盖 body 中的 id）；updatedAt 由服务端强制刷新为当日，null 字段保持原值。
     * 返回：更新后回查的 EmissionFactor。
     * 错误码：401 未登录；403 角色不足；404 因子不存在；400 字段非法。
     * 链上写：无。
     */
    @PutMapping("/{id}")
    public EmissionFactor update(@PathVariable Integer id, @RequestBody EmissionFactor factor) {
        requireManagerRole();
        return emissionFactorService.update(id, factor);
    }

    /**
     * DELETE /api/emission-factors/{id} —— 删除排放因子。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为因子主键。
     * 返回：空 Map。
     * 错误码：401 未登录；403 角色不足；404 因子不存在。
     * 链上写：无；历史排放记录保存的是因子数值快照，删除不影响已核算结果。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        requireManagerRole();
        emissionFactorService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：排放因子的新增、修改、删除仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 企业端、审核端等其他角色
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行排放因子管理操作");
        }
    }
}
