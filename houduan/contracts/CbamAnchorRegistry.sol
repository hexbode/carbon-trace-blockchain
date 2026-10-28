// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;

/**
 * @title 碳链通 · CBAM 申报包存证锚点
 * @notice 只保存「申报包编号 → 快照哈希 + 数据版本」的锚点，不保存申报内容本身。
 *         快照原文仍由业务库与导出的 JSON 承载，链上只提供不可篡改的时间戳与版本证明。
 *
 * @dev 设计取舍：
 *  - 与认证存证分开部署：CBAM 申报与证书认证是两条独立业务线，锚点合约升级/重部署
 *    不应影响 CertRegistryData 中的历史证书；
 *  - 同一申报包可重复锚定（退回修改后重新提交），链上保留最新锚点，历史以事件追溯；
 *  - 写入受 writer 白名单限制，owner 由部署账户担任，可增删 writer。
 */
contract CbamAnchorRegistry {

    /**
     * @notice 单条锚点。
     * @dev snapshotHash 为申报包 JSON 的 Keccak-256；dataVersion 为生成快照时的数据版本。
     */
    struct Anchor {
        string  declarationNo;
        uint256 reportYear;
        uint256 dataVersion;
        bytes32 snapshotHash;
        uint64  anchoredAt;
        address anchoredBy;
        bool    exists;
    }

    /// @notice 合约管理员，可增删 writer
    address public owner;
    /// @notice 写入白名单：owner 与 writer 可写锚点，其余地址只能读
    mapping(address => bool) public writers;

    /// @dev keccak256(bytes(declarationNo)) -> 最新锚点
    mapping(bytes32 => Anchor) private _anchors;

    /// @notice 锚点写入/覆盖时触发，历史版本按事件追溯
    event AnchorWritten(bytes32 indexed id, string declarationNo, uint256 reportYear,
                        uint256 dataVersion, bytes32 snapshotHash, uint64 anchoredAt,
                        address anchoredBy);
    /// @notice writer 白名单变更；allowed=false 表示移除
    event WriterChanged(address indexed account, bool allowed);

    /// @notice 仅管理员可调用，否则 revert("NOT_OWNER")
    modifier onlyOwner() {
        require(msg.sender == owner, "NOT_OWNER");
        _;
    }

    /// @notice 仅 owner 或白名单 writer 可调用，否则 revert("NOT_WRITER")
    modifier onlyWriter() {
        require(msg.sender == owner || writers[msg.sender], "NOT_WRITER");
        _;
    }

    /**
     * @notice 部署锚点合约：部署账户成为 owner 与首个 writer。
     * @param initialWriters 初始写入方地址（后端签名账户等），零地址会被跳过
     */
    constructor(address[] memory initialWriters) {
        owner = msg.sender;
        writers[msg.sender] = true;
        for (uint256 i = 0; i < initialWriters.length; i++) {
            if (initialWriters[i] != address(0)) {
                writers[initialWriters[i]] = true;
            }
        }
    }

    /**
     * @notice 写入或覆盖某申报包的锚点。
     * @dev 权限：onlyWriter。允许覆盖是有意设计：退回修改后重新提交会产生新的快照哈希，
     *      最新锚点指向最后提交的版本，历史版本仍可通过 AnchorWritten 事件追溯。
     * @param declarationNo 申报包编号（如 CBAM-2026-C001）
     * @param reportYear    申报年度
     * @param dataVersion   生成快照时的数据版本
     * @param snapshotHash  申报包 JSON 的 Keccak-256
     */
    function anchor(string calldata declarationNo, uint256 reportYear, uint256 dataVersion,
                    bytes32 snapshotHash) external onlyWriter {
        require(bytes(declarationNo).length > 0, "DECLARATION_EMPTY");
        require(snapshotHash != bytes32(0), "HASH_EMPTY");

        bytes32 id = keccak256(bytes(declarationNo));
        _anchors[id] = Anchor(declarationNo, reportYear, dataVersion, snapshotHash,
                uint64(block.timestamp), msg.sender, true);
        emit AnchorWritten(id, declarationNo, reportYear, dataVersion, snapshotHash,
                uint64(block.timestamp), msg.sender);
    }

    /**
     * @notice 查询某申报包的最新锚点。
     * @param declarationNo 申报包编号
     * @return exists 是否已锚定
     * @return reportYear 申报年度
     * @return dataVersion 快照数据版本
     * @return snapshotHash 快照哈希
     * @return anchoredAt 锚定时间（链上时间戳，秒）
     * @return anchoredBy 锚定账户
     * @return declarationNoOut 申报包编号回显
     */
    function anchorOf(string calldata declarationNo) external view returns (
        bool exists, uint256 reportYear, uint256 dataVersion, bytes32 snapshotHash,
        uint64 anchoredAt, address anchoredBy, string memory declarationNoOut
    ) {
        Anchor memory a = _anchors[keccak256(bytes(declarationNo))];
        return (a.exists, a.reportYear, a.dataVersion, a.snapshotHash,
                a.anchoredAt, a.anchoredBy, a.declarationNo);
    }

    /// @notice 判断某申报包是否已锚定
    function existsOf(string calldata declarationNo) external view returns (bool) {
        return _anchors[keccak256(bytes(declarationNo))].exists;
    }

    /**
     * @notice 新增写入白名单账户（仅 owner）。
     * @param account 目标账户地址，不可为 address(0)
     */
    function addWriter(address account) external onlyOwner {
        require(account != address(0), "ZERO_ADDRESS");
        writers[account] = true;
        emit WriterChanged(account, true);
    }

    /**
     * @notice 移除写入白名单账户（仅 owner，幂等）。
     * @param account 目标账户地址
     */
    function removeWriter(address account) external onlyOwner {
        writers[account] = false;
        emit WriterChanged(account, false);
    }
}
