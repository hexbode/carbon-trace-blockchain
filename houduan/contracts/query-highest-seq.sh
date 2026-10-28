#!/usr/bin/env bash
# ============================================================
# 查询「真实链上最高认证序号」——逻辑升级前填写 --initial-seq 的取值依据
#
# 两种路径：
#   1. 提供 --logic（V2 逻辑合约地址）→ 直接读 highestCertSeq()，一次调用即可；
#   2. 不提供 --logic，或逻辑合约仍是 V1（没有该视图）→ 逐块扫描数据合约
#      CertRegistryData.certIdsInBlock(height)，取所有 CR 编号的最大序号。
#
# 为什么不看业务库：链上只追加不删除，删除排放记录只把存证标记为 INVALID、
# 撤销只标记为 REVOKED。业务库若删过最高编号的记录，SELECT MAX(id) 会偏小；
# 按偏小值填 --initial-seq 会让后端跳过对历史编号的占用检查并复用旧编号。
#
# 前置：数据合约 ABI 已注册到 WeBASE-Front（首次部署脚本会自动注册；
#       手工环境可用本脚本 --register 自动注册，或按 README 的 /contract/abiInfo curl 操作）。
#
# 用法：
#   ./query-highest-seq.sh --data 0x<dataAddress>
#   ./query-highest-seq.sh --data 0x<dataAddress> --logic 0x<logicAddress>
#   ./query-highest-seq.sh --data 0x<dataAddress> --from 0 --jobs 8
#   ./query-highest-seq.sh --data 0x<dataAddress> --register
#
# 参数：
#   --front <url>    WeBASE-Front 地址，默认 http://127.0.0.1:5002
#   --group <id>     群组标识，默认 group0
#   --data <addr>    数据合约 CertRegistryData 地址（必填）
#   --logic <addr>   V2 逻辑合约地址；提供则优先读 highestCertSeq()
#   --from <n>       区块扫描起始高度，默认 0
#   --jobs <n>       区块扫描并发数，默认 4（Front 每次调用都会新建 SDK 客户端，不宜过大）
#   --abi <path>     数据合约 ABI，默认 ../src/main/resources/chain/CertRegistryData.abi.json
#   --register       仅把数据合约 ABI 注册到 Front 后退出
#   -h|--help        打印本说明
# ============================================================
set -euo pipefail

FRONT='http://127.0.0.1:5002'
CONTEXT='/WeBASE-Front'
GROUP='group0'
DATA=''
LOGIC=''
FROM_BLOCK=0
JOBS=4
CONTRACT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ABI="$CONTRACT_DIR/../src/main/resources/chain/CertRegistryData.abi.json"
REGISTER_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --front)    FRONT="$2"; shift 2 ;;
    --group)    GROUP="$2"; shift 2 ;;
    --data)     DATA="$2"; shift 2 ;;
    --logic)    LOGIC="$2"; shift 2 ;;
    --from)     FROM_BLOCK="$2"; shift 2 ;;
    --jobs)     JOBS="$2"; shift 2 ;;
    --abi)      ABI="$2"; shift 2 ;;
    --register) REGISTER_ONLY=1; shift ;;
    -h|--help)  sed -n '2,33p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

[[ -n "$DATA" ]] || { echo "必须提供 --data <数据合约地址>" >&2; exit 1; }
[[ "$DATA" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "--data 需要 0x + 40 位十六进制：$DATA" >&2; exit 1; }
if [[ -n "$LOGIC" && ! "$LOGIC" =~ ^0x[0-9a-fA-F]{40}$ ]]; then
  echo "--logic 需要 0x + 40 位十六进制：$LOGIC" >&2
  exit 1
fi
[[ -f "$ABI" ]] || { echo "数据合约 ABI 不存在：$ABI" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }

python3 - "$FRONT$CONTEXT" "$GROUP" "$DATA" "$LOGIC" "$ABI" "$FROM_BLOCK" "$JOBS" "$REGISTER_ONLY" <<'PY'
import concurrent.futures
import json
import re
import sys
import urllib.error
import urllib.request

base, group, data_addr, logic_addr, abi_path, from_block, jobs, register_only = sys.argv[1:9]
from_block = int(from_block)
jobs = max(1, int(jobs))
register_only = register_only == '1'
CR_PATTERN = re.compile(r'^CR(\d+)$')
with open(abi_path, encoding='utf-8') as f:
    DATA_ABI = json.load(f)


def post(path, body, timeout=60):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError('调用失败 %s：%s' % (path, e.read().decode()[:400]))


def get(path, timeout=30):
    try:
        raw = urllib.request.urlopen(base + path, timeout=timeout).read().decode().strip()
    except urllib.error.HTTPError as e:
        raise RuntimeError('调用失败 %s：%s' % (path, e.read().decode()[:400]))
    try:
        return json.loads(raw)
    except Exception:
        return raw


def unquote(value):
    """WeBASE 会把字符串返回值二次 JSON 序列化，去掉多余引号。"""
    while isinstance(value, str) and len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        value = json.loads(value)
    return value


def flatten(value):
    if isinstance(value, list):
        for item in value:
            yield from flatten(item)
    else:
        yield value


def read_data(func, params):
    return post('/trans/handle', {
        'groupId': group,
        'contractName': 'CertRegistryData',
        'contractAddress': data_addr,
        'funcName': func,
        'funcParam': params,
        'contractAbi': DATA_ABI,
    })


def seq_of(cert_id):
    m = CR_PATTERN.match(str(cert_id))
    return int(m.group(1)) if m else None


if register_only:
    post('/contract/abiInfo', {
        'groupId': group,
        'contractName': 'CertRegistryData',
        'address': data_addr,
        'abiInfo': DATA_ABI,
        'contractBin': '',
    })
    print('CertRegistryData ABI 已注册：%s' % data_addr)
    sys.exit(0)


# 路径 1：V2 逻辑合约的 highestCertSeq()
if logic_addr:
    try:
        result = post('/trans/handle', {
            'groupId': group,
            'contractName': 'CertRegistryLogicV2',
            'contractAddress': logic_addr,
            'funcName': 'highestCertSeq',
            'funcParam': [],
            'contractAbi': [{
                'inputs': [], 'name': 'highestCertSeq',
                'outputs': [{'internalType': 'uint256', 'name': '', 'type': 'uint256'}],
                'stateMutability': 'view', 'type': 'function',
            }],
        })
    except RuntimeError as e:
        print('highestCertSeq() 不可用（逻辑合约可能是 V1），回退到区块扫描：%s' % e, file=sys.stderr)
        result = None
    seqs = [int(v) for v in flatten(result) if str(v).isdigit()] if result is not None else []
    if seqs:
        print('highestCertSeq = %d（来源：逻辑合约 %s）' % (max(seqs), logic_addr))
        sys.exit(0)

# 路径 2：逐块读取 certIdsInBlock
latest = get('/%s/web3/blockNumber' % group)
if isinstance(latest, dict):
    latest = latest.get('blockNumber', latest.get('result'))
if isinstance(latest, str):
    latest = latest.strip('"')
latest = int(latest)
if latest < 0:
    print('链上暂无区块，最高序号 = 0')
    sys.exit(0)


def block_max(height):
    try:
        result = read_data('certIdsInBlock', [str(height)])
    except RuntimeError as e:
        print('区块 %d 读取失败，跳过：%s' % (height, e), file=sys.stderr)
        return None
    best = None
    for value in flatten(result):
        seq = seq_of(unquote(value))
        if seq is not None and (best is None or seq > best):
            best = seq
    return best


best = None
scanned = 0
with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
    futures = {pool.submit(block_max, h): h for h in range(from_block, latest + 1)}
    for future in concurrent.futures.as_completed(futures):
        scanned += 1
        value = future.result()
        if value is not None and (best is None or value > best):
            best = value
        if scanned % 100 == 0:
            print('  已扫描 %d / %d 区块...' % (scanned, latest - from_block + 1), file=sys.stderr)

print('扫描区块 %d..%d，共 %d 个' % (from_block, latest, latest - from_block + 1))
print('highestCertSeq = %d（来源：CertRegistryData.certIdsInBlock 全链扫描）' % (best if best is not None else 0))
print('提示：把该值填入升级脚本 --initial-seq；偏大只会多做逐号检查，偏小会复用历史编号。')
PY
