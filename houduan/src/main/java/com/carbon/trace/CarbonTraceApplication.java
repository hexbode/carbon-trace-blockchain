package com.carbon.trace;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：企业碳排放数据认证与追溯系统后端
 */
@SpringBootApplication
@MapperScan("com.carbon.trace.mapper")
public class CarbonTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarbonTraceApplication.class, args);
    }
}
