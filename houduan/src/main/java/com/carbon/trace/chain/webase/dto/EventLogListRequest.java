package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * WeBASE-Front 事件日志查询请求（POST /event/eventLogs/list）。
 * 该接口按 ABI 解码事件，用于把链上交易哈希反查回认证编号。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventLogListRequest {

    private Integer groupId;

    /** 合约 ABI（JSON 数组），必填 */
    private JsonNode contractAbi;

    private Long fromBlock;

    private Long toBlock;

    private String contractAddress;

    private Integer pageSize;

    private Integer pageNumber;
}
