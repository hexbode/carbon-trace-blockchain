package com.carbon.trace.chain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 区块链文件的数据结构：{ "blocks": [ ... ] }
 */
@Data
public class ChainDocument {

    /** 按区块高度升序排列的区块列表 */
    private List<Block> blocks = new ArrayList<>();
}
