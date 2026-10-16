package com.carbon.trace.chain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 区块视图（字段映射自真实链的区块，对外契约与旧实现保持一致）
 */
@Data
public class ChainBlockView {

    /** 区块高度 */
    private Long index;

    /** 区块哈希 */
    private String hash;

    /** 前一区块哈希 */
    private String previousHash;

    /** 区块时间戳（毫秒） */
    private Long timestamp;

    /** 交易根（对应旧实现的默克尔根） */
    private String merkleRoot;

    /** 随机数 */
    private Long nonce;

    /** 打包节点（对应真实链的 sealer） */
    private String miner;

    /** 区块内交易明细 */
    private List<ChainTxView> transactions = new ArrayList<>();
}
