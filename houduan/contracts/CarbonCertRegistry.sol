// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;
pragma experimental ABIEncoderV2;

/**
 * @title 碳链通 · 认证存证合约
 * @notice 企业碳排放数据的认证存证、监管撤销与业务作废登记，并提供 certId / emissionId / fingerprint 三种索引查询。
 * @dev 语义：只追加、不删除。
 *  - 删除排放记录时调用 invalidateByEmissionId，仅把链上状态标记为 INVALID（历史留痕不可抹除）；
 *  - 监管撤销调用 revoke，状态为 REVOKED 且为终局状态，不允许再次 certify 复活。
 *
 * 约定：认证编号 certId 与排放记录编号 emissionId 三表同号（CR + 6 位序号），链上以 certId 为唯一键。
 * 电子防伪码 fingerprint 大小写敏感，索引键为 keccak256(原始字节)，天然区分大小写。
 *
 * 金额口径：amountScaled 为排放量 ×100（2 位小数），与 Java 侧 AmountCodec.toScaled 一致；
 * 链上只存整数，不做浮点运算。
 *
 * 适用：FISCO BCOS 2.6.0+（含 2.9.x）与 FISCO BCOS 3.x（二者均支持 solidity 0.6.10）。
 * 注意：本合约 pragma 要求 >=0.8.11，部署所用编译器版本须落在该区间内。
 */
contract CarbonCertRegistry {

    /// @notice 证书状态取值：0=未登记，1=有效，2=监管撤销（终局），3=业务记录已删除而作废
    /// @dev 状态迁移：未登记 --certify--> VALID；VALID --invalidateByEmissionId--> INVALID；
    ///      INVALID --certify--> VALID（同一编号重新认证）；VALID --revoke--> REVOKED；
    ///      REVOKED 为终局：certify 会 revert("CERT_REVOKED")，重复 revoke 仅覆盖 reason。
    uint8 public constant STATUS_NONE    = 0; // 不存在
    uint8 public constant STATUS_VALID   = 1; // 有效
    uint8 public constant STATUS_REVOKED = 2; // 监管撤销（终局）
    uint8 public constant STATUS_INVALID = 3; // 业务记录被删除而作废

    /**
     * @notice 单条认证存证记录。
     * @dev certId 为链上唯一键；createdBlock/createdAt 仅在首次登记时写入，
     *      同一编号再次 certify 时保持不变，可作为「首次上链」的溯源依据。
     *      reason 在 certify 时被清空，仅在 revoke / invalidateByEmissionId 时写入。
     *      字段标度：amountScaled ×100（2 位小数）；complianceFlags 为合规合约返回的位掩码低 16 位。
     */
    struct Certificate {
        /// @notice 认证编号（CR + 6 位序号），与 emissionId 同号
        string  certId;
        /// @notice 企业编号
        string  enterpriseId;
        /// @notice 企业名称（上链时快照，再次 certify 同编号时会被覆盖）
        string  enterpriseName;
        string  period;           // YYYY-MM
        uint256 amountScaled;     // 排放量 × 100（定点，2 位小数，与前端金额口径一致）
        string  fingerprint;      // 12 位电子防伪码（大小写敏感）
        string  auditor;          // 审核人
        string  reason;           // 撤销/作废原因
        uint16  complianceFlags;  // 上链时的合规判定结果位掩码（来自合规合约）
        uint64  createdBlock;     // 首次登记所在区块高度
        uint64  createdAt;        // 首次登记时间戳（秒）
        /// @notice 当前状态，取值见 STATUS_NONE / STATUS_VALID / STATUS_REVOKED / STATUS_INVALID
        uint8   status;
        /// @notice 是否已登记；未登记时 status 同为 STATUS_NONE，读取时须同时判断 exists
        bool    exists;
    }

    /// @notice 合约管理员，可增删 writer、移交管理权；部署时为部署账户
    address public owner;
    /// @notice 写入白名单；owner 与 writer 可调用 certify / revoke / invalidateByEmissionId
    mapping(address => bool) public writers;

    /// @notice 历史累计认证数（只增不减，含事后被撤销/作废的记录）
    uint256 public totalCount;    // 历史累计认证数
    /// @notice 当前状态为 VALID 的数量
    uint256 public validCount;    // 当前有效
    /// @notice 当前状态为 REVOKED 的数量
    uint256 public revokedCount;  // 已撤销
    /// @notice 当前状态为 INVALID 的数量；恒有 totalCount == validCount + revokedCount + invalidCount
    uint256 public invalidCount;  // 已作废

    /**
     * @dev 三种索引互相独立：
     *  - _certs：keccak256(certId) → 证书本体。certId 为唯一键，emissionId 与之同号，故共用同一张表；
     *  - _certIdByFingerprint：keccak256(fingerprint) → keccak256(certId)，用于防伪码唯一性校验与反查；
     *  - _certIdsInBlock：区块高度 → 该区块内「首次登记」的 certId 列表，便于按块浏览，不随状态变更调整。
     * 所有键均由原始字节直接 keccak256 得到，不做大小写或去空白归一化，因此字符串必须逐字节一致。
     */
    mapping(bytes32 => Certificate) private _certs;               // keccak(certId) -> 证书
    mapping(bytes32 => bytes32)     private _certIdByFingerprint; // keccak(fingerprint) -> keccak(certId)
    mapping(uint256 => string[])    private _certIdsInBlock;      // 区块高度 -> 本区块新增的认证编号

    /**
     * @notice 认证写入时触发；首次登记与同编号更新都会发出，仅凭事件无法区分二者。
     * @dev 更新场景下 createdBlock / createdAt 仍为首次登记值，amountScaled 等为本次写入值。
     * @param certIdHash keccak256(bytes(certId))，供按哈希建索引
     * @param certId 认证编号
     * @param enterpriseId 企业编号
     * @param fingerprint 电子防伪码原文（大小写敏感）
     * @param amountScaled 排放量 ×100
     * @param complianceFlags 合规判定位掩码（传入值截取低 16 位）
     * @param createdBlock 首次登记区块高度
     * @param createdAt 首次登记时间戳（秒）
     */
    event CertCreated(bytes32 indexed certIdHash, string certId, string enterpriseId,
                      string fingerprint, uint256 amountScaled, uint16 complianceFlags,
                      uint64 createdBlock, uint64 createdAt);
    /**
     * @notice 监管撤销成功时触发（REVOKED 状态下重复撤销也会触发）。
     * @dev 第四个参数名为 createdAt，实际携带的是本次撤销的 block.timestamp。
     * @param certIdHash keccak256(bytes(certId))
     * @param certId 认证编号
     * @param reason 撤销原因
     * @param createdAt 本次撤销的时间戳（秒）
     */
    event CertRevoked(bytes32 indexed certIdHash, string certId, string reason, uint64 createdAt);
    /**
     * @notice 业务删除排放记录导致的作废登记成功时触发（重复作废也会触发）。
     * @dev 第四个参数名为 createdAt，实际携带的是本次作废的 block.timestamp。
     * @param certIdHash keccak256(bytes(emissionId))
     * @param certId 认证编号（此处即 emissionId）
     * @param reason 作废原因
     * @param createdAt 本次作废的时间戳（秒）
     */
    event CertInvalidated(bytes32 indexed certIdHash, string certId, string reason, uint64 createdAt);
    /// @notice writer 白名单变更；allowed=false 表示移除
    /// @param account 被变更的账户地址
    /// @param allowed 变更后的白名单状态
    event WriterChanged(address indexed account, bool allowed);
    /// @notice 管理员移交成功时触发
    /// @param previousOwner 原管理员地址
    /// @param newOwner 新管理员地址
    event OwnerChanged(address indexed previousOwner, address indexed newOwner);

    /// @notice 仅合约管理员可调用，否则 revert("NOT_OWNER")
    modifier onlyOwner() {
        require(msg.sender == owner, "NOT_OWNER");
        _;
    }

    /// @notice 仅白名单 writer 或 owner 可调用，否则 revert("NOT_WRITER")；owner 天然具备写权限
    modifier onlyWriter() {
        require(writers[msg.sender] || msg.sender == owner, "NOT_WRITER");
        _;
    }

    /**
     * @notice 部署时把部署账户设为 owner 与首个 writer，并注册初始 writer 列表。
     * @dev address(0) 会被跳过；若列表包含部署账户或重复地址，会幂等地置为 true 并重复发事件。
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

    /**
     * @notice 新增写入白名单账户。
     * @dev 权限：onlyOwner。对已是 writer 的地址重复调用不会 revert，状态不变但仍发事件；零地址被拒绝。
     * @param account 待授权的账户地址，不可为 address(0)
     */
    function addWriter(address account) external onlyOwner {
        require(account != address(0), "ZERO_ADDRESS");
        writers[account] = true;
        emit WriterChanged(account, true);
    }

    /**
     * @notice 移除写入白名单账户。
     * @dev 权限：onlyOwner。不校验该地址是否在名单中（幂等）；不会撤销 owner 的写权限，
     *      因为 onlyWriter 对 owner 始终放行。
     * @param account 待移除的账户地址
     */
    function removeWriter(address account) external onlyOwner {
        writers[account] = false;
        emit WriterChanged(account, false);
    }

    /**
     * @notice 移交合约管理权，并把新 owner 加入 writer 白名单。
     * @dev 权限：onlyOwner。旧 owner 的 writers 标记不会自动清除，如需收回写权限须另行 removeWriter。
     * @param newOwner 新管理员地址，不可为 address(0)
     */
    function transferOwner(address newOwner) external onlyOwner {
        require(newOwner != address(0), "ZERO_ADDRESS");
        address previous = owner;
        owner = newOwner;
        writers[newOwner] = true;
        emit OwnerChanged(previous, newOwner);
    }

    // ---------------- 写入 ----------------

    /**
     * @notice 认证上链：首次登记，或对未撤销的同一编号更新信息。
     * @dev 权限：onlyWriter。关键约束与状态变更：
     *  - certId 与 fingerprint 均不可为空；
     *  - 防伪码全局唯一：未被占用，或已被同一 certId 占用，否则 revert("FINGERPRINT_DUPLICATE")；
     *  - 首次登记：exists=true、status=VALID，写入 createdBlock/createdAt，totalCount++、validCount++，
     *    并把 certId 追加到 _certIdsInBlock[当前区块]；
     *  - 已存在且为 INVALID：恢复为 VALID（validCount++、invalidCount--），createdBlock/createdAt 保持不变；
     *  - 已存在且为 VALID：仅覆盖内容字段，四个计数均不变；
     *  - 已存在且为 REVOKED：revert("CERT_REVOKED")，撤销为终局，不允许复活；
     *  - 新建与更新都会覆盖内容字段、清空 reason、刷新 fingerprint 索引；
     *    但更换防伪码时不会解绑旧码，旧码仍指向本编号（只追加，保留历史可查）。
     * @param certId 认证编号（CR + 6 位序号），同时作为 emissionId 的键
     * @param enterpriseId 企业编号
     * @param enterpriseName 企业名称
     * @param period 报告期 YYYY-MM
     * @param amountScaled 排放量 ×100（2 位小数）
     * @param fingerprint 12 位电子防伪码，大小写敏感
     * @param auditor 审核人
     * @param complianceFlags 合规判定位掩码，落库时截取低 16 位
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

    /**
     * @notice 监管撤销：把 VALID 证书置为 REVOKED（终局），并记录原因。
     * @dev 权限：onlyWriter。require 证书已登记，且不处于 INVALID（业务作废后不可再撤销）。
     *      - VALID → REVOKED：validCount--、revokedCount++；
     *      - 已是 REVOKED：状态与计数不变，仅覆盖 reason 并重复发事件（幂等）；
     *      - 撤销后该编号不可再 certify（终局性）。
     * @param certId 认证编号
     * @param reason 撤销原因（原文上链）
     * @return 恒为 true；失败场景以 revert 表达
     */
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
     * @notice 业务侧删除排放记录 → 链上作废（数据保留，仅状态变更）。
     * @dev 权限：onlyWriter。emissionId 与 certId 同号，故直接以 emissionId 的 keccak 命中 _certs。
     *      - 未登记：返回 false，不 revert（删除未认证记录属于合法业务操作）；
     *      - VALID → INVALID：validCount--、invalidCount++；
     *      - REVOKED：保持终局状态不变，仅覆盖 reason（不会把撤销降级为作废）；
     *      - INVALID：状态不变，仅覆盖 reason。
     * @param emissionId 排放记录编号（等同 certId）
     * @param reason 作废原因
     * @return true=找到存证并已记录作废；false=该编号从未认证
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

    /**
     * @notice 按认证编号查询证书快照。
     * @dev 返回固定 12 元组，顺序是跨语言硬契约（Java 侧按索引解析），调整顺序须同步后端映射。
     *      未登记时 exists=false、status=STATUS_NONE，其余字段为空/0，不 revert。
     *      注意：返回值不含 complianceFlags，位掩码需另调 complianceOf。
     * @param certId 认证编号，大小写敏感
     * @return exists 是否已登记
     * @return status 状态值（0=未登记 / 1=有效 / 2=撤销 / 3=作废）
     * @return createdBlock 首次登记区块高度
     * @return createdAt 首次登记时间戳（秒）
     * @return certIdOut 认证编号
     * @return enterpriseId 企业编号
     * @return enterpriseName 企业名称
     * @return period 报告期 YYYY-MM
     * @return amountScaled 排放量 ×100
     * @return fingerprintOut 电子防伪码
     * @return auditor 审核人
     * @return reason 撤销/作废原因
     */
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

    /**
     * @notice 按排放记录编号查询，等价于按认证编号查询（三表同号）。
     * @dev 直接委托 getByCertId，不额外做编号校验或前缀判断；emissionId 大小写敏感。
     * @param emissionId 排放记录编号
     * @return exists 是否已登记
     * @return status 状态值（0=未登记 / 1=有效 / 2=撤销 / 3=作废）
     * @return createdBlock 首次登记区块高度
     * @return createdAt 首次登记时间戳（秒）
     * @return certIdOut 认证编号
     * @return enterpriseId 企业编号
     * @return enterpriseName 企业名称
     * @return period 报告期 YYYY-MM
     * @return amountScaled 排放量 ×100
     * @return fingerprintOut 电子防伪码
     * @return auditor 审核人
     * @return reason 撤销/作废原因
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
     * @notice 按电子防伪码反查证书。
     * @dev 防伪码大小写敏感：索引为 keccak256(原始字节)，大小写不同即不同键。
     *      未绑定（从未认证，或哈希为 0）时返回 exists=false 的空元组，不 revert。
     * @param fingerprint 电子防伪码
     * @return exists 是否已登记
     * @return status 状态值（0=未登记 / 1=有效 / 2=撤销 / 3=作废）
     * @return createdBlock 首次登记区块高度
     * @return createdAt 首次登记时间戳（秒）
     * @return certIdOut 认证编号
     * @return enterpriseId 企业编号
     * @return enterpriseName 企业名称
     * @return period 报告期 YYYY-MM
     * @return amountScaled 排放量 ×100
     * @return fingerprintOut 电子防伪码
     * @return auditor 审核人
     * @return reason 撤销/作废原因
     */
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

    /**
     * @notice 判断某编号是否已上链存证。
     * @dev 仅看 exists 标志，不区分 VALID/REVOKED/INVALID：已撤销或已作废仍返回 true。
     * @param certId 认证编号
     * @return 是否已登记
     */
    function existsOf(string memory certId) external view returns (bool) {
        return _certs[keccak256(bytes(certId))].exists;
    }

    /**
     * @notice 查询某编号的当前状态。
     * @dev 未登记时返回 STATUS_NONE(0)；已登记则必为 VALID / REVOKED / INVALID 之一，不会回到 0。
     * @param certId 认证编号
     * @return 状态值（0/1/2/3）
     */
    function statusOf(string memory certId) external view returns (uint8) {
        return _certs[keccak256(bytes(certId))].status;
    }

    /**
     * @notice 查询认证时记录的合规判定位掩码。
     * @dev 为 certify 时写入的快照，不随合规合约规则或治理参数变化而更新；未登记返回 0。
     * @param certId 认证编号
     * @return 16 位位掩码（bit0 配额 / bit1 环比 / bit2 漏填 / bit3 因子 / bit4 迟报）
     */
    function complianceOf(string memory certId) external view returns (uint16) {
        return _certs[keccak256(bytes(certId))].complianceFlags;
    }

    /**
     * @notice 四项计数快照，供一致性核验与统计使用。
     * @dev 恒有 total == valid + revoked + invalid；total 只增不减，撤销/作废只在三个细分计数间转移。
     * @return total 历史累计认证数
     * @return valid 当前有效数
     * @return revoked 已撤销数
     * @return invalid 已作废数
     */
    function count() external view returns (uint256 total, uint256 valid, uint256 revoked, uint256 invalid) {
        return (totalCount, validCount, revokedCount, invalidCount);
    }

    /**
     * @notice 返回某区块内「首次登记」的认证编号列表。
     * @dev 仅首次登记会追加，更新/撤销/作废不会改动该列表；未登记的区块返回空数组。
     *      返回值由存储拷贝到内存，调用方修改不影响链上数据。
     * @param blockNumber 区块高度
     * @return 该区块新增的 certId 数组（按登记顺序）
     */
    function certIdsInBlock(uint256 blockNumber) external view returns (string[] memory) {
        string[] storage ids = _certIdsInBlock[blockNumber];
        string[] memory out = new string[](ids.length);
        for (uint256 i = 0; i < ids.length; i++) {
            out[i] = ids[i];
        }
        return out;
    }
}
