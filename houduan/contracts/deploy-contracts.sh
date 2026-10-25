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

# ---------- 默认参数 ----------
# FRONT / CONTEXT：WeBASE-Front 地址与上下文前缀，实际接口为 $FRONT$CONTEXT/contract/...
# GROUP：FISCO BCOS 3.x 群组标识（1.5.x 为 1），可用 --group 覆盖
# USER：交易账户地址，同时作为 CarbonCertRegistry 构造参数（合约初始 writer）
# SIGN_USER_ID：WeBASE-Sign 的 signUserId；为空则改用 Front 本地私钥签名
# OUT_FILE：非空时额外输出 Spring Boot YAML 配置片段
# SOLC：本地 solc 可执行文件路径；为空则回退 WeBASE 内置编译器
# CONTRACT_DIR：脚本自身所在目录，两个 .sol 默认与该脚本同目录

# ---------- 命令行参数 ----------
#   --front <url>         WeBASE-Front 地址，默认 http://127.0.0.1:5002
#   --group <id>          群组标识，默认 group0
#   --user <address>      交易账户地址（必填；Sign 托管时仍用于写入合约初始 writer）
#   --sign-user-id <id>   提供则走 /contract/deployWithSign，私钥不出 WeBASE-Sign
#   --solc <path>         提供则用本地 solc 编译，否则交给 WeBASE 内置编译器
#   --out <path>          额外写出 YAML 配置的路径
#   -h|--help             打印脚本头部第 2~30 行说明后退出
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

# USER 必填：本地私钥模式下它是签名账户，Sign 托管模式下它是 CarbonCertRegistry 的初始 writer。
if [[ -z "$USER" ]]; then
  echo "必须提供 --user：交易账户地址（Sign 托管时也需提供，用于合约初始 writer）。" >&2
  exit 1
fi
# 依赖检查：curl 用于 Front 的编译/部署/ABI 注册接口，python3 用于组装与解析 JSON。
command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }

# 编译器能力校验（仅当显式传入 --solc 时执行）：
#   合约需 solc >=0.8.11；而 checkAll 参数/返回值较多，必须走 --via-ir（solc 0.8.13+）才能避免 Stack too deep。
#   这里用 `solc --help` 中是否存在 --via-ir 选项来判断版本能力，比解析版本号更稳妥。
#   未传入 --solc 时会跳过本校验并回退 WeBASE 内置编译器，其版本与本脚本无关，需自行确认满足要求。
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

# 预留的通用 POST 封装：拼接 $FRONT$CONTEXT$path，-m 120 限制单次请求 120 秒，-sS 静默但保留错误输出。
# 当前编译/部署/注册均改用 python3 urllib（需要处理嵌套 JSON），此函数未被调用。
post() {
  local path="$1" body="$2"
  curl -sS -m 120 -X POST "$FRONT$CONTEXT$path" \
    -H 'Content-Type: application/json' -d "$body"
}

# 编译单个合约，向 stdout 输出 {"abiInfo": [...], "bytecodeBin": "0x..."}。
# 分支一（指定 --solc）：本地编译
#   --combined-json abi,bin 一次输出 ABI 与部署字节码；
#   --evm-version london 显式锁定 EVM 目标为 London，避免高版本 solc 默认启用
#     Shanghai/Cancun 新增操作码（如 PUSH0、MCOPY）而链节点不支持；
#   --via-ir 走 IR 编译管线，规避 checkAll 参数/返回值过多导致的 Stack too deep（需 solc 0.8.13+）。
#   python 侧按 "<源文件>:<合约名>" 精确匹配（key.endswith(':$name')），取第一个命中项即停止，
#   因此同文件内同名合约或重名依赖不会误取。
# 分支二（未指定 --solc）：把源码 Base64 后 POST /contract/contractCompile，由 WeBASE 内置编译器编译，
#   响应形状由 WeBASE 决定，脚本原样透传；版本与编译选项不受本脚本控制。
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
# 用途：把编译结果里的 abiInfo（数组）与 bytecodeBin（字符串）分别取出，供部署与 ABI 注册使用。
json_get() {
  python3 -c "
import json,sys
d=json.load(sys.stdin)
v=d.get('$1')
print(v if isinstance(v,str) else json.dumps(v))
"
}

# 部署前回显关键参数，便于核对 Front 地址、群组、签名方式与实际生效的编译器。
echo '============================================'
echo '  碳链通 · 合约部署（WeBASE 3.x）'
echo "  Front     = $FRONT"
echo "  Group     = $GROUP"
echo "  签名方式  = $([[ -n "$SIGN_USER_ID" ]] && echo "WeBASE-Sign ($SIGN_USER_ID)" || echo "Front 本地私钥")"
echo "  编译方式  = $([[ -n "$SOLC" ]] && echo "本地 solc（$SOLC）" || echo "WeBASE 内置编译器")"
echo "  交易账户  = $USER"
echo '============================================'

# 步骤 1/4：编译两个合约，并分别取出 ABI（JSON 数组）与 bytecodeBin（十六进制字节码）。
echo '[1/4] 编译合约 ...'
COMPLIANCE_JSON=$(compile_contract 'CarbonCompliance' "$CONTRACT_DIR/CarbonCompliance.sol")
REGISTRY_JSON=$(compile_contract 'CarbonCertRegistry' "$CONTRACT_DIR/CarbonCertRegistry.sol")

COMPLIANCE_ABI=$(echo "$COMPLIANCE_JSON" | json_get abiInfo)
COMPLIANCE_BIN=$(echo "$COMPLIANCE_JSON" | json_get bytecodeBin)
REGISTRY_ABI=$(echo "$REGISTRY_JSON" | json_get abiInfo)
REGISTRY_BIN=$(echo "$REGISTRY_JSON" | json_get bytecodeBin)

# 任一字节码为空即视为编译失败：打印两份响应的前 400 字符，便于定位
# 版本不满足 pragma（>=0.8.11）、未启用 --via-ir 导致的 Stack too deep，或内置编译器报错。
[[ -n "$COMPLIANCE_BIN" && -n "$REGISTRY_BIN" ]] || {
  echo "编译失败：$(echo "$COMPLIANCE_JSON" | head -c 400) / $(echo "$REGISTRY_JSON" | head -c 400)" >&2
  exit 1
}
echo '  编译成功'

# 部署单个合约，输出部署接口返回的响应体（合约地址由调用方从响应中去引号、去换行得到）。
# 请求体：
#   groupId / contractName / abiInfo / bytecodeBin / funcParam（构造参数）
#   contractSource、contractPath、version 置空：表示直接以 ABI+字节码部署，不依赖 Front 已存源码。
# 签名分支：
#   SIGN_USER_ID 非空 → /contract/deployWithSign，附 signUserId，私钥由 WeBASE-Sign 托管；
#   否则              → /contract/deploy，用 user 对应的 Front 本地私钥签名。
# 超时 180 秒：部署需等待交易上链，比查询接口耗时更长。
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

# 步骤 2/4：先部署合规合约，再部署存证合约。
# WeBASE 3.x 的 funcParam 是 List<String>，每个元素必须是「参数值的 JSON 字面量」：
#   - CarbonCompliance 无构造参数 → []；
#   - CarbonCertRegistry 构造函数为 address[] → ["0x..."] 作为单个参数的 JSON 文本，
#     整体再包一层数组，即 [ "[\"0x...\"]" ]；这里用 python 生成该嵌套 JSON，避免手工转义。
echo '[2/4] 部署合约 ...'
COMPLIANCE_ADDR=$(deploy_contract 'CarbonCompliance' "$COMPLIANCE_ABI" "$COMPLIANCE_BIN" '[]' | tr -d '"' | tr -d '\n')
REGISTRY_CTOR=$(python3 -c "import json,sys;print(json.dumps([json.dumps([sys.argv[1]])]))" "$USER")
REGISTRY_ADDR=$(deploy_contract 'CarbonCertRegistry' "$REGISTRY_ABI" "$REGISTRY_BIN" "$REGISTRY_CTOR" | tr -d '"' | tr -d '\n')

# 校验部署返回的地址形状：0x + 40 位十六进制；不符合即视为部署失败并回显原始返回值。
for addr in "$COMPLIANCE_ADDR" "$REGISTRY_ADDR"; do
  [[ "$addr" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "部署失败，返回：$addr" >&2; exit 1; }
done
echo "  CarbonCompliance  -> $COMPLIANCE_ADDR"
echo "  CarbonCertRegistry -> $REGISTRY_ADDR"

# 步骤 3/4：把 ABI 注册到 WeBASE-Front（POST /contract/abiInfo），便于控制台/区块浏览器按地址调用。
# 注册失败只打印告警、不中断脚本：此时合约已成功上链，注册仅影响 Front 侧的调用便利性。
# 请求体：groupId / contractName / address / abiInfo / contractBin；超时 60 秒。
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

# 步骤 4/4：输出可直接粘贴到 Spring Boot 配置的键值对。
# 键名与后端 application.yml 的 carbon.chain.* 层级一一对应：
#   carbon.chain.mode=webase
#   carbon.chain.webase.group-id / user-address
#   carbon.chain.contracts.registry.address / compliance.address
echo '[4/4] 配置项：'
cat <<EOF
carbon.chain.mode=webase
carbon.chain.webase.group-id=$GROUP
carbon.chain.webase.user-address=$USER
carbon.chain.contracts.registry.address=$REGISTRY_ADDR
carbon.chain.contracts.compliance.address=$COMPLIANCE_ADDR
EOF

# 可选：把上面同样的配置展开为 YAML 层级写入 --out 指定文件，供 Spring Boot 直接读取。
# 内容含真实环境地址，注释已提示不要提交仓库；父目录不存在时自动创建。
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
