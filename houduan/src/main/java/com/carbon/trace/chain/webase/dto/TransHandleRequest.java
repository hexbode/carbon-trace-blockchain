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
 *
 * 字段整体采用「按需下发」：使用 {@link JsonInclude.Include#NON_NULL}，未设置的字段不会出现在
 * 请求体中，避免把空的 signUserId 一并发送导致 WeBASE 走错签名分支。
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

    /** 合约名（需与 WeBASE 中登记的一致，用于匹配合约记录） */
    private String contractName;

    /** 合约地址（部署产物） */
    private String contractAddress;

    /** 调用的函数名，必须与 ABI 中的 name 完全一致 */
    private String funcName;

    /**
     * 入参列表，按合约函数参数顺序。由 WebaseChainGateway#encodeFuncParams 统一转换。
     *
     * WeBASE 3.x 的 funcParam 是 List&lt;String&gt;，每个元素按「原文」参与 ABI 编码
     * （实测结论：字符串不能加引号，否则引号会被一并写进链上数据）：
     *  - 数字 → 1000；布尔 → true；
     *  - 字符串 → 直接给内容，如 CR000001；
     *  - 数组 → JSON 数组文本，形如 ["0x..."]（元素必须是 JSON 字符串）。
     */
    private List<String> funcParam = new ArrayList<>();

    /** 合约 ABI（JSON 数组），WeBASE 用它完成入参编码与返回值解码 */
    private JsonNode contractAbi;

    /** 合约版本（普通合约留空；非空会被 WeBASE 当作需要版本匹配的合约处理） */
    private String version;
}
