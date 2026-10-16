package com.carbon.trace.chain.model;

import lombok.Data;

/**
 * 链统计
 */
@Data
public class ChainStats {

    /** 区块总数 */
    private long blockCount;

    /** 链上累计认证存证数 */
    private long txCount;

    /** 当前有效存证数 */
    private long activeCount;

    /** 已撤销存证数 */
    private long revokedCount;

    /** 已作废存证数 */
    private long invalidCount;

    /** 运行模式：webase / stub */
    private String mode;

    /** 链是否可用 */
    private boolean chainAvailable;
}
