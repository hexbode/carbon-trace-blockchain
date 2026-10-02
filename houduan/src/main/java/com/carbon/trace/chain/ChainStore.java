package com.carbon.trace.chain;

import com.carbon.trace.common.BlockchainUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 链上数据存储：区块与交易保存在后端文件中（默认 ./data/blockchain.json），
 * 数据库中不保存任何哈希值。
 */
@Component
public class ChainStore {

    private static final Logger log = LoggerFactory.getLogger(ChainStore.class);

    /** 创世区块前一哈希（64 个 0） */
    public static final String ZERO_HASH = "0x" + "0".repeat(64);

    /** 每 4 笔认证交易打包成一个区块 */
    public static final int TX_PER_BLOCK = 4;

    /** PoW 难度：哈希前两位为 '0' */
    public static final int DIFFICULTY = 2;

    /** 挖矿最大尝试次数 */
    public static final int MAX_TRIES = 50000;

    private final Path file;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 内存中的链，按区块高度升序 */
    private final List<Block> blocks = new ArrayList<>();

    public ChainStore(@Value("${carbon.blockchain.file:./data/blockchain.json}") String filePath) {
        this.file = Path.of(filePath).toAbsolutePath().normalize();
    }

    /** 启动时从文件加载链，文件不存在则初始化创世区块 */
    @PostConstruct
    public synchronized void load() {
        blocks.clear();
        if (Files.exists(file)) {
            try {
                ChainDocument document = objectMapper.readValue(
                        Files.readString(file, StandardCharsets.UTF_8), ChainDocument.class);
                if (document != null && document.getBlocks() != null) {
                    blocks.addAll(document.getBlocks());
                }
                sortBlocks();
                log.info("链上数据已加载：{} 个区块 / {} 笔交易（{}）", blockCount(), txCount(), file);
            } catch (IOException e) {
                throw new IllegalStateException("链上数据文件读取失败：" + file, e);
            }
        } else {
            blocks.add(genesis());
            save();
            log.info("链上数据文件不存在，已初始化创世区块：{}", file);
        }
    }

    // ---------- 查询 ----------

    /** 链数据文件路径 */
    public Path file() {
        return file;
    }

    public synchronized List<Block> blocks() {
        return new ArrayList<>(blocks);
    }

    public synchronized int blockCount() {
        return blocks.size();
    }

    public synchronized int txCount() {
        int total = 0;
        for (Block block : blocks) {
            total += block.getTransactions() == null ? 0 : block.getTransactions().size();
        }
        return total;
    }

    public synchronized Block blockAt(int blockIndex) {
        for (Block block : blocks) {
            if (Objects.equals(block.getBlockIndex(), blockIndex)) {
                return block;
            }
        }
        return null;
    }

    /** 链上全部交易（按区块高度升序） */
    public synchronized List<BlockTransaction> allTransactions() {
        List<BlockTransaction> all = new ArrayList<>();
        for (Block block : blocks) {
            if (block.getTransactions() != null) {
                all.addAll(block.getTransactions());
            }
        }
        return all;
    }

    /**
     * 按电子防伪码或交易哈希定位交易。
     * 电子防伪码：大小写敏感、精确匹配；交易哈希：忽略大小写、忽略 0x 前缀。
     */
    public synchronized BlockTransaction findByCodeOrHash(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String code = input.trim();
        String hash = normalize(input);
        for (BlockTransaction tx : allTransactions()) {
            if (Objects.equals(code, tx.getFingerprint())
                    || Objects.equals(hash, normalize(tx.getTxHash()))) {
                return tx;
            }
        }
        return null;
    }

    /** 按排放记录编号批量取链上交易 */
    public synchronized Map<String, BlockTransaction> byEmissionIds(List<String> emissionIds) {
        if (emissionIds == null || emissionIds.isEmpty()) {
            return Map.of();
        }
        return allTransactions().stream()
                .filter(tx -> emissionIds.contains(tx.getEmissionId()))
                .collect(Collectors.toMap(BlockTransaction::getEmissionId, tx -> tx, (a, b) -> a));
    }

    // ---------- 写入 ----------

    /**
     * 追加一笔认证交易（每 TX_PER_BLOCK 笔打包成一个区块），返回所在区块高度。
     */
    public synchronized Integer appendTransaction(Map<String, Object> tx) {
        int blockIndex = (txCount() / TX_PER_BLOCK) + 1;

        String txHash = (String) tx.get("txHash");
        if (txHash == null) {
            txHash = BlockchainUtil.generateTxHash(tx);
        }

        // 汇总该区块已有交易哈希 + 新交易，计算默克尔根
        List<String> hashes = txHashesInBlock(blockIndex);
        hashes.add(txHash);
        String merkleRoot = BlockchainUtil.merkleRoot(hashes);

        Block block = blockAt(blockIndex);
        long timestamp = block != null ? block.getTimestamp() : System.currentTimeMillis();
        String previousHash = previousHashOf(blockIndex);
        String miner = "监管节点-0" + ((blockIndex % 3) + 1);

        BlockchainUtil.MiningResult mined = BlockchainUtil.mineBlock(
                blockIndex, previousHash, timestamp, merkleRoot, DIFFICULTY, MAX_TRIES);

        Block target = block != null ? block : new Block();
        target.setBlockIndex(blockIndex);
        target.setHash(mined.hash());
        target.setPreviousHash(previousHash);
        target.setTimestamp(timestamp);
        target.setMerkleRoot(merkleRoot);
        target.setNonce(mined.nonce());
        target.setMiner(miner);
        target.setTxCount(hashes.size());
        if (target.getTransactions() == null) {
            target.setTransactions(new ArrayList<>());
        }

        BlockTransaction entity = new BlockTransaction();
        entity.setId(nextTransactionId());
        entity.setBlockIndex(blockIndex);
        entity.setTxHash(txHash);
        entity.setEmissionId((String) tx.get("emissionId"));
        entity.setEnterpriseName((String) tx.get("enterpriseName"));
        entity.setPeriod((String) tx.get("period"));
        entity.setAmount(toBigDecimal(tx.get("amount")));
        entity.setFingerprint((String) tx.get("fingerprint"));
        entity.setAuditor((String) tx.get("auditor"));
        target.getTransactions().add(entity);

        putBlock(target);
        save();
        return blockIndex;
    }

    /** 移除某个排放记录对应的链上交易，并重算受影响的区块，保持链自洽 */
    public synchronized boolean removeByEmissionId(String emissionId) {
        if (emissionId == null) {
            return false;
        }
        int affected = Integer.MAX_VALUE;
        for (Block block : blocks) {
            if (block.getTransactions() == null) {
                continue;
            }
            if (block.getTransactions().removeIf(tx -> Objects.equals(tx.getEmissionId(), emissionId))) {
                affected = Math.min(affected, block.getBlockIndex());
            }
        }
        if (affected == Integer.MAX_VALUE) {
            return false;
        }
        rehashFrom(affected);
        save();
        return true;
    }

    // ---------- 内部工具 ----------

    private List<String> txHashesInBlock(int blockIndex) {
        Block block = blockAt(blockIndex);
        if (block == null || block.getTransactions() == null) {
            return new ArrayList<>();
        }
        return block.getTransactions().stream()
                .map(BlockTransaction::getTxHash)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String previousHashOf(int blockIndex) {
        if (blockIndex <= 0) {
            return ZERO_HASH;
        }
        Block previous = blockAt(blockIndex - 1);
        return previous != null && previous.getHash() != null ? previous.getHash() : ZERO_HASH;
    }

    private long nextTransactionId() {
        long max = 0;
        for (BlockTransaction tx : allTransactions()) {
            if (tx.getId() != null && tx.getId() > max) {
                max = tx.getId();
            }
        }
        return max + 1;
    }

    private void putBlock(Block block) {
        for (int i = 0; i < blocks.size(); i++) {
            if (Objects.equals(blocks.get(i).getBlockIndex(), block.getBlockIndex())) {
                blocks.set(i, block);
                return;
            }
        }
        blocks.add(block);
        sortBlocks();
    }

    /** 从指定区块高度开始重算默克尔根并重新挖矿，保持哈希链自洽 */
    private void rehashFrom(int fromIndex) {
        String previousHash = fromIndex <= 0 ? ZERO_HASH : previousHashOf(fromIndex);
        for (Block block : blocks) {
            if (block.getBlockIndex() < fromIndex) {
                continue;
            }
            List<String> hashes = txHashesInBlock(block.getBlockIndex());
            String merkleRoot = BlockchainUtil.merkleRoot(hashes);
            BlockchainUtil.MiningResult mined = BlockchainUtil.mineBlock(
                    block.getBlockIndex(), previousHash, block.getTimestamp(), merkleRoot, DIFFICULTY, MAX_TRIES);
            block.setPreviousHash(previousHash);
            block.setMerkleRoot(merkleRoot);
            block.setNonce(mined.nonce());
            block.setHash(mined.hash());
            block.setTxCount(hashes.size());
            previousHash = mined.hash();
        }
    }

    private Block genesis() {
        long timestamp = System.currentTimeMillis();
        String merkleRoot = BlockchainUtil.merkleRoot(List.of());
        Block block = new Block();
        block.setBlockIndex(0);
        block.setPreviousHash(ZERO_HASH);
        block.setTimestamp(timestamp);
        block.setMerkleRoot(merkleRoot);
        block.setNonce(0);
        block.setMiner("监管节点-01");
        block.setTxCount(0);
        block.setTransactions(new ArrayList<>());
        block.setHash(BlockchainUtil.generateBlockHash(0, ZERO_HASH, timestamp, merkleRoot, 0));
        return block;
    }

    private void sortBlocks() {
        blocks.sort(Comparator.comparing(Block::getBlockIndex));
    }

    /** 将链写入文件（UTF-8，无 BOM） */
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            ChainDocument document = new ChainDocument();
            document.setBlocks(new ArrayList<>(blocks));
            Files.writeString(file, objectMapper.writeValueAsString(document), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("链上数据文件写入失败：" + file, e);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }
}
