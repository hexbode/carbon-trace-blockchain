package com.carbon.trace.chain.webase.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 链上区块（GET /{groupId}/web3/blockByNumber/{n}）。
 *
 * 该 DTO 同时兼容 WeBASE 2.x（FISCO BCOS 2.x）与 3.x：两版在父块、出块节点、交易根、
 * 时间戳单位、交易列表形状上均有差异，所有差异都收敛在本类的辅助方法里，
 * 上层（WebaseChainGateway）只使用 {@link #index()} 等语义化访问器，不直接读字段。
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

    /**
     * 毫秒时间戳：2.x 给秒，3.x 直接给毫秒，按量级自动识别。
     *
     * 以 1_000_000_000_000 为分界（约公元 2001 年的毫秒值），小于该值视为秒并乘 1000；
     * 解析失败返回 null，调用方可保留空值而不是伪造 1970 年。
     *
     * @return 毫秒时间戳；字段缺失或非法时返回 null
     */
    public Long timestampMillis() {
        long ts = TransHandleReceipt.parseNumber(timestamp);
        if (ts < 0) {
            return null;
        }
        return ts > 1_000_000_000_000L ? ts : ts * 1000L;
    }

    /**
     * 区块 nonce 的数值形式（2.x/3.x 字段含义略有差异，仅作展示）。
     *
     * @return nonce 数值；缺失或非法时返回 -1
     */
    public Long nonceLong() {
        return TransHandleReceipt.parseNumber(nonce);
    }

    /**
     * 父块哈希：兼容 2.x 的 parentHash 与 3.x 的 parentInfo。
     *
     * 3.x 的 parentInfo 是数组，首元素为直接父块（可能还有祖先块），这里只取首元素的 blockHash。
     *
     * @return 父块哈希；两者都缺失时返回空串（不返回 null，便于前端直接展示）
     */
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

    /**
     * 出块节点：2.x 直接给地址；3.x 给下标，需要从 sealerList 取公钥。
     *
     * 仅当 sealer 是纯数字且下标合法时才查 sealerList，否则原样返回，避免把地址误当数组下标。
     *
     * @return 出块节点标识；字段缺失时返回空串
     */
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

    /**
     * 取区块内交易哈希列表。
     *
     * transactions 可能是哈希字符串数组，也可能是交易对象数组；对象中的哈希字段
     * 在 2.x 为 {@code hash}、3.x 为 {@code transactionHash}，两种都兼容。无法识别的元素直接跳过。
     *
     * @return 交易哈希列表，恒不为 null；无交易时为空列表
     */
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

    /**
     * 交易根 → 对外展示的 merkleRoot 字段（兼容 2.x/3.x 的字段名）。
     *
     * 优先级：transactionsRoot（2.x）→ txsRoot（3.x）→ stateRoot（兜底）。
     * 注意 stateRoot 是状态树根而非交易根，仅在没有交易根字段时用于占位展示。
     *
     * @return 交易根/兜底根哈希；全部缺失时返回空串
     */
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
