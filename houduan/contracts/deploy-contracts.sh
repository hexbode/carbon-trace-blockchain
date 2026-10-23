#!/usr/bin/env bash
# ============================================================
# 碳链通 · 合约部署脚本（Linux / Ubuntu + WeBASE 3.x）
#
# 作用：编译 Solidity → 部署到链上 → 注册 ABI
#       → 可选把合约地址写入指定的 yml 配置
#
# 前置：
#   1. FISCO BCOS 3.x 节点 + WeBASE-Front（默认 http://127.0.0.1:5002）已启动；
#   2. 已存在签名账户（二选一）：
#        WeBASE-Sign 托管：本脚本用 signUserId，私钥不出 Sign 服务（推荐）；
#        Front 本地私钥  ：GET /WeBASE-Front/privateKey?groupId=group0&type=0&userName=carbon-backend
#                          返回的 address 即 --user，同时作为合约初始 writer。
#
# 用法：
#   ./deploy-contracts.sh --user 0x...                 # 本地私钥账户
#   ./deploy-contracts.sh --sign-user-id abc123 --user 0x...   # Sign 托管（user 仅用于写入 writer）
#   ./deploy-contracts.sh --user 0x... --solc /usr/local/bin/solc-0.8.30
#   ./deploy-contracts.sh --user 0x... --out /opt/carbon-trace/application-ubuntu.yml
#
# 说明：
#   1. WeBASE 3.x 的群组标识是 group0（1.5.x 为 1），可用 --group 覆盖；
#   2. 合约需 ≥0.8.11；checkAll 参数/返回值较多，必须用支持 --via-ir 的编译器
#      （0.8.13+）编译，否则会报 Stack too deep。推荐 solc 0.8.30：
#      https://binaries.soliditylang.org/linux-amd64/solc-linux-amd64-v0.8.30+commit.73712a01
#      脚本用 solc 编译后直接把 abi/bin 部署上链，不依赖 WeBASE 内置编译器版本。
# ============================================================
set -euo pipefail

FRONT='http://127.0.0.1:5002'
CONTEXT='/WeBASE-Front'
GROUP='group0'
USER=''
SIGN_USER_ID=''
OUT_FILE=''
SOLC=''
CONTRACT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --front)         FRONT="$2"; shift 2 ;;
    --group)         GROUP="$2"; shift 2 ;;
    --user)          USER="$2"; shift 2 ;;
    --sign-user-id)  SIGN_USER_ID="$2"; shift 2 ;;
    --solc)          SOLC="$2"; shift 2 ;;
    --out)           OUT_FILE="$2"; shift 2 ;;
    -h|--help)       sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

if [[ -z "$USER" ]]; then
  echo "必须提供 --user：交易账户地址（Sign 托管时也需提供，用于合约初始 writer）。" >&2
  exit 1
fi
command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }

# 合约 checkAll 参数/返回值较多，必须用 --via-ir 编译（solc 0.8.13+）
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

post() {
  local path="$1" body="$2"
  curl -sS -m 120 -X POST "$FRONT$CONTEXT$path" \
    -H 'Content-Type: application/json' -d "$body"
}

# 编译：优先用本地 solc（版本与合约一致），否则交给 WeBASE 内置编译器
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

json_get() {
  python3 -c "
import json,sys
d=json.load(sys.stdin)
v=d.get('$1')
print(v if isinstance(v,str) else json.dumps(v))
"
}

echo '============================================'
echo '  碳链通 · 合约部署（WeBASE 3.x）'
echo "  Front     = $FRONT"
echo "  Group     = $GROUP"
echo "  签名方式  = $([[ -n "$SIGN_USER_ID" ]] && echo "WeBASE-Sign ($SIGN_USER_ID)" || echo "Front 本地私钥")"
echo "  编译方式  = $([[ -n "$SOLC" ]] && echo "本地 solc（$SOLC）" || echo "WeBASE 内置编译器")"
echo "  交易账户  = $USER"
echo '============================================'

echo '[1/4] 编译合约 ...'
COMPLIANCE_JSON=$(compile_contract 'CarbonCompliance' "$CONTRACT_DIR/CarbonCompliance.sol")
REGISTRY_JSON=$(compile_contract 'CarbonCertRegistry' "$CONTRACT_DIR/CarbonCertRegistry.sol")

COMPLIANCE_ABI=$(echo "$COMPLIANCE_JSON" | json_get abiInfo)
COMPLIANCE_BIN=$(echo "$COMPLIANCE_JSON" | json_get bytecodeBin)
REGISTRY_ABI=$(echo "$REGISTRY_JSON" | json_get abiInfo)
REGISTRY_BIN=$(echo "$REGISTRY_JSON" | json_get bytecodeBin)

[[ -n "$COMPLIANCE_BIN" && -n "$REGISTRY_BIN" ]] || {
  echo "编译失败：$(echo "$COMPLIANCE_JSON" | head -c 400) / $(echo "$REGISTRY_JSON" | head -c 400)" >&2
  exit 1
}
echo '  编译成功'

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

echo '[2/4] 部署合约 ...'
# WeBASE 3.x 的 funcParam 是 List<String>，每个元素必须是参数值的 JSON 字面量。
# 无构造参数 → []；构造参数 address[] → [ "[\"0x...\"]" ]
COMPLIANCE_ADDR=$(deploy_contract 'CarbonCompliance' "$COMPLIANCE_ABI" "$COMPLIANCE_BIN" '[]' | tr -d '"' | tr -d '\n')
REGISTRY_CTOR=$(python3 -c "import json,sys;print(json.dumps([json.dumps([sys.argv[1]])]))" "$USER")
REGISTRY_ADDR=$(deploy_contract 'CarbonCertRegistry' "$REGISTRY_ABI" "$REGISTRY_BIN" "$REGISTRY_CTOR" | tr -d '"' | tr -d '\n')

for addr in "$COMPLIANCE_ADDR" "$REGISTRY_ADDR"; do
  [[ "$addr" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "部署失败，返回：$addr" >&2; exit 1; }
done
echo "  CarbonCompliance  -> $COMPLIANCE_ADDR"
echo "  CarbonCertRegistry -> $REGISTRY_ADDR"

echo '[3/4] 注册 ABI ...'
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
register_abi 'CarbonCompliance' "$COMPLIANCE_ADDR" "$COMPLIANCE_ABI" "$COMPLIANCE_BIN"
register_abi 'CarbonCertRegistry' "$REGISTRY_ADDR" "$REGISTRY_ABI" "$REGISTRY_BIN"

echo '[4/4] 配置项：'
cat <<EOF
carbon.chain.mode=webase
carbon.chain.webase.group-id=$GROUP
carbon.chain.webase.user-address=$USER
carbon.chain.contracts.registry.address=$REGISTRY_ADDR
carbon.chain.contracts.compliance.address=$COMPLIANCE_ADDR
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
        address: $REGISTRY_ADDR
      compliance:
        address: $COMPLIANCE_ADDR
EOF
  echo "  已写入：$OUT_FILE"
fi
