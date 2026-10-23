package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * WeBASE-Front 合约调用请求（POST /trans/handle 或 /trans/handleWithSign）。
 *
 * 二选一的签名方式：
 *  - user（本地私钥用户地址）走 /trans/handle；
 *  - signUserId（WeBASE-Sign 托管）走 /trans/handleWithSign。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransHandleRequest {

    /** 群组标识：WeBASE 1.5.x 为 "1"，WeBASE 3.x 为 "group0" */
    private String groupId;

    /** 本地私钥模式：Front 中的用户地址 */
    private String user;

    /** Sign 托管模式：WeBASE-Sign 的用户标识 */
    private String signUserId;

    private String contractName;

    private String contractAddress;

    private String funcName;

    /**
     * 入参列表，按合约函数参数顺序。
     *
     * WeBASE 3.x 要求每个元素是参数的 JSON 字面量字符串：
     *  - 数字 → "1000"；布尔 → "true"；
     *  - 字符串 → "\"abc\""（带引号）；
     *  - 数组 → "[\"0x...\"]"。
     * 由 WebaseChainGateway#encodeFuncParams 统一转换。
     */
    private List<String> funcParam = new ArrayList<>();

    /** 合约 ABI（JSON 数组），WeBASE 用它完成入参编码与返回值解码 */
    private JsonNode contractAbi;

    /** 合约版本（普通合约留空） */
    private String version;
}
