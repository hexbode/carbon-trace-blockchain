// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;
pragma experimental ABIEncoderV2;

import "./CertRegistryData.sol";

/**
 * @title 碳链通 · 认证存证逻辑合约 V1
 * @notice 承载认证存证的全部业务规则：认证写入、监管撤销、业务作废、三种索引查询与写入者治理；
 *         所有状态都通过 CertRegistryData 读写，本合约只保存数据合约地址。
 *
 * @dev 对外函数签名、返回值顺序与事件定义必须保持稳定——后端 WeBASE 网关按 ABI 编解码，
 *      链上历史交易也按同一选择器解析。新增能力应发布新逻辑版本（如 V2），不得改动既有签名。
 *
 * 语义：只追加、不删除。
 *  - 删除排放记录时调用 invalidateByEmissionId，仅把链上状态标记为 INVALID；
 *  - 监管撤销调用 revoke，状态为 REVOKED 且为终局，不允许再次 certify 复活。
 *
 * 金额口径：amountScaled 为排放量 ×100（2 位小数），与 Java 侧 AmountCodec.toScaled 一致。
 * 电子防伪码 fingerprint 大小写敏感，索引键为 keccak256(原始字节)。
 */
contract CertRegistryLogicV1 {

    /// @notice 证书状态取值：0=未登记，1=有效，2=监管撤销（终局），3=业务记录已删除而作废
    uint8 public constant STATUS_NONE    = 0;
    uint8 public constant STATUS_VALID   = 1;
    uint8 public constant STATUS_REVOKED = 2;
    uint8 public constant STATUS_INVALID = 3;

    /// @notice 数据合约引用；逻辑合约无业务状态，升级时由新逻辑重新绑定同一数据合约
    CertRegistryData internal data;

    /**
     * @notice 认证写入时触发；首次登记与同编号更新都会发出。
     * @dev 更新场景下 createdBlock / createdAt 仍为首次登记值。
     */
    event CertCreated(bytes32 indexed certIdHash, string certId, string enterpriseId,
                      string fingerprint, uint256 amountScaled, uint16 complianceFlags,
                      uint64 createdBlock, uint64 createdAt);
    /// @notice 监管撤销成功时触发（REVOKED 状态下重复撤销也会触发）
    event CertRevoked(bytes32 indexed certIdHash, string certId, string reason, uint64 createdAt);
    /// @notice 业务删除排放记录导致的作废登记成功时触发（重复作废也会触发）
    event CertInvalidated(bytes32 indexed certIdHash, string certId, string reason, uint64 createdAt);
    /// @notice writer 白名单变更；allowed=false 表示移除
    event WriterChanged(address indexed account, bool allowed);
    /// @notice 管理员移交成功时触发
    event OwnerChanged(address indexed previousOwner, address indexed newOwner);

    /// @notice 仅数据合约管理员可调用，否则 revert("NOT_OWNER")
    modifier onlyOwner() {
        require(msg.sender == data.owner(), "NOT_OWNER");
        _;
    }

    /// @notice 仅白名单 writer 或 owner 可调用，否则 revert("NOT_WRITER")
    modifier onlyWriter() {
        require(msg.sender == data.owner() || data.writers(msg.sender), "NOT_WRITER");
        _;
    }

    /**
     * @notice 部署逻辑合约并绑定数据合约。
     * @dev 部署完成后还必须由数据合约 owner 调用 data.updateLogic(本合约地址) 才会生效；
     *      在此之前本合约调用数据合约的写入函数会因 NOT_LOGIC 失败。
     * @param dataAddress 已部署的 CertRegistryData 地址
     */
    constructor(address dataAddress) {
        require(dataAddress != address(0), "ZERO_ADDRESS");
        data = CertRegistryData(dataAddress);
    }

    /**
     * @notice 当前逻辑版本号，用于升级核验与运维排查。
     * @dev V1 返回 1；后续版本继承本合约并重写为更高版本号。
     */
    function logicVersion() external pure virtual returns (uint256) {
        return 1;
    }

    // ---------------- 权限管理 ----------------

    /**
     * @notice 新增写入白名单账户。
     * @dev 权限：onlyOwner（owner 从数据合约读取）。
     */
    function addWriter(address account) external onlyOwner {
        require(account != address(0), "ZERO_ADDRESS");
        data.setWriter(account, true);
        emit WriterChanged(account, true);
    }

    /**
     * @notice 移除写入白名单账户。
     * @dev 权限：onlyOwner。不校验该地址是否在名单中（幂等）。
     */
    function removeWriter(address account) external onlyOwner {
        data.setWriter(account, false);
        emit WriterChanged(account, false);
    }

    /**
     * @notice 移交数据合约管理权，并把新 owner 加入 writer 白名单。
     * @dev 权限：onlyOwner。旧 owner 的 writers 标记不会自动清除。
     */
    function transferOwner(address newOwner) external onlyOwner {
        require(newOwner != address(0), "ZERO_ADDRESS");
        address previous = data.owner();
        data.transferOwner(newOwner);
        emit OwnerChanged(previous, newOwner);
    }

    // ---------------- 写入 ----------------

    /**
     * @notice 认证上链：首次登记，或对未撤销的同一编号更新信息。
     * @dev 权限：onlyWriter。规则：
     *  - certId 与 fingerprint 均不可为空；
     *  - 防伪码全局唯一：未被占用，或已被同一 certId 占用，否则 revert("FINGERPRINT_DUPLICATE")；
     *  - 首次登记：写入 createdBlock/createdAt，total++/valid++，并把 certId 追加到区块索引；
     *  - 已存在且为 INVALID：恢复为 VALID（valid++、invalid--），createdBlock/createdAt 不变；
     *  - 已存在且为 VALID：仅覆盖内容字段，计数不变；
     *  - 已存在且为 REVOKED：revert("CERT_REVOKED")，撤销为终局。
     * @return created true=首次登记，false=同编号更新
     */
    function certify(
        string memory certId,
        string memory enterpriseId,
        string memory enterpriseName,
        string memory period,
        uint256 amountScaled,
        string memory fingerprint,
        string memory auditor,
        uint256 complianceFlags
    ) public virtual onlyWriter returns (bool created) {
        require(bytes(certId).length > 0, "CERT_ID_EMPTY");
        require(bytes(fingerprint).length > 0, "FINGERPRINT_EMPTY");

        bytes32 certIdHash = keccak256(bytes(certId));
        bytes32 fpHash = keccak256(bytes(fingerprint));

        // 防伪码必须唯一：未被占用，或已被同一编号占用
        bytes32 bound = data.certIdHashOfFingerprint(fpHash);
        require(bound == bytes32(0) || bound == certIdHash, "FINGERPRINT_DUPLICATE");

        CertRegistryData.Certificate memory c = data.certificateOf(certIdHash);
        if (c.exists) {
            require(c.status != STATUS_REVOKED, "CERT_REVOKED");
            if (c.status == STATUS_INVALID) {
                // 同一编号重新认证：由作废恢复为有效，计数在 valid 与 invalid 之间转移
                (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) = data.counts();
                data.setCertificateStatus(certIdHash, STATUS_VALID, "");
                data.setCounts(total, valid + 1, revoked, invalid - 1);
            }
            // 覆盖内容字段（含防伪码重新绑定与 reason 清空）
            data.writeCertificateContent(certIdHash, enterpriseId, enterpriseName, period,
                    amountScaled, fingerprint, auditor, uint16(complianceFlags), fpHash);
            created = false;
        } else {
            data.writeNewCertificate(certIdHash, certId, enterpriseId, enterpriseName, period,
                    amountScaled, fingerprint, auditor, uint16(complianceFlags), fpHash);
            created = true;
        }

        // 事件的首次登记信息：新记录取当前区块，更新记录保留数据合约中的首次值
        uint64 createdBlock = c.exists ? c.createdBlock : uint64(block.number);
        uint64 createdAt = c.exists ? c.createdAt : uint64(block.timestamp);
        emit CertCreated(certIdHash, certId, enterpriseId, fingerprint,
                         amountScaled, uint16(complianceFlags), createdBlock, createdAt);
    }

    /**
     * @notice 监管撤销：把 VALID 证书置为 REVOKED（终局），并记录原因。
     * @dev 权限：onlyWriter。require 证书已登记，且不处于 INVALID。
     *  - VALID → REVOKED：valid--、revoked++；
     *  - 已是 REVOKED：状态与计数不变，仅覆盖 reason 并重复发事件（幂等）。
     */
    function revoke(string memory certId, string memory reason) external onlyWriter returns (bool) {
        bytes32 certIdHash = keccak256(bytes(certId));
        CertRegistryData.Certificate memory c = data.certificateOf(certIdHash);
        require(c.exists, "CERT_NOT_FOUND");
        require(c.status != STATUS_INVALID, "CERT_INVALIDATED");
        if (c.status == STATUS_VALID) {
            (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) = data.counts();
            data.setCertificateStatus(certIdHash, STATUS_REVOKED, reason);
            data.setCounts(total, valid - 1, revoked + 1, invalid);
        } else {
            // 已是 REVOKED：仅刷新撤销原因
            data.setCertificateStatus(certIdHash, STATUS_REVOKED, reason);
        }
        emit CertRevoked(certIdHash, certId, reason, uint64(block.timestamp));
        return true;
    }

    /**
     * @notice 业务侧删除排放记录 → 链上作废（数据保留，仅状态变更）。
     * @dev 权限：onlyWriter。未登记返回 false，不 revert。
     *  - VALID → INVALID：valid--、invalid++；
     *  - REVOKED / INVALID：状态不变，仅覆盖 reason。
     */
    function invalidateByEmissionId(string memory emissionId, string memory reason) external onlyWriter returns (bool) {
        bytes32 certIdHash = keccak256(bytes(emissionId)); // 三表同号：emissionId == certId
        CertRegistryData.Certificate memory c = data.certificateOf(certIdHash);
        if (!c.exists) {
            return false;
        }
        if (c.status == STATUS_VALID) {
            (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) = data.counts();
            data.setCertificateStatus(certIdHash, STATUS_INVALID, reason);
            data.setCounts(total, valid - 1, revoked, invalid + 1);
        } else {
            data.setCertificateStatus(certIdHash, c.status, reason);
        }
        emit CertInvalidated(certIdHash, emissionId, reason, uint64(block.timestamp));
        return true;
    }

    // ---------------- 查询 ----------------
    // 返回值顺序是对外契约（跨语言按索引编号映射），修改顺序必须同步修改 Java 侧映射。

    /**
     * @notice 按认证编号查询证书快照。
     * @dev 固定 12 元组，顺序是跨语言硬契约；未登记时 exists=false、status=0，其余字段为空/0。
     *      返回值不含 complianceFlags，位掩码需另调 complianceOf。
     */
    function getByCertId(string memory certId) public view returns (
        bool exists, uint8 status, uint64 createdBlock, uint64 createdAt,
        string memory certIdOut, string memory enterpriseId, string memory enterpriseName,
        string memory period, uint256 amountScaled, string memory fingerprintOut,
        string memory auditor, string memory reason
    ) {
        CertRegistryData.Certificate memory c = data.certificateOf(keccak256(bytes(certId)));
        return (c.exists, c.status, c.createdBlock, c.createdAt, c.certId, c.enterpriseId,
                c.enterpriseName, c.period, c.amountScaled, c.fingerprint, c.auditor, c.reason);
    }

    /**
     * @notice 按排放记录编号查询，等价于按认证编号查询（三表同号）。
     */
    function getByEmissionId(string memory emissionId) external view returns (
        bool exists, uint8 status, uint64 createdBlock, uint64 createdAt,
        string memory certIdOut, string memory enterpriseId, string memory enterpriseName,
        string memory period, uint256 amountScaled, string memory fingerprintOut,
        string memory auditor, string memory reason
    ) {
        return getByCertId(emissionId);
    }

    /**
     * @notice 按电子防伪码反查证书（大小写敏感）。
     * @dev 未绑定时返回 exists=false 的空元组，不 revert。
     */
    function getByFingerprint(string memory fingerprint) external view returns (
        bool exists, uint8 status, uint64 createdBlock, uint64 createdAt,
        string memory certIdOut, string memory enterpriseId, string memory enterpriseName,
        string memory period, uint256 amountScaled, string memory fingerprintOut,
        string memory auditor, string memory reason
    ) {
        bytes32 certIdHash = data.certIdHashOfFingerprint(keccak256(bytes(fingerprint)));
        if (certIdHash == bytes32(0)) {
            return (false, STATUS_NONE, 0, 0, "", "", "", "", 0, "", "", "");
        }
        CertRegistryData.Certificate memory c = data.certificateOf(certIdHash);
        return (c.exists, c.status, c.createdBlock, c.createdAt, c.certId, c.enterpriseId,
                c.enterpriseName, c.period, c.amountScaled, c.fingerprint, c.auditor, c.reason);
    }

    /// @notice 判断某编号是否已上链存证（已撤销/已作废仍返回 true）
    function existsOf(string memory certId) external view returns (bool) {
        return data.certificateOf(keccak256(bytes(certId))).exists;
    }

    /// @notice 查询某编号的当前状态（0/1/2/3）
    function statusOf(string memory certId) external view returns (uint8) {
        return data.certificateOf(keccak256(bytes(certId))).status;
    }

    /// @notice 查询认证时记录的合规判定位掩码；未登记返回 0
    function complianceOf(string memory certId) external view returns (uint16) {
        return data.certificateOf(keccak256(bytes(certId))).complianceFlags;
    }

    /// @notice 四项计数快照，恒有 total == valid + revoked + invalid
    function count() external view returns (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) {
        return data.counts();
    }

    /// @notice 返回某区块内「首次登记」的认证编号列表
    function certIdsInBlock(uint256 blockNumber) external view returns (string[] memory) {
        return data.certIdsInBlock(blockNumber);
    }
}
