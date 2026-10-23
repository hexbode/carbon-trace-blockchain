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
 */
@RestController
@RequestMapping("/api/emission-sources")
public class EmissionSourceController {

    private final EmissionSourceService emissionSourceService;

    public EmissionSourceController(EmissionSourceService emissionSourceService) {
        this.emissionSourceService = emissionSourceService;
    }

    /** 返回全部排放源字典，供排放填报表单下拉选择使用 */
    @GetMapping
    public List<EmissionSource> list() {
        return emissionSourceService.list();
    }
}
