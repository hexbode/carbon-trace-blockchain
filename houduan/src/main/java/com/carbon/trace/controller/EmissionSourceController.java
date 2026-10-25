package com.carbon.trace.controller;

import com.carbon.trace.entity.EmissionSource;
import com.carbon.trace.service.EmissionSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排放源接口：排放源（能源、物料等）字典查询。
 *
 * 暴露接口：GET /api/emission-sources 返回全部排放源列表，供排放填报表单选择；
 * 排放源为公共字典，登录即可访问，无写接口。
 *
 * 依赖：{@link EmissionSourceService}（只读查询；字典数据由数据库初始化脚本维护）。
 * 安全边界：不提供写接口，字典内容无法通过 API 篡改；接口只要求登录，无企业维度限制；
 * 链上写：无。
 */
@RestController
@RequestMapping("/api/emission-sources")
public class EmissionSourceController {

    private final EmissionSourceService emissionSourceService;

    /** 构造注入排放源服务；控制器无状态 */
    public EmissionSourceController(EmissionSourceService emissionSourceService) {
        this.emissionSourceService = emissionSourceService;
    }

    /**
     * GET /api/emission-sources —— 全部排放源字典。
     *
     * 权限：登录即可。
     * 参数：无。
     * 返回：List&lt;EmissionSource&gt;，供排放填报表单下拉选择。
     * 错误码：401 未登录；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<EmissionSource> list() {
        return emissionSourceService.list();
    }
}
