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

    private Integer groupId;

    /** 本地私钥模式：Front 中的用户地址 */
    private String user;

    /** Sign 托管模式：WeBASE-Sign 的用户标识 */
    private String signUserId;

    private String contractName;

    private String contractAddress;

    private String funcName;

    /** 入参列表，按合约函数参数顺序 */
    private List<Object> funcParam = new ArrayList<>();

    /** 合约 ABI（JSON 数组），WeBASE 用它完成入参编码与返回值解码 */
    private JsonNode contractAbi;

    /** 合约版本（普通合约留空） */
    private String version;
}
