package com.carbon.trace.chain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 区块（链上数据结构，仅保存在区块链文件中，不落数据库）
 */
@Data
public class Block {

    /** 区块高度（0 为创世区块） */
    private Integer blockIndex;

    /** 区块哈希 */
    private String hash;

    /** 前一区块哈希 */
    private String previousHash;

    /** 区块时间戳（毫秒） */
    private Long timestamp;

    /** 默克尔根 */
    private String merkleRoot;

    /** PoW 随机数 */
    private Integer nonce;

    /** 打包节点 */
    private String miner;

    /** 区块内交易数 */
    private Integer txCount;

    /** 区块内交易明细 */
    private List<BlockTransaction> transactions = new ArrayList<>();
}
