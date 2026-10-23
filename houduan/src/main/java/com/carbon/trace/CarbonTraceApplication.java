package com.carbon.trace;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类：企业碳排放数据认证与追溯系统后端。
 * 装配说明：
 *  - {@code @SpringBootApplication} 聚合组件扫描与自动配置；
 *  - {@code @EnableScheduling} 开启定时任务（如告警/链上完整性巡检等周期任务）；
 *  - {@code @MapperScan("com.carbon.trace.mapper")} 批量注册 MyBatis-Plus Mapper 接口；
 *  - 主流程仅为委托 {@code SpringApplication.run} 引导容器启动，无额外业务逻辑。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.carbon.trace.mapper")
public class CarbonTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarbonTraceApplication.class, args);
    }
}
