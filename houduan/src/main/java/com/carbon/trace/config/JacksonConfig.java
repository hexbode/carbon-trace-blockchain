package com.carbon.trace.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 时间序列化配置
 *
 * 问题：LocalDateTime 默认按 ISO-8601 输出（如 2024-10-15T03:00:09），
 *      前端表格直接展示时会多出一个 "T"，与库里的 2024-10-15 03:00:09 不一致。
 * 处理：统一按 "yyyy-MM-dd HH:mm:ss" 序列化/反序列化（LocalDate 仍是 yyyy-MM-dd，不受影响）。
 */
@Configuration
public class JacksonConfig {

    /** 统一使用的日期时间格式 */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer dateTimeCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            // 用 modulesToInstall 追加，避免覆盖 Spring Boot 自动注册的其它 Jackson 模块
            builder.modulesToInstall(module);
        };
    }
}
