package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.Data;

/**
 * 事件日志条目（/event/eventLogs/list 返回 data 数组中的一项）。
 *
 * 结构形如：{ "log": { "blockNumber", "transactionHash", "address", "logIndex", "topics", "data" },
 *            "data": [ 解码后的参数值 ] }
 */
@Data
public class DecodedEventLogDto {

    private JsonNode log;

    private JsonNode data;

    public JsonNode logNode() {
        return log == null ? MissingNode.getInstance() : log;
    }

    public String transactionHash() {
        JsonNode node = logNode().get("transactionHash");
        return node == null || node.isNull() ? null : node.asText();
    }

    public long blockNumber() {
        JsonNode node = logNode().get("blockNumber");
        if (node == null || node.isNull()) {
            return -1L;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        return TransHandleReceipt.parseNumber(node.asText());
    }

    /** 解码后的第 index 个参数值（不存在时返回 null） */
    public JsonNode param(int index) {
        if (data == null || !data.isArray() || data.size() <= index) {
            return null;
        }
        JsonNode node = data.get(index);
        return node == null || node.isNull() ? null : node;
    }
}
