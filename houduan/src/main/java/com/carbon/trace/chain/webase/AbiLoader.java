package com.carbon.trace.chain.webase;

import com.carbon.trace.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约 ABI 装载器。
 *
 * ABI 属于代码（随合约源码版本演进、需要 code review），因此随仓库提供；
 * WeBASE-Front 的 /trans/handle 与 /event/eventLogs/list 都要求传入 contractAbi。
 */
@Component
public class AbiLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 按 location（classpath:/file: 前缀）的 ABI 缓存；ABI 内容不可变，缓存一次即可 */
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public AbiLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /** 支持 classpath:xxx 与 file:xxx 两种前缀 */
    public JsonNode load(String location) {
        if (location == null || location.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "合约 ABI 位置未配置");
        }
        return cache.computeIfAbsent(location, this::read);
    }

    /** 真正读取并解析 ABI 文件；只接受 JSON 数组（Solidity ABI 顶层即函数/事件数组），否则视为格式错误 */
    private JsonNode read(String location) {
        Resource resource = location.startsWith("classpath:")
                ? new ClassPathResource(location.substring("classpath:".length()))
                : resourceLoader.getResource(location);
        try (InputStream input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(content);
            if (!node.isArray()) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "合约 ABI 文件格式错误（应为 JSON 数组）：" + location);
            }
            return node;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "合约 ABI 文件读取失败：" + location);
        }
    }
}
