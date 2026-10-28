#!/usr/bin/env bash
# ============================================================
# 碳链通 · 合约部署 / 升级脚本（Linux / Ubuntu + WeBASE 3.x）
#
# 作用：
#   部署模式：编译 Solidity → 部署 数据合约 + 逻辑合约 V1（+ 合规合约）
#             → 调数据合约 updateLogic 激活逻辑 → 注册 ABI → 可选写入 yml
#   升级模式：只编译并部署新的逻辑合约 → 调数据合约 updateLogic(newLogic)
#             → 注册 ABI → 输出新的 registry 地址（历史存证无需迁移）
#
# 前置：
#   1. FISCO BCOS 3.x 节点 + WeBASE-Front（默认 http://127.0.0.1:5002）已启动；
#   2. 已存在签名账户（二选一）：
#        WeBASE-Sign 托管：本脚本用 signUserId，私钥不出 Sign 服务（推荐）；
#        Front 本地私钥  ：GET /WeBASE-Front/privateKey?groupId=group0&type=0&userName=carbon-backend
#                          返回的 address 即 --user，同时作为数据合约初始 writer。
#
# 用法：
#   # 首次部署（数据 + 逻辑 V1 + 合规）
#   ./deploy-contracts.sh --user 0x... --solc /usr/local/bin/solc-0.8.30
#   # 升级逻辑（只涉及一笔 updateLogic 治理交易，不迁移历史存证）
#   ./deploy-contracts.sh --user 0x... --upgrade-logic 0x<dataAddress> \
#       --logic-contract CertRegistryLogicV2 --initial-seq <当前链上最高认证序号> \
#       --solc /usr/local/bin/solc-0.8.30
#   # 可选：把配置写入指定 yml
#   ./deploy-contracts.sh --user 0x... --out /opt/carbon-trace/application-ubuntu.yml
#
# 说明：
#   1. WeBASE 3.x 的群组标识是 group0（1.5.x 为 1），可用 --group 覆盖；
#   2. 合约需 ≥0.8.11；checkAll 参数/返回值较多，必须用支持 --via-ir 的编译器
#      （0.8.13+）编译，否则会报 Stack too deep。推荐 solc 0.8.30；
#   3. 逻辑与数据分离：数据合约保存状态并只允许当前 logic 写入；升级只部署新逻辑并
#      调 data.updateLogic，数据地址与历史存证保持不变；
#   4. 后端只直连逻辑合约；数据合约地址仅用于治理与升级。
# ============================================================
set -euo pipefail

FRONT='http://127.0.0.1:5002'
CONTEXT='/WeBASE-Front'
GROUP='group0'
USER=''
SIGN_USER_ID=''
OUT_FILE=''
SOLC=''
UPGRADE_DATA=''
LOGIC_CONTRACT=''
INITIAL_SEQ=''
CONTRACT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 默认把 ABI 写回后端 resources，保证仓库内 ABI 与本次部署的逻辑版本一致
ABI_OUT_DIR="$(cd "$CONTRACT_DIR/.." && pwd)/src/main/resources/chain"

# ---------- 命令行参数 ----------
#   --front <url>          WeBASE-Front 地址，默认 http://127.0.0.1:5002
#   --group <id>           群组标识，默认 group0
#   --user <address>       交易账户地址（必填；Sign 托管时仍用于写入数据合约初始 writer）
#   --sign-user-id <id>    提供则走 Sign 托管接口，私钥不出 WeBASE-Sign
#   --solc <path>          提供则用本地 solc 编译，否则交给 WeBASE 内置编译器
#   --out <path>           首次部署写出完整 yml；升级模式写出仅含 registry 的片段
#   --upgrade-logic <addr> 升级模式：数据合约地址；只部署新逻辑并调用 updateLogic
#   --logic-contract <name> 逻辑合约名，默认部署模式 V1、升级模式 V2
#   --initial-seq <n>       V2 构造参数：当前链上最高认证序号（如 CR000085 → 85）
#   --abi-out <dir>        ABI 输出目录，默认 ../src/main/resources/chain
#   -h|--help              打印脚本头部第 2~48 行说明后退出
while [[ $# -gt 0 ]]; do
  case "$1" in
    --front)          FRONT="$2"; shift 2 ;;
    --group)          GROUP="$2"; shift 2 ;;
    --user)           USER="$2"; shift 2 ;;
    --sign-user-id)   SIGN_USER_ID="$2"; shift 2 ;;
    --solc)           SOLC="$2"; shift 2 ;;
    --out)            OUT_FILE="$2"; shift 2 ;;
    --upgrade-logic)  UPGRADE_DATA="$2"; shift 2 ;;
    --logic-contract) LOGIC_CONTRACT="$2"; shift 2 ;;
    --initial-seq)    INITIAL_SEQ="$2"; shift 2 ;;
    --abi-out)        ABI_OUT_DIR="$2"; shift 2 ;;
    -h|--help)        sed -n '2,48p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

# USER 必填：本地私钥模式下它是签名账户；Sign 托管模式下它是数据合约的初始 writer。
if [[ -z "$USER" ]]; then
  echo "必须提供 --user：交易账户地址（Sign 托管时也需提供，用于数据合约初始 writer）。" >&2
  exit 1
fi
if [[ -n "$UPGRADE_DATA" && ! "$UPGRADE_DATA" =~ ^0x[0-9a-fA-F]{40}$ ]]; then
  echo "--upgrade-logic 需要合法的数据合约地址（0x + 40 位十六进制）：$UPGRADE_DATA" >&2
  exit 1
fi
if [[ -n "$INITIAL_SEQ" && ! "$INITIAL_SEQ" =~ ^[0-9]+$ ]]; then
  echo "--initial-seq 必须是非负整数：$INITIAL_SEQ" >&2
  exit 1
fi
# 依赖检查：curl 用于探活，python3 用于组装与解析 JSON。
command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }

# 编译器能力校验（仅当显式传入 --solc 时执行）：
#   合约需 solc >=0.8.11；而 checkAll 参数/返回值较多，必须走 --via-ir（solc 0.8.13+）才能避免 Stack too deep。
#   这里用 `solc --help` 中是否存在 --via-ir 选项来判断版本能力，比解析版本号更稳妥。
if [[ -n "$SOLC" ]]; then
  help_file=$(mktemp)
  "$SOLC" --help > "$help_file" 2>&1 || true
  if ! grep -q -- '--via-ir' "$help_file"; then
    rm -f "$help_file"
    echo "编译器不支持 --via-ir（需 solc 0.8.13+，推荐 0.8.30）：$SOLC" >&2
    exit 1
  fi
  rm -f "$help_file"
fi

# 模式判定：
#   UPGRADE_DATA 非空 → 升级模式，默认逻辑合约 V2；
#   否则 → 部署模式，默认逻辑合约 V1。
if [[ -n "$UPGRADE_DATA" ]]; then
  MODE='upgrade'
  LOGIC_CONTRACT="${LOGIC_CONTRACT:-CertRegistryLogicV2}"
else
  MODE='deploy'
  LOGIC_CONTRACT="${LOGIC_CONTRACT:-CertRegistryLogicV1}"
fi
LOGIC_SOURCE="$CONTRACT_DIR/$LOGIC_CONTRACT.sol"
if [[ ! -f "$LOGIC_SOURCE" ]]; then
  echo "逻辑合约源码不存在：$LOGIC_SOURCE" >&2
  exit 1
fi
# V2 的构造函数需要初始最高序号；缺失时直接拒绝，避免部署后把所有历史编号误判为空闲
if [[ "$LOGIC_CONTRACT" == 'CertRegistryLogicV2' && -z "$INITIAL_SEQ" ]]; then
  echo "CertRegistryLogicV2 需要 --initial-seq（当前链上最高认证序号，如 CR000085 → 85）。" >&2
  exit 1
fi

# 编译单个合约，向 stdout 输出 {"abiInfo": [...], "bytecodeBin": "0x..."}。
# 分支一（指定 --solc）：本地编译
#   --combined-json abi,bin 一次输出 ABI 与部署字节码；
#   --evm-version london 显式锁定 EVM 目标为 London，避免高版本 solc 默认启用
#     Shanghai/Cancun 新增操作码（如 PUSH0、MCOPY）而链节点不支持；
#   --via-ir 走 IR 编译管线，规避 checkAll 参数/返回值过多导致的 Stack too deep（需 solc 0.8.13+）。
#   python 侧按 "<源文件>:<合约名>" 精确匹配（key.endswith(':$name')），取第一个命中项即停止。
# 分支二（未指定 --solc）：把源码 Base64 后 POST /contract/contractCompile，由 WeBASE 内置编译器编译。
compile_contract() {
  local name="$1" file="$2" response
  if [[ -n "$SOLC" ]]; then
    local solc_flags='--evm-version london --via-ir'
    response=$("$SOLC" $solc_flags --combined-json abi,bin "$file" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for key,val in d['contracts'].items():
    if key.endswith(':$name'):
        abi=val['abi']
        if isinstance(abi,str):
            abi=json.loads(abi)
        print(json.dumps({'abiInfo':abi,'bytecodeBin':val['bin']}))
        break
else:
    sys.exit(2)
")
    echo "$response"
    return
  fi
  response=$(python3 - "$file" "$name" "$GROUP" "$FRONT$CONTEXT" <<'PY'
import base64, json, sys, urllib.request
file, name, group, base = sys.argv[1:5]
src = open(file, 'rb').read()
body = {"contractName": name, "groupId": group,
        "solidityBase64": base64.b64encode(src).decode()}
req = urllib.request.Request(base + '/contract/contractCompile',
                             data=json.dumps(body).encode(),
                             headers={'Content-Type': 'application/json'})
try:
    print(urllib.request.urlopen(req, timeout=120).read().decode())
except urllib.error.HTTPError as e:
    print(e.read().decode()); sys.exit(1)
PY
)
  echo "$response"
}

# 从 stdin 的 JSON 中取顶层字段：字符串原样输出，其余类型序列化为 JSON 文本。
json_get() {
  python3 -c "
import json,sys
d=json.load(sys.stdin)
v=d.get('$1')
print(v if isinstance(v,str) else json.dumps(v))
"
}

# 部署单个合约，输出部署接口返回的响应体（合约地址由调用方从响应中去引号、去换行得到）。
# 请求体：groupId / contractName / abiInfo / bytecodeBin / funcParam（构造参数）。
# 签名分支：SIGN_USER_ID 非空 → /contract/deployWithSign；否则 → /contract/deploy。
deploy_contract() {
  local name="$1" abi="$2" bin="$3" ctor="$4"
  python3 - "$FRONT$CONTEXT" "$GROUP" "$USER" "$SIGN_USER_ID" "$name" "$abi" "$bin" "$ctor" <<'PY'
import json, sys, urllib.request
base, group, user, sign, name, abi, binhex, ctor = sys.argv[1:9]
body = {"groupId": group, "contractName": name,
        "abiInfo": json.loads(abi), "bytecodeBin": binhex,
        "funcParam": json.loads(ctor), "contractSource": "", "contractPath": "",
        "version": ""}
path = '/contract/deployWithSign' if sign else '/contract/deploy'
if sign: body["signUserId"] = sign
else:    body["user"] = user
req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                             headers={'Content-Type': 'application/json'})
try:
    print(urllib.request.urlopen(req, timeout=180).read().decode())
except urllib.error.HTTPError as e:
    print(e.read().decode()); sys.exit(1)
PY
}

# 发起一笔合约写交易（用于 updateLogic），成功时输出交易哈希。
# funcParam 为 JSON 数组文本，元素按 ABI 原文传值（地址直接给 0x...，不要加引号）。
# 失败判定与后端 TransHandleReceipt.successful() 保持一致：statusOK=false 或 status 非 0x0 即失败。
send_transaction() {
  local name="$1" addr="$2" abi="$3" func="$4" params="$5"
  python3 - "$FRONT$CONTEXT" "$GROUP" "$USER" "$SIGN_USER_ID" "$name" "$addr" "$abi" "$func" "$params" <<'PY'
import json, sys, urllib.request
base, group, user, sign, name, addr, abi, func, params = sys.argv[1:10]
body = {"groupId": group, "contractName": name, "contractAddress": addr,
        "funcName": func, "funcParam": json.loads(params), "contractAbi": json.loads(abi)}
path = '/trans/handleWithSign' if sign else '/trans/handle'
if sign: body["signUserId"] = sign
else:    body["user"] = user
req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                             headers={'Content-Type': 'application/json'})
try:
    raw = urllib.request.urlopen(req, timeout=180).read().decode()
except urllib.error.HTTPError as e:
    print(e.read().decode()); sys.exit(1)
try:
    resp = json.loads(raw)
except Exception:
    print('交易返回非 JSON：' + raw[:300]); sys.exit(1)
status_ok = resp.get('statusOK')
status = str(resp.get('status') or '').strip().lower()
ok = (status_ok is True) or (status_ok is None and status in ('', '0x0', '0x00', '0'))
if not ok:
    print('交易失败：' + raw[:400]); sys.exit(1)
print(resp.get('transactionHash', ''))
PY
}

# 把 ABI 注册到 WeBASE-Front（POST /contract/abiInfo），便于控制台/区块浏览器按地址调用。
# 注册失败只打印告警、不中断脚本：此时合约已成功上链，注册仅影响 Front 侧的调用便利性。
register_abi() {
  local name="$1" addr="$2" abi="$3" bin="$4"
  python3 - "$FRONT$CONTEXT" "$GROUP" "$name" "$addr" "$abi" "$bin" <<'PY'
import json, sys, urllib.request
base, group, name, addr, abi, binhex = sys.argv[1:7]
body = {"groupId": group, "contractName": name, "address": addr,
        "abiInfo": json.loads(abi), "contractBin": binhex}
req = urllib.request.Request(base + '/contract/abiInfo',
                             data=json.dumps(body).encode(),
                             headers={'Content-Type': 'application/json'})
try:
    urllib.request.urlopen(req, timeout=60).read()
    print(f"  {name} ABI 已注册")
except urllib.error.HTTPError as e:
    print(f"  {name} ABI 注册失败（不影响链上调用）：{e.read().decode()[:200]}")
PY
}

# 把 ABI 写回后端 resources（覆盖式），保证后端使用的 ABI 与本次生效的逻辑版本一致。
write_abi_file() {
  local target="$1" abi="$2"
  mkdir -p "$(dirname "$target")"
  python3 - "$target" "$abi" <<'PY'
import json, sys
target, abi = sys.argv[1], sys.argv[2]
with open(target, 'w', encoding='utf-8') as f:
    json.dump(json.loads(abi), f, ensure_ascii=False, indent=2)
    f.write('\n')
PY
  echo "  ABI 已写入：$target"
}

# 部署前回显关键参数，便于核对 Front 地址、群组、签名方式与实际生效的编译器。
echo '============================================'
echo "  碳链通 · 合约 $([[ "$MODE" == 'upgrade' ]] && echo '逻辑升级' || echo '首次部署')（WeBASE 3.x）"
echo "  Front     = $FRONT"
echo "  Group     = $GROUP"
echo "  签名方式  = $([[ -n "$SIGN_USER_ID" ]] && echo "WeBASE-Sign ($SIGN_USER_ID)" || echo "Front 本地私钥")"
echo "  编译方式  = $([[ -n "$SOLC" ]] && echo "本地 solc（$SOLC）" || echo "WeBASE 内置编译器")"
echo "  逻辑合约  = $LOGIC_CONTRACT"
echo "  交易账户  = $USER"
[[ "$MODE" == 'upgrade' ]] && echo "  数据合约  = $UPGRADE_DATA"
echo '============================================'

# ---------- 升级模式 ----------
if [[ "$MODE" == 'upgrade' ]]; then
  echo '[1/4] 编译数据合约与新的逻辑合约 ...'
  DATA_JSON=$(compile_contract 'CertRegistryData' "$CONTRACT_DIR/CertRegistryData.sol")
  LOGIC_JSON=$(compile_contract "$LOGIC_CONTRACT" "$LOGIC_SOURCE")
  DATA_ABI=$(echo "$DATA_JSON" | json_get abiInfo)
  LOGIC_ABI=$(echo "$LOGIC_JSON" | json_get abiInfo)
  LOGIC_BIN=$(echo "$LOGIC_JSON" | json_get bytecodeBin)
  [[ -n "$LOGIC_BIN" ]] || { echo "编译失败：$(echo "$LOGIC_JSON" | head -c 400)" >&2; exit 1; }
  echo '  编译成功'

  echo '[2/4] 部署新逻辑合约 ...'
  # 逻辑合约构造函数：V1 只有 dataAddress；V2 额外需要初始最高认证序号
  if [[ -n "$INITIAL_SEQ" ]]; then
    LOGIC_CTOR=$(python3 -c "import json,sys;print(json.dumps([sys.argv[1], sys.argv[2]]))" "$UPGRADE_DATA" "$INITIAL_SEQ")
  else
    LOGIC_CTOR=$(python3 -c "import json,sys;print(json.dumps([sys.argv[1]]))" "$UPGRADE_DATA")
  fi
  NEW_LOGIC_ADDR=$(deploy_contract "$LOGIC_CONTRACT" "$LOGIC_ABI" "$LOGIC_BIN" "$LOGIC_CTOR" | tr -d '"' | tr -d '\n')
  [[ "$NEW_LOGIC_ADDR" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "部署失败，返回：$NEW_LOGIC_ADDR" >&2; exit 1; }
  echo "  $LOGIC_CONTRACT -> $NEW_LOGIC_ADDR"

  echo '[3/4] 调用数据合约 updateLogic（唯一一笔升级治理交易）...'
  UPDATE_PARAMS=$(python3 -c "import json,sys;print(json.dumps([sys.argv[1]]))" "$NEW_LOGIC_ADDR")
  TX_HASH=$(send_transaction 'CertRegistryData' "$UPGRADE_DATA" "$DATA_ABI" 'updateLogic' "$UPDATE_PARAMS")
  echo "  updateLogic 已上链：$TX_HASH"
  register_abi "$LOGIC_CONTRACT" "$NEW_LOGIC_ADDR" "$LOGIC_ABI" "$LOGIC_BIN"
  write_abi_file "$ABI_OUT_DIR/CertRegistryLogic.abi.json" "$LOGIC_ABI"

  echo '[4/4] 升级完成，后端需更新的配置：'
  cat <<EOF
carbon.chain.contracts.registry.name=$LOGIC_CONTRACT
carbon.chain.contracts.registry.address=$NEW_LOGIC_ADDR
# 数据合约地址保持不变：$UPGRADE_DATA
# 历史存证无需迁移；重启后端后新逻辑即生效
EOF
  if [[ -n "$OUT_FILE" ]]; then
    mkdir -p "$(dirname "$OUT_FILE")"
    cat > "$OUT_FILE" <<EOF
# 由 deploy-contracts.sh --upgrade-logic 生成：仅包含 registry，请合并到主配置后使用
carbon:
  chain:
    contracts:
      registry:
        name: $LOGIC_CONTRACT
        address: $NEW_LOGIC_ADDR
EOF
    echo "  registry 配置片段已写入：$OUT_FILE"
  fi
  exit 0
fi

# ---------- 首次部署模式 ----------
# 步骤 1/5：编译三个合约，并分别取出 ABI 与 bytecodeBin。
echo '[1/5] 编译合约 ...'
COMPLIANCE_JSON=$(compile_contract 'CarbonCompliance' "$CONTRACT_DIR/CarbonCompliance.sol")
DATA_JSON=$(compile_contract 'CertRegistryData' "$CONTRACT_DIR/CertRegistryData.sol")
LOGIC_JSON=$(compile_contract "$LOGIC_CONTRACT" "$LOGIC_SOURCE")

COMPLIANCE_ABI=$(echo "$COMPLIANCE_JSON" | json_get abiInfo)
COMPLIANCE_BIN=$(echo "$COMPLIANCE_JSON" | json_get bytecodeBin)
DATA_ABI=$(echo "$DATA_JSON" | json_get abiInfo)
DATA_BIN=$(echo "$DATA_JSON" | json_get bytecodeBin)
LOGIC_ABI=$(echo "$LOGIC_JSON" | json_get abiInfo)
LOGIC_BIN=$(echo "$LOGIC_JSON" | json_get bytecodeBin)

[[ -n "$COMPLIANCE_BIN" && -n "$DATA_BIN" && -n "$LOGIC_BIN" ]] || {
  echo "编译失败：$(echo "$COMPLIANCE_JSON" | head -c 200) / $(echo "$DATA_JSON" | head -c 200) / $(echo "$LOGIC_JSON" | head -c 200)" >&2
  exit 1
}
echo '  编译成功'

# 步骤 2/5：部署合规合约与数据合约。
# WeBASE 3.x 的 funcParam 是 List<String>，每个元素必须是「参数值的 JSON 字面量」：
#   - CarbonCompliance 无构造参数 → []；
#   - CertRegistryData 构造函数为 address[] → ["0x..."] 作为单个参数的 JSON 文本，
#     整体再包一层数组，即 [ "[\"0x...\"]" ]；这里用 python 生成嵌套 JSON，避免手工转义。
echo '[2/5] 部署合规合约与数据合约 ...'
COMPLIANCE_ADDR=$(deploy_contract 'CarbonCompliance' "$COMPLIANCE_ABI" "$COMPLIANCE_BIN" '[]' | tr -d '"' | tr -d '\n')
DATA_CTOR=$(python3 -c "import json,sys;print(json.dumps([json.dumps([sys.argv[1]])]))" "$USER")
DATA_ADDR=$(deploy_contract 'CertRegistryData' "$DATA_ABI" "$DATA_BIN" "$DATA_CTOR" | tr -d '"' | tr -d '\n')

for addr in "$COMPLIANCE_ADDR" "$DATA_ADDR"; do
  [[ "$addr" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "部署失败，返回：$addr" >&2; exit 1; }
done
echo "  CarbonCompliance   -> $COMPLIANCE_ADDR"
echo "  CertRegistryData   -> $DATA_ADDR"

# 步骤 3/5：部署逻辑合约并激活。
# 顺序必须是「先数据、后逻辑、再 updateLogic」：数据合约初始 logic=address(0)，
# 未激活前所有写入都会被 onlyLogic 拒绝，避免部署间隙出现无人授权的写窗口。
echo '[3/5] 部署逻辑合约并激活 ...'
# 逻辑合约构造函数：V1 只有 dataAddress；如部署 V2，需同时提供初始最高认证序号
if [[ -n "$INITIAL_SEQ" ]]; then
  LOGIC_CTOR=$(python3 -c "import json,sys;print(json.dumps([sys.argv[1], sys.argv[2]]))" "$DATA_ADDR" "$INITIAL_SEQ")
else
  LOGIC_CTOR=$(python3 -c "import json,sys;print(json.dumps([sys.argv[1]]))" "$DATA_ADDR")
fi
LOGIC_ADDR=$(deploy_contract "$LOGIC_CONTRACT" "$LOGIC_ABI" "$LOGIC_BIN" "$LOGIC_CTOR" | tr -d '"' | tr -d '\n')
[[ "$LOGIC_ADDR" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "逻辑合约部署失败，返回：$LOGIC_ADDR" >&2; exit 1; }
UPDATE_PARAMS=$(python3 -c "import json,sys;print(json.dumps([sys.argv[1]]))" "$LOGIC_ADDR")
TX_HASH=$(send_transaction 'CertRegistryData' "$DATA_ADDR" "$DATA_ABI" 'updateLogic' "$UPDATE_PARAMS")
echo "  $LOGIC_CONTRACT -> $LOGIC_ADDR"
echo "  updateLogic 已上链：$TX_HASH"

# 步骤 4/5：注册 ABI 并写回后端 resources。
echo '[4/5] 注册 ABI ...'
register_abi 'CarbonCompliance' "$COMPLIANCE_ADDR" "$COMPLIANCE_ABI" "$COMPLIANCE_BIN"
register_abi 'CertRegistryData' "$DATA_ADDR" "$DATA_ABI" "$DATA_BIN"
register_abi "$LOGIC_CONTRACT" "$LOGIC_ADDR" "$LOGIC_ABI" "$LOGIC_BIN"
write_abi_file "$ABI_OUT_DIR/CertRegistryData.abi.json" "$DATA_ABI"
write_abi_file "$ABI_OUT_DIR/CertRegistryLogic.abi.json" "$LOGIC_ABI"

# 步骤 5/5：输出配置。
echo '[5/5] 配置项：'
cat <<EOF
carbon.chain.mode=webase
carbon.chain.webase.group-id=$GROUP
carbon.chain.webase.user-address=$USER
carbon.chain.contracts.registry.name=$LOGIC_CONTRACT
carbon.chain.contracts.registry.address=$LOGIC_ADDR
carbon.chain.contracts.compliance.address=$COMPLIANCE_ADDR
# 数据合约（仅治理/升级使用，后端不直连）：$DATA_ADDR
# 注意：沿用既有部署时请保留原 compliance.address；本次新部署的合规合约没有既有治理参数
EOF

if [[ -n "$OUT_FILE" ]]; then
  mkdir -p "$(dirname "$OUT_FILE")"
  cat > "$OUT_FILE" <<EOF
# 由 deploy-contracts.sh 生成，请勿提交到仓库（真实环境配置）
carbon:
  chain:
    mode: webase
    webase:
      group-id: $GROUP
      user-address: $USER
    contracts:
      registry:
        name: $LOGIC_CONTRACT
        address: $LOGIC_ADDR
      compliance:
        address: $COMPLIANCE_ADDR
EOF
  echo "  已写入：$OUT_FILE"
fi
