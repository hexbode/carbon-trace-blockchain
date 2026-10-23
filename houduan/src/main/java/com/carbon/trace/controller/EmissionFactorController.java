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
 */
@RestController
@RequestMapping("/api/emission-factors")
public class EmissionFactorController {

    private final EmissionFactorService emissionFactorService;

    public EmissionFactorController(EmissionFactorService emissionFactorService) {
        this.emissionFactorService = emissionFactorService;
    }

    /** 因子列表：可按类别 category 与关键字 keyword 过滤 */
    @GetMapping
    public List<EmissionFactor> list(@RequestParam(required = false) String category,
                                     @RequestParam(required = false) String keyword) {
        return emissionFactorService.list(category, keyword);
    }

    /** 新建排放因子，body 为 EmissionFactor 实体；仅监管/管理员可执行 */
    @PostMapping
    public EmissionFactor create(@RequestBody EmissionFactor factor) {
        requireManagerRole();
        return emissionFactorService.create(factor);
    }

    /** 修改排放因子；仅监管/管理员可执行 */
    @PutMapping("/{id}")
    public EmissionFactor update(@PathVariable Integer id, @RequestBody EmissionFactor factor) {
        requireManagerRole();
        return emissionFactorService.update(id, factor);
    }

    /** 删除排放因子；仅监管/管理员可执行 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        requireManagerRole();
        emissionFactorService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * 角色限制：排放因子的新增、修改、删除仅监管端（regulator）与系统管理员（admin）可执行。
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行排放因子管理操作");
        }
    }
}
