package com.carbon.trace.controller;

import com.carbon.trace.entity.EmissionSource;
import com.carbon.trace.service.EmissionSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排放源接口
 */
@RestController
@RequestMapping("/api/emission-sources")
public class EmissionSourceController {

    private final EmissionSourceService emissionSourceService;

    public EmissionSourceController(EmissionSourceService emissionSourceService) {
        this.emissionSourceService = emissionSourceService;
    }

    @GetMapping
    public List<EmissionSource> list() {
        return emissionSourceService.list();
    }
}
