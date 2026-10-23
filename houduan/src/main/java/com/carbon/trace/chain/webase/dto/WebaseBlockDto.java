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

    /** WeBASE 3.x 用 parentInfo 数组表达父块，首元素即上一块 */
    @JsonProperty("parentInfo")
    private JsonNode parentInfo;

    @JsonProperty("sealer")
    private String sealer;

    /** WeBASE 3.x 的 sealer 是出块节点下标，真实节点公钥在 sealerList 中 */
    @JsonProperty("sealerList")
    private JsonNode sealerList;

    /** 块时间戳（2.x 为秒，3.x 为毫秒） */
    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("transactionsRoot")
    private String transactionsRoot;

    /** WeBASE 3.x 的交易根 */
    @JsonProperty("txsRoot")
    private String txsRoot;

    @JsonProperty("stateRoot")
    private String stateRoot;

    @JsonProperty("nonce")
    private String nonce;

    /** 区块内交易（可能是交易对象数组，也可能是交易哈希数组） */
    @JsonProperty("transactions")
    private JsonNode transactions;

    /** 区块高度：解析 number 字段（可能是十进制或 0x 十六进制），失败返回 -1 */
    public Long index() {
        return TransHandleReceipt.parseNumber(number);
    }

    /** 毫秒时间戳：2.x 给秒，3.x 直接给毫秒，按量级自动识别 */
    public Long timestampMillis() {
        long ts = TransHandleReceipt.parseNumber(timestamp);
        if (ts < 0) {
            return null;
        }
        return ts > 1_000_000_000_000L ? ts : ts * 1000L;
    }

    /** 区块 nonce 的数值形式（2.x/3.x 字段含义略有差异，仅作展示） */
    public Long nonceLong() {
        return TransHandleReceipt.parseNumber(nonce);
    }

    /** 父块哈希：兼容 2.x 的 parentHash 与 3.x 的 parentInfo */
    public String previousHash() {
        if (parentHash != null && !parentHash.isBlank()) {
            return parentHash;
        }
        if (parentInfo != null && parentInfo.isArray() && parentInfo.size() > 0) {
            JsonNode first = parentInfo.get(0);
            if (first != null && first.hasNonNull("blockHash")) {
                return first.get("blockHash").asText();
            }
        }
        return "";
    }

    /** 出块节点：2.x 直接给地址；3.x 给下标，需要从 sealerList 取公钥 */
    public String sealerName() {
        if (sealer == null) {
            return "";
        }
        if (sealerList != null && sealerList.isArray() && sealer.matches("\\d+")) {
            int index = Integer.parseInt(sealer);
            if (index >= 0 && index < sealerList.size()) {
                return sealerList.get(index).asText("");
            }
        }
        return sealer;
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

    /** 交易根 → 对外展示的 merkleRoot 字段（兼容 2.x/3.x 的字段名） */
    public String merkleRoot() {
        if (transactionsRoot != null && !transactionsRoot.isBlank()) {
            return transactionsRoot;
        }
        if (txsRoot != null && !txsRoot.isBlank()) {
            return txsRoot;
        }
        return stateRoot == null ? "" : stateRoot;
    }
}
