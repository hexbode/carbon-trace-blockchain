package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 链上区块（GET /{groupId}/web3/blockByNumber/{n}）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebaseBlockDto {

    @JsonProperty("number")
    private String number;

    @JsonProperty("hash")
    private String hash;

    @JsonProperty("parentHash")
    private String parentHash;

    @JsonProperty("sealer")
    private String sealer;

    /** 块时间戳（秒） */
    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("transactionsRoot")
    private String transactionsRoot;

    @JsonProperty("nonce")
    private String nonce;

    /** 区块内交易（可能是交易对象数组，也可能是交易哈希数组） */
    @JsonProperty("transactions")
    private JsonNode transactions;

    public Long index() {
        return TransHandleReceipt.parseNumber(number);
    }

    /** 秒 → 毫秒（对外契约沿用毫秒时间戳） */
    public Long timestampMillis() {
        long seconds = TransHandleReceipt.parseNumber(timestamp);
        return seconds < 0 ? null : seconds * 1000L;
    }

    public Long nonceLong() {
        return TransHandleReceipt.parseNumber(nonce);
    }

    /** 取区块内交易哈希列表 */
    public java.util.List<String> transactionHashes() {
        java.util.List<String> hashes = new java.util.ArrayList<>();
        if (transactions == null || !transactions.isArray()) {
            return hashes;
        }
        for (JsonNode node : transactions) {
            if (node.isTextual()) {
                hashes.add(node.asText());
            } else if (node.hasNonNull("hash")) {
                hashes.add(node.get("hash").asText());
            } else if (node.hasNonNull("transactionHash")) {
                hashes.add(node.get("transactionHash").asText());
            }
        }
        return hashes;
    }

    /** 交易根 → 对外展示的 merkleRoot 字段 */
    public String merkleRoot() {
        return transactionsRoot == null ? "" : transactionsRoot;
    }
}
