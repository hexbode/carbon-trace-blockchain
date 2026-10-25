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
 *
 * 依赖 Spring {@link ResourceLoader} 与 Jackson；按 location 字符串缓存解析结果，
 * ABI 内容视为不可变，因此缓存无失效机制——修改 ABI 文件后必须重启进程。
 *
 * 约束：Solidity 编译输出的 ABI 顶层必须是 JSON 数组；对象或其他形状一律视为格式错误，
 * 在启动/首次调用时快速失败，避免把错误 ABI 传给 WeBASE 造成难以理解的编解码异常。
 */
@Component
public class AbiLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 按 location（classpath:/file: 前缀）的 ABI 缓存；ABI 内容不可变，缓存一次即可 */
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    /**
     * 装配装载器。
     *
     * @param resourceLoader Spring 资源加载器，用于解析 {@code file:} 等协议前缀
     */
    public AbiLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 按位置加载并缓存 ABI。
     *
     * 支持 classpath:xxx 与 file:xxx 两种前缀；同一 location 只读取一次。
     *
     * @param location ABI 资源位置，不能为空白
     * @return ABI 的 JSON 数组节点
     * @throws ApiException 位置为空、文件不存在/不可读或顶层不是数组时抛出（500）
     */
    public JsonNode load(String location) {
        if (location == null || location.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "合约 ABI 位置未配置");
        }
        return cache.computeIfAbsent(location, this::read);
    }

    /**
     * 真正读取并解析 ABI 文件；只接受 JSON 数组（Solidity ABI 顶层即函数/事件数组），否则视为格式错误。
     *
     * classpath: 前缀单独走 {@link ClassPathResource}，其余（含 file:）交给 ResourceLoader 解析；
     * 读取使用 UTF-8，避免中文注释/错误消息在不同平台乱码。
     *
     * @param location 资源位置
     * @return ABI 数组节点
     * @throws ApiException 读取失败或顶层不是数组时抛出
     */
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
