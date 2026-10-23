// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.11 <0.9.0;

/**
 * 碳链通 · 排放合规判定合约
 *
 * 全部告警规则判定在合约内执行，合约只返回「结果位掩码 + 数值」，
 * 告警级别与中文文案仍由后端生成（保持 alerts 表结构与前端契约不变）。
 *
 * 规则与位掩码（flags）：
 *   bit0 年度配额超限      QUOTA_EXCEEDED
 *   bit1 环比波动超阈值    FLUCTUATION_EXCEEDED
 *   bit2 活动数据为 0      MISSING_ACTIVITY_DATA
 *   bit3 排放因子不符      FACTOR_MISMATCH
 *   bit4 迟报（补报）      LATE_REPORT
 *
 * 可治理参数（链上变更留痕）：环比阈值（基点）、迟报月数。
 * 输入边界：本期/上期合计、年累计、报告期与当前月份由后端从业务库聚合后传入——
 * 链上无法自行聚合业务库数据，这是必要的输入边界。
 */
contract CarbonCompliance {

    uint8 public constant OK                    = 0;
    uint8 public constant QUOTA_EXCEEDED        = 1;
    uint8 public constant FLUCTUATION_EXCEEDED  = 2;
    uint8 public constant MISSING_ACTIVITY_DATA = 3;
    uint8 public constant FACTOR_MISMATCH       = 4;
    uint8 public constant LATE_REPORT           = 5;

    uint16 public constant FLAG_QUOTA       = 1;
    uint16 public constant FLAG_FLUCTUATION = 2;
    uint16 public constant FLAG_ACTIVITY    = 4;
    uint16 public constant FLAG_FACTOR      = 8;
    uint16 public constant FLAG_LATE        = 16;

    /** 百分比取整粒度：10bp = 0.1%，与 Java 侧 BigDecimal scale=1 HALF_UP 口径一致 */
    uint256 public constant BP_STEP = 10;

    address public owner;
    mapping(address => bool) public writers;

    /** 环比波动阈值，基点：2000 = ±20.00% */
    uint256 public fluctuationThresholdBp = 2000;

    /** 迟报判定：报告期早于当前月份超过该月数即视为迟报 */
    uint256 public lateReportMonths = 1;

    mapping(bytes32 => uint256) private _annualQuota; // keccak(enterpriseId|year)       -> 配额 × 100
    mapping(bytes32 => uint256) private _yearTotal;   // keccak(enterpriseId|year)       -> 年累计 × 100
    mapping(bytes32 => uint256) private _periodTotal; // keccak(enterpriseId|period)     -> 本期合计 × 100
    mapping(bytes32 => uint256) private _lastFactor;  // keccak(enterpriseId|sourceCode) -> 最近使用因子 × 10000

    event QuotaSet(bytes32 indexed key, string enterpriseId, uint256 year, uint256 quotaScaled);
    event PeriodTotalSet(bytes32 indexed key, string enterpriseId, string period, uint256 amountScaled);
    event YearTotalSet(bytes32 indexed key, string enterpriseId, uint256 year, uint256 amountScaled);
    event FactorRemembered(bytes32 indexed key, string enterpriseId, string sourceCode, uint256 factorScaled);
    event FluctuationThresholdChanged(uint256 oldBp, uint256 newBp);
    event LateReportMonthsChanged(uint256 oldMonths, uint256 newMonths);
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

    constructor() {
        owner = msg.sender;
        writers[msg.sender] = true;
        emit WriterChanged(msg.sender, true);
    }

    // ---------------- 权限 ----------------

    function addWriter(address account) external onlyOwner {
        require(account != address(0), "ZERO_ADDRESS");
        writers[account] = true;
        emit WriterChanged(account, true);
    }

    function removeWriter(address account) external onlyOwner {
        writers[account] = false;
        emit WriterChanged(account, false);
    }

    // ---------------- 可治理参数 ----------------

    function setFluctuationThresholdBp(uint256 bp) external onlyWriter {
        require(bp > 0 && bp <= 10000, "BAD_THRESHOLD");
        uint256 old = fluctuationThresholdBp;
        fluctuationThresholdBp = bp;
        emit FluctuationThresholdChanged(old, bp);
    }

    function setLateReportMonths(uint256 months) external onlyWriter {
        require(months <= 24, "BAD_MONTHS");
        uint256 old = lateReportMonths;
        lateReportMonths = months;
        emit LateReportMonthsChanged(old, months);
    }

    // ---------------- 链上状态写入 ----------------

    function setAnnualQuota(string memory enterpriseId, uint256 year, uint256 quotaScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, _uintToStr(year));
        _annualQuota[key] = quotaScaled;
        emit QuotaSet(key, enterpriseId, year, quotaScaled);
    }

    function annualQuotaOf(string memory enterpriseId, uint256 year) external view returns (uint256) {
        return _annualQuota[_key2(enterpriseId, _uintToStr(year))];
    }

    /** 链上镜像：企业本期排放量合计（后端每次填报后同步，幂等覆盖） */
    function setPeriodTotal(string memory enterpriseId, string memory period, uint256 amountScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, period);
        _periodTotal[key] = amountScaled;
        emit PeriodTotalSet(key, enterpriseId, period, amountScaled);
    }

    function periodTotalOf(string memory enterpriseId, string memory period) external view returns (uint256) {
        return _periodTotal[_key2(enterpriseId, period)];
    }

    /** 链上镜像：企业年度累计排放量（配额判定用） */
    function setYearTotal(string memory enterpriseId, uint256 year, uint256 amountScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, _uintToStr(year));
        _yearTotal[key] = amountScaled;
        emit YearTotalSet(key, enterpriseId, year, amountScaled);
    }

    function yearTotalOf(string memory enterpriseId, uint256 year) external view returns (uint256) {
        return _yearTotal[_key2(enterpriseId, _uintToStr(year))];
    }

    /** 记录最近一次使用的排放因子（供「因子不符」判定读取） */
    function rememberFactor(string memory enterpriseId, string memory sourceCode, uint256 factorScaled) external onlyWriter {
        bytes32 key = _key2(enterpriseId, sourceCode);
        _lastFactor[key] = factorScaled;
        emit FactorRemembered(key, enterpriseId, sourceCode, factorScaled);
    }

    function lastFactorOf(string memory enterpriseId, string memory sourceCode) external view returns (uint256) {
        return _lastFactor[_key2(enterpriseId, sourceCode)];
    }

    // ---------------- 合规判定 ----------------

    /**
     * 规则①（配额）：本期（年累计）是否超出年度配额。
     * 优先读链上镜像，镜像为 0（尚未同步）时使用链下传入值兜底。未配置配额视为合规。
     */
    function checkCompliance(string memory enterpriseId, uint256 year, uint256 fallbackYearToDateScaled)
        public view returns (uint8 code, bool compliant, string memory reason)
    {
        bytes32 key = _key2(enterpriseId, _uintToStr(year));
        uint256 quota = _annualQuota[key];
        if (quota == 0) {
            return (OK, true, "NO_QUOTA_CONFIGURED");
        }
        uint256 mirrored = _yearTotal[key];
        uint256 used = mirrored == 0 ? fallbackYearToDateScaled : mirrored;
        if (used > quota) {
            return (QUOTA_EXCEEDED, false, "QUOTA_EXCEEDED");
        }
        return (OK, true, "OK");
    }

    /** 规则②（环比波动）：|环比基点| > 阈值 即不合规；无上期基准视为合规并返回 deltaBp = 0 */
    function checkFluctuation(uint256 currentTotalScaled, uint256 previousTotalScaled)
        public view returns (uint8 code, bool compliant, int256 deltaBp, string memory reason)
    {
        if (previousTotalScaled == 0) {
            return (OK, true, 0, "NO_BASELINE");
        }
        deltaBp = _deltaBp(currentTotalScaled, previousTotalScaled);
        int256 absBp = deltaBp < 0 ? -deltaBp : deltaBp;
        if (uint256(absBp) > fluctuationThresholdBp) {
            return (FLUCTUATION_EXCEEDED, false, deltaBp, "FLUCTUATION_EXCEEDED");
        }
        return (OK, true, deltaBp, "OK");
    }

    /** 规则③（活动数据为 0，疑似漏填） */
    function checkActivity(uint256 activityDataScaled) public pure returns (uint8 code, bool compliant, string memory reason) {
        if (activityDataScaled == 0) {
            return (MISSING_ACTIVITY_DATA, false, "MISSING_ACTIVITY_DATA");
        }
        return (OK, true, "OK");
    }

    /** 规则④（因子不符）：与链上最近一次使用值比较；无历史视为合规 */
    function checkFactor(string memory enterpriseId, string memory sourceCode, uint256 factorScaled)
        public view returns (uint8 code, bool compliant, uint256 previousFactorScaled)
    {
        uint256 prev = _lastFactor[_key2(enterpriseId, sourceCode)];
        previousFactorScaled = prev;
        if (prev != 0 && prev != factorScaled) {
            return (FACTOR_MISMATCH, false, prev);
        }
        return (OK, true, prev);
    }

    /**
     * 规则⑤（迟报）：报告期早于「当前月份 - lateReportMonths」即视为迟报。
     * 月份以整数 YYYYMM 表示（如 202410），当前月份由后端传入。
     */
    function checkLateReport(uint256 periodYyyymm, uint256 currentYyyymm)
        public view returns (uint8 code, bool compliant, string memory reason)
    {
        if (periodYyyymm == 0 || currentYyyymm == 0) {
            return (OK, true, "NO_PERIOD");
        }
        uint256 deadline = _minusMonths(currentYyyymm, lateReportMonths);
        if (deadline == 0) {
            return (OK, true, "NO_DEADLINE");
        }
        if (periodYyyymm < deadline) {
            return (LATE_REPORT, false, "LATE_REPORT");
        }
        return (OK, true, "OK");
    }

    /**
     * 一次调用完成全部链上规则判定，返回位掩码供后端按位生成告警。
     * 后端按位解析：1 配额 / 2 环比 / 4 漏填 / 8 因子 / 16 迟报。
     */
    function checkAll(
        string memory enterpriseId,
        uint256 year,
        string memory sourceCode,
        uint256 currentTotalScaled,
        uint256 previousTotalScaled,
        uint256 yearToDateScaled,
        uint256 activityDataScaled,
        uint256 factorScaled,
        uint256 periodYyyymm,
        uint256 currentYyyymm
    ) public view returns (
        uint16 flags,
        int256 deltaBp,
        uint256 previousFactorScaled,
        uint256 quotaScaled,
        uint256 yearToDateUsed,
        uint256 thresholdBp
    ) {
        thresholdBp = fluctuationThresholdBp;
        // 配额
        bytes32 yearKey = _key2(enterpriseId, _uintToStr(year));
        uint256 quota = _annualQuota[yearKey];
        quotaScaled = quota;
        if (quota != 0) {
            uint256 mirrored = _yearTotal[yearKey];
            uint256 used = mirrored == 0 ? yearToDateScaled : mirrored;
            yearToDateUsed = used;
            if (used > quota) {
                flags |= FLAG_QUOTA;
            }
        }

        // 环比波动
        if (previousTotalScaled > 0) {
            deltaBp = _deltaBp(currentTotalScaled, previousTotalScaled);
            int256 absBp = deltaBp < 0 ? -deltaBp : deltaBp;
            if (uint256(absBp) > fluctuationThresholdBp) {
                flags |= FLAG_FLUCTUATION;
            }
        }

        // 活动数据为 0
        if (activityDataScaled == 0) {
            flags |= FLAG_ACTIVITY;
        }

        // 因子不符
        uint256 prevFactor = _lastFactor[_key2(enterpriseId, sourceCode)];
        previousFactorScaled = prevFactor;
        if (prevFactor != 0 && prevFactor != factorScaled) {
            flags |= FLAG_FACTOR;
        }

        // 迟报
        if (periodYyyymm != 0 && currentYyyymm != 0) {
            uint256 deadline = _minusMonths(currentYyyymm, lateReportMonths);
            if (deadline != 0 && periodYyyymm < deadline) {
                flags |= FLAG_LATE;
            }
        }
    }

    // ---------------- 内部工具 ----------------

    /**
     * 环比基点（1bp = 0.01%），四舍五入到 BP_STEP（0.1%），
     * 与 Java 侧 BigDecimal(scale=1, HALF_UP) 的口径对齐。
     */
    function _deltaBp(uint256 currentScaled, uint256 previousScaled) private pure returns (int256) {
        if (previousScaled == 0) {
            return 0;
        }
        int256 diff = int256(currentScaled) - int256(previousScaled);
        int256 den = int256(previousScaled);
        int256 num = diff * 10000;
        int256 raw = num >= 0 ? (num + den / 2) / den : (num - den / 2) / den;
        int256 step = int256(BP_STEP);
        return raw >= 0 ? ((raw + step / 2) / step) * step : ((raw - step / 2) / step) * step;
    }

    /** yyyymm 往前推 months 个月；非法输入返回 0 表示无有效基准 */
    function _minusMonths(uint256 yyyymm, uint256 months) private pure returns (uint256) {
        uint256 y = yyyymm / 100;
        uint256 mo = yyyymm % 100;
        if (mo == 0 || mo > 12) {
            return 0;
        }
        uint256 total = y * 12 + (mo - 1);
        if (total < months) {
            return 0;
        }
        total -= months;
        return (total / 12) * 100 + (total % 12) + 1;
    }

    function _key2(string memory a, string memory b) private pure returns (bytes32) {
        return keccak256(abi.encodePacked(a, "|", b));
    }

    function _uintToStr(uint256 v) private pure returns (string memory) {
        if (v == 0) {
            return "0";
        }
        uint256 t = v;
        uint256 n = 0;
        while (t != 0) {
            n++;
            t /= 10;
        }
        bytes memory b = new bytes(n);
        while (v != 0) {
            n--;
            b[n] = bytes1(uint8(48 + v % 10));
            v /= 10;
        }
        return string(b);
    }
}
