package com.carbon.trace.chain.model;

import lombok.Data;

/**
 * 链上证书计数（供一致性核验与统计使用）
 */
@Data
public class ChainCertCount {

    /** 历史累计认证数 */
    private long total;

    /** 当前有效 */
    private long valid;

    /** 已撤销 */
    private long revoked;

    /** 已作废（业务记录被删除） */
    private long invalid;
}
