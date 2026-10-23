// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;
pragma experimental ABIEncoderV2;

/**
 * 碳链通 · 认证存证合约
 *
 * 语义：只追加、不删除。
 *  - 删除排放记录时调用 invalidateByEmissionId，仅把链上状态标记为 INVALID（历史留痕不可抹除）；
 *  - 监管撤销调用 revoke，状态为 REVOKED 且为终局状态，不允许再次 certify 复活。
 *
 * 约定：认证编号 certId 与排放记录编号 emissionId 三表同号（CR + 6 位序号），链上以 certId 为唯一键。
 * 电子防伪码 fingerprint 大小写敏感，索引键为 keccak256(原始字节)，天然区分大小写。
 *
 * 适用：FISCO BCOS 2.6.0+（含 2.9.x）与 FISCO BCOS 3.x（二者均支持 solidity 0.6.10）。
 */
contract CarbonCertRegistry {

    uint8 public constant STATUS_NONE    = 0; // 不存在
    uint8 public constant STATUS_VALID   = 1; // 有效
    uint8 public constant STATUS_REVOKED = 2; // 监管撤销（终局）
    uint8 public constant STATUS_INVALID = 3; // 业务记录被删除而作废

    struct Certificate {
        string  certId;
        string  enterpriseId;
        string  enterpriseName;
        string  period;           // YYYY-MM
        uint256 amountScaled;     // 排放量 × 100（定点，2 位小数，与前端金额口径一致）
        string  fingerprint;      // 12 位电子防伪码（大小写敏感）
        string  auditor;          // 审核人
        string  reason;           // 撤销/作废原因
        uint16  complianceFlags;  // 上链时的合规判定结果位掩码（来自合规合约）
        uint64  createdBlock;     // 首次登记所在区块高度
        uint64  createdAt;        // 首次登记时间戳（秒）
        uint8   status;
        bool    exists;
    }

    address public owner;
    mapping(address => bool) public writers;

    uint256 public totalCount;    // 历史累计认证数
    uint256 public validCount;    // 当前有效
    uint256 public revokedCount;  // 已撤销
    uint256 public invalidCount;  // 已作废

    mapping(bytes32 => Certificate) private _certs;               // keccak(certId) -> 证书
    mapping(bytes32 => bytes32)     private _certIdByFingerprint; // keccak(fingerprint) -> keccak(certId)
    mapping(uint256 => string[])    private _certIdsInBlock;      // 区块高度 -> 本区块新增的认证编号

    event CertCreated(bytes32 indexed certIdHash, string certId, string enterpriseId,
                      string fingerprint, uint256 amountScaled, uint16 complianceFlags,
                      uint64 createdBlock, uint64 createdAt);
    event CertRevoked(bytes32 indexed certIdHash, string certId, string reason, uint64 createdAt);
    event CertInvalidated(bytes32 indexed certIdHash, string certId, string reason, uint64 createdAt);
    event WriterChanged(address indexed account, bool allowed);
    event OwnerChanged(address indexed previousOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "NOT_OWNER");
        _;
    }

    modifier onlyWriter() {
        require(writers[msg.sender] || msg.sender == owner, "NOT_WRITER");
        _;
    }

    /**
     * @param initialWriters 初始写入方地址（业务后端账户、部署账户等）
     */
    constructor(address[] memory initialWriters) {
        owner = msg.sender;
        writers[msg.sender] = true;
        emit WriterChanged(msg.sender, true);
        for (uint256 i = 0; i < initialWriters.length; i++) {
            if (initialWriters[i] != address(0)) {
                writers[initialWriters[i]] = true;
                emit WriterChanged(initialWriters[i], true);
            }
        }
    }

    // ---------------- 权限管理 ----------------

    function addWriter(address account) external onlyOwner {
        require(account != address(0), "ZERO_ADDRESS");
        writers[account] = true;
        emit WriterChanged(account, true);
    }

    function removeWriter(address account) external onlyOwner {
        writers[account] = false;
        emit WriterChanged(account, false);
    }

    function transferOwner(address newOwner) external onlyOwner {
        require(newOwner != address(0), "ZERO_ADDRESS");
        address previous = owner;
        owner = newOwner;
        writers[newOwner] = true;
        emit OwnerChanged(previous, newOwner);
    }

    // ---------------- 写入 ----------------

    /**
     * 认证上链：首次登记，或对未撤销的同一编号更新信息。
     * 已被撤销（REVOKED）的证书不允许复活，保证撤销的终局性。
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
    ) public onlyWriter returns (bool created) {
        require(bytes(certId).length > 0, "CERT_ID_EMPTY");
        require(bytes(fingerprint).length > 0, "FINGERPRINT_EMPTY");

        bytes32 certIdHash = keccak256(bytes(certId));
        bytes32 fpHash = keccak256(bytes(fingerprint));

        // 防伪码必须唯一：未被占用，或已被同一编号占用
        bytes32 bound = _certIdByFingerprint[fpHash];
        require(bound == bytes32(0) || bound == certIdHash, "FINGERPRINT_DUPLICATE");

        Certificate storage c = _certs[certIdHash];
        if (c.exists) {
            require(c.status != STATUS_REVOKED, "CERT_REVOKED");
            if (c.status == STATUS_INVALID) {
                // 同一编号重新认证：由作废恢复为有效
                c.status = STATUS_VALID;
                validCount++;
                invalidCount--;
            }
            created = false;
        } else {
            c.certId = certId;
            c.createdBlock = uint64(block.number);
            c.createdAt = uint64(block.timestamp);
            c.exists = true;
            c.status = STATUS_VALID;
            totalCount++;
            validCount++;
            _certIdsInBlock[block.number].push(certId);
            created = true;
        }

        c.enterpriseId = enterpriseId;
        c.enterpriseName = enterpriseName;
        c.period = period;
        c.amountScaled = amountScaled;
        c.fingerprint = fingerprint;
        c.auditor = auditor;
        c.complianceFlags = uint16(complianceFlags);
        c.reason = "";

        _certIdByFingerprint[fpHash] = certIdHash;

        emit CertCreated(certIdHash, certId, enterpriseId, fingerprint,
                         amountScaled, uint16(complianceFlags), c.createdBlock, c.createdAt);
    }

    /** 监管撤销：终局状态，不再计入有效数 */
    function revoke(string memory certId, string memory reason) external onlyWriter returns (bool) {
        Certificate storage c = _certs[keccak256(bytes(certId))];
        require(c.exists, "CERT_NOT_FOUND");
        require(c.status != STATUS_INVALID, "CERT_INVALIDATED");
        if (c.status == STATUS_VALID) {
            c.status = STATUS_REVOKED;
            validCount--;
            revokedCount++;
        }
        c.reason = reason;
        emit CertRevoked(keccak256(bytes(certId)), certId, reason, uint64(block.timestamp));
        return true;
    }

    /**
     * 业务侧删除排放记录 → 链上作废（数据保留，仅状态变更）。
     * 已撤销的证书保持终局状态，仅追加备注。
     * 未认证过的排放记录（链上无存证）直接返回 false，不 revert——删除未认证记录是合法业务操作。
     */
    function invalidateByEmissionId(string memory emissionId, string memory reason) external onlyWriter returns (bool) {
        bytes32 certIdHash = keccak256(bytes(emissionId)); // 三表同号：emissionId == certId
        Certificate storage c = _certs[certIdHash];
        if (!c.exists) {
            return false;
        }
        if (c.status == STATUS_VALID) {
            c.status = STATUS_INVALID;
            validCount--;
            invalidCount++;
        }
        c.reason = reason;
        emit CertInvalidated(certIdHash, emissionId, reason, uint64(block.timestamp));
        return true;
    }

    // ---------------- 查询 ----------------
    // 返回值顺序是对外契约（跨语言按索引编号映射），修改顺序必须同步修改 Java 侧映射。

    function getByCertId(string memory certId) public view returns (
        bool exists, uint8 status, uint64 createdBlock, uint64 createdAt,
        string memory certIdOut, string memory enterpriseId, string memory enterpriseName,
        string memory period, uint256 amountScaled, string memory fingerprintOut,
        string memory auditor, string memory reason
    ) {
        Certificate storage c = _certs[keccak256(bytes(certId))];
        return (c.exists, c.status, c.createdBlock, c.createdAt, c.certId, c.enterpriseId,
                c.enterpriseName, c.period, c.amountScaled, c.fingerprint, c.auditor, c.reason);
    }

    /** 三表同号，按排放编号查询与按认证编号查询等价 */
    function getByEmissionId(string memory emissionId) external view returns (
        bool exists, uint8 status, uint64 createdBlock, uint64 createdAt,
        string memory certIdOut, string memory enterpriseId, string memory enterpriseName,
        string memory period, uint256 amountScaled, string memory fingerprintOut,
        string memory auditor, string memory reason
    ) {
        return getByCertId(emissionId);
    }

    /** 按电子防伪码查询（大小写敏感：keccak 保留原始字节） */
    function getByFingerprint(string memory fingerprint) external view returns (
        bool exists, uint8 status, uint64 createdBlock, uint64 createdAt,
        string memory certIdOut, string memory enterpriseId, string memory enterpriseName,
        string memory period, uint256 amountScaled, string memory fingerprintOut,
        string memory auditor, string memory reason
    ) {
        bytes32 certIdHash = _certIdByFingerprint[keccak256(bytes(fingerprint))];
        if (certIdHash == bytes32(0)) {
            return (false, STATUS_NONE, 0, 0, "", "", "", "", 0, "", "", "");
        }
        Certificate storage c = _certs[certIdHash];
        return (c.exists, c.status, c.createdBlock, c.createdAt, c.certId, c.enterpriseId,
                c.enterpriseName, c.period, c.amountScaled, c.fingerprint, c.auditor, c.reason);
    }

    function existsOf(string memory certId) external view returns (bool) {
        return _certs[keccak256(bytes(certId))].exists;
    }

    function statusOf(string memory certId) external view returns (uint8) {
        return _certs[keccak256(bytes(certId))].status;
    }

    function complianceOf(string memory certId) external view returns (uint16) {
        return _certs[keccak256(bytes(certId))].complianceFlags;
    }

    /** 计数：供一致性核验与统计使用 */
    function count() external view returns (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) {
        return (totalCount, validCount, revokedCount, invalidCount);
    }

    /** 某区块新增的认证编号（区块浏览用，免装事件解码） */
    function certIdsInBlock(uint256 blockNumber) external view returns (string[] memory) {
        string[] storage ids = _certIdsInBlock[blockNumber];
        string[] memory out = new string[](ids.length);
        for (uint256 i = 0; i < ids.length; i++) {
            out[i] = ids[i];
        }
        return out;
    }
}
