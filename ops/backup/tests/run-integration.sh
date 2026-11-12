#!/usr/bin/env bash
# HFP-03-011 integration suite ランナー（ホスト側）
#
# docker compose（pinned MySQL 8.0.36 ×2 + tool イメージ）で隔離ネットワークを
# 構築し、integration-pitr.sh を tool コンテナ内で実行して実 PITR を検証する。
#
# - host port を公開しない（internal network）
# - パスワードは合成値（INTEGRATION_SRC_PW / INTEGRATION_TGT_PW）を自動生成
# - 全ステップ失敗時は非 0 で終了（CI では allow-failure にしない契約）
# - Docker が無い環境では BLOCKED として終了する
#
# usage: bash ops/backup/tests/run-integration.sh
set -uo pipefail
export MSYS_NO_PATHCONV=1
umask 077

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
COMPOSE_FILE=$(cygpath -w "$HERE/docker-compose.integration.yml" 2>/dev/null || echo "$HERE/docker-compose.integration.yml")

if ! docker info > /dev/null 2>&1; then
  echo "BLOCKED: Docker が利用できません。integration suite は実 MySQL コンテナで実行します。" >&2
  exit 2
fi
if ! docker compose version > /dev/null 2>&1; then
  echo "BLOCKED: docker compose plugin が利用できません。" >&2
  exit 2
fi

WORK="${INTEGRATION_WORK_DIR:-$HERE/.integration-work}"
WORK_OWNED=0
PROJECT_OWNED=0
upAttempted=0
PROJECT_IDS_VERIFIED=0
PROJECT_CONTAINER_IDS=()
SOURCE_CID=""
TARGET_CID=""
TOOL_CID=""
SCHEDULER_CID=""
REPLICA_CID=""
NET=""
RUN_ID=""
PROJECT=""
DIAG_FILE=""
FAILURE_STAGE=""
FAILURE_NATIVE_RC=0
FAILURE_OUTPUT_FILE=""
WORK_AWK=""
WWORK=""

compose() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
}

# ------------------------------------------------------------
# パス正規化ヘルパー
# ------------------------------------------------------------
# Windows（Git Bash/MSYS）では cygpath で Windows 形式と POSIX 形式を往復させ、
# mount 表（例: /tmp ⇔ %TEMP%）による表記揺れを WORK と許可ルートの双方で同じ規則に揃える。
platform_posix_path() {
  local path=$1 windows_form posix_form
  [[ -n "$path" && "$path" != *$'\n'* ]] || return 1
  if command -v cygpath > /dev/null 2>&1; then
    windows_form=$(cygpath -w "$path" 2>/dev/null) || return 1
    posix_form=$(cygpath -u "$windows_form" 2>/dev/null) || return 1
    [[ -n "$posix_form" ]] || return 1
    path=$posix_form
  fi
  printf '%s\n' "$path"
}

# 絶対パスを字句的に正規化する。'.'・'..' を含むパスや相対パスは拒否する（fail-closed）。
lexical_normalize_abs() {
  local path=$1 normalized="" component rest
  [[ "$path" == /* && "$path" != *$'\n'* ]] || return 1
  rest=${path#/}
  while [[ -n "$rest" ]]; do
    component=${rest%%/*}
    if [[ "$rest" == */* ]]; then rest=${rest#*/}; else rest=""; fi
    [[ -n "$component" ]] || continue
    if [[ "$component" == "." || "$component" == ".." ]]; then return 1; fi
    normalized+="/$component"
  done
  printf '%s\n' "${normalized:-/}"
}

# 既存ディレクトリの物理パス（symlink 解決後）を返す。
canonical_dir() {
  local physical
  physical=$(cd -P -- "$1" 2>/dev/null && pwd -P) || return 1
  platform_posix_path "$physical"
}

reject_work_path() {
  echo "FAIL: integration work path を拒否しました（chmod/chown は行いません）: $1" >&2
  return 1
}

# 検証済み WORK が終了処理の時点でも同一の所有ディレクトリであることを再確認する。
verify_work_still_owned() {
  (( WORK_OWNED )) || return 1
  [[ -d "$WORK" && ! -L "$WORK" ]] || return 1
  [[ "$(canonical_dir "$WORK")" == "$WORK" ]] || return 1
  [[ -f "$WORK/.ses-pitr-run-owner" && ! -L "$WORK/.ses-pitr-run-owner" ]] || return 1
  grep -qxF "run=$RUN_ID" "$WORK/.ses-pitr-run-owner" 2>/dev/null
}

normalize_evidence_permissions() {
  (( WORK_OWNED )) || return 0
  if ! verify_work_still_owned; then
    echo "cleanup: 作業ディレクトリの所有を再確認できないため evidence の権限正規化を行いません" >&2
    return 0
  fi
  local host_uid host_gid
  host_uid=$(id -u 2>/dev/null || echo 0)
  host_gid=$(id -g 2>/dev/null || echo 0)
  if docker info > /dev/null 2>&1; then
    docker run --rm --user 0:0 -v "$WWORK:/work:rw" ses-backup-tool:integration \
      sh -c "chown -R ${host_uid}:${host_gid} /work/evidence /work/drill-evidence 2>/dev/null || true; chmod -R u+rwX,go-rwx /work/evidence /work/drill-evidence 2>/dev/null || true" 2>/dev/null || true
  fi
  chmod -R u+rwX,go-rwx "$WORK/evidence" "$WORK/drill-evidence" 2>/dev/null || true
}

normalize_work_permissions() {
  (( WORK_OWNED )) || return 0
  if ! verify_work_still_owned; then
    echo "cleanup: 作業ディレクトリの所有を再確認できないため権限正規化を行いません" >&2
    return 0
  fi
  local host_uid host_gid
  host_uid=$(id -u 2>/dev/null || echo 0)
  host_gid=$(id -g 2>/dev/null || echo 0)
  if docker info > /dev/null 2>&1; then
    docker run --rm --user 0:0 -v "$WWORK:/work:rw" ses-backup-tool:integration \
      sh -c "chown -R ${host_uid}:${host_gid} /work 2>/dev/null || true; chmod -R u+rwX,go-rwx /work 2>/dev/null || true" 2>/dev/null || true
  fi
  chmod -R u+rwX,go-rwx "$WORK" 2>/dev/null || true
}

cleanup_project() {
  (( PROJECT_OWNED && upAttempted )) || return 0
  if (( ! PROJECT_IDS_VERIFIED )) && ! refresh_project_containers; then
    echo "cleanup: project resource ownership could not be verified; leaving unknown resources untouched" >&2
    return 1
  fi
  compose down -v > /dev/null 2>&1
}

cleanup() {
  local primary_rc=$?
  trap - EXIT
  if (( primary_rc != 0 )); then
    collect_failure_diagnostics "$primary_rc" || true
  fi
  cleanup_project || true
  if (( WORK_OWNED )); then
    normalize_work_permissions || true
  fi
  exit "$primary_rc"
}
trap cleanup EXIT

# ------------------------------------------------------------
# WORK パス検証（chmod/chown の対象を限定する境界）
# ------------------------------------------------------------
# WORK は次のいずれかに限る。比較は platform_posix_path → 字句正規化 → 物理パスの順で揃えて行う。
#   1. 既定値 $HERE/.integration-work（CI・verify-like-ci.sh の evidence artifact パス）
#   2. 一時ディレクトリルート（TMPDIR / TMP / TEMP。いずれも未設定なら /tmp）の厳密な配下
# 許可ルートより下の各要素は symlink を含んではならず、親ディレクトリは既存でなければならない
# （mkdir -p で祖先を作らない）。既存の WORK は空であり、かつ (1) の既定値か、親に
# verify-like-ci.ps1 の所有マーカー（.ses-pitr-owner.json）を持つ integration-work に限る。
# 条件を満たさなければ fail-closed とし、WORK_OWNED を立てない（chmod/chown は一切行わない）。
validate_work_path() {
  local posix lexical here_lex here_canon repo_canon default_work candidate candidate_lex candidate_canon
  local root_canon="" rel="" rest component current i parent_path work_canon entries owner_file last_index
  local -a candidates=() roots_lex=() roots_canon=() rel_parts=()

  posix=$(platform_posix_path "$WORK") || { reject_work_path "パスを正規化できません"; return 1; }
  lexical=$(lexical_normalize_abs "$posix") \
    || { reject_work_path "'.'・'..'・改行を含まない絶対パスが必要です: $WORK"; return 1; }
  here_canon=$(canonical_dir "$HERE") || { reject_work_path "スクリプトディレクトリを正規化できません"; return 1; }
  repo_canon=$(canonical_dir "$HERE/../../..") || { reject_work_path "リポジトリルートを正規化できません"; return 1; }
  here_lex=$(lexical_normalize_abs "$(platform_posix_path "$HERE")") \
    || { reject_work_path "スクリプトディレクトリを字句正規化できません"; return 1; }
  default_work="$here_canon/.integration-work"

  if [[ "$lexical" == "$here_lex/.integration-work" || "$lexical" == "$default_work" ]]; then
    root_canon=$here_canon
    rel=".integration-work"
  else
    [[ -n "${TMPDIR:-}" ]] && candidates+=("$TMPDIR")
    [[ -n "${TMP:-}" ]] && candidates+=("$TMP")
    [[ -n "${TEMP:-}" ]] && candidates+=("$TEMP")
    (( ${#candidates[@]} > 0 )) || candidates=(/tmp)
    for candidate in "${candidates[@]}"; do
      candidate_lex=$(lexical_normalize_abs "$(platform_posix_path "$candidate")") || continue
      candidate_canon=$(canonical_dir "$candidate_lex") || continue
      [[ "$candidate_lex" != "/" && "$candidate_canon" != "/" ]] || continue
      roots_lex+=("$candidate_lex")
      roots_canon+=("$candidate_canon")
    done
    for i in "${!roots_lex[@]}"; do
      if [[ "$lexical" == "${roots_lex[i]}/"* ]]; then
        rel=${lexical#"${roots_lex[i]}/"}
        root_canon=${roots_canon[i]}
        break
      fi
      if [[ "$lexical" == "${roots_canon[i]}/"* ]]; then
        rel=${lexical#"${roots_canon[i]}/"}
        root_canon=${roots_canon[i]}
        break
      fi
    done
  fi
  if [[ -z "$rel" || -z "$root_canon" ]]; then
    echo "FAIL: integration work path is outside the permitted fresh-work boundary（許可ルート外: $lexical）" >&2
    return 1
  fi

  rest=$rel
  while [[ -n "$rest" ]]; do
    component=${rest%%/*}
    if [[ "$rest" == */* ]]; then rest=${rest#*/}; else rest=""; fi
    [[ -n "$component" ]] && rel_parts+=("$component")
  done
  (( ${#rel_parts[@]} > 0 )) || { reject_work_path "許可ルートそのものは使えません"; return 1; }
  last_index=$(( ${#rel_parts[@]} - 1 ))

  # 許可ルートより下を 1 要素ずつ検査する（祖先・WORK 自身のいずれも symlink を許さない）。
  current=$root_canon
  for i in "${!rel_parts[@]}"; do
    current="$current/${rel_parts[i]}"
    if [[ -L "$current" ]]; then
      reject_work_path "symlink を含むパス要素があります: $current"
      return 1
    fi
    if (( i < last_index )) && [[ ! -d "$current" ]]; then
      reject_work_path "親ディレクトリが存在しません（祖先は作成しません）: $current"
      return 1
    fi
  done
  work_canon=$current
  parent_path=${work_canon%/*}
  if [[ "$(canonical_dir "$parent_path")" != "$parent_path" ]]; then
    reject_work_path "親ディレクトリの物理パスが一致しません: $parent_path"
    return 1
  fi

  if [[ "$work_canon" != "$default_work" ]]; then
    if [[ "$work_canon/" == "$repo_canon/"* ]]; then
      reject_work_path "リポジトリ配下は既定パス以外使えません: $work_canon"
      return 1
    fi
    if [[ "$repo_canon/" == "$work_canon/"* ]]; then
      reject_work_path "リポジトリの祖先ディレクトリは使えません: $work_canon"
      return 1
    fi
  fi

  if [[ -e "$work_canon" || -L "$work_canon" ]]; then
    if [[ ! -d "$work_canon" || -L "$work_canon" ]]; then
      echo "FAIL: integration work path is not a fresh owned directory" >&2
      return 1
    fi
    if ! entries=$(find "$work_canon" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null); then
      reject_work_path "既存ディレクトリの中身を確認できません: $work_canon"
      return 1
    fi
    if [[ -n "$entries" ]]; then
      echo "FAIL: integration work path is not empty; existing contents are preserved" >&2
      return 1
    fi
    if [[ "$work_canon" != "$default_work" ]]; then
      owner_file="$parent_path/.ses-pitr-owner.json"
      if [[ "${rel_parts[last_index]}" != "integration-work" || ! -f "$owner_file" || -L "$owner_file" ]] \
          || ! grep -q '"runToken"' "$owner_file" 2>/dev/null \
          || ! grep -q '"workDir"' "$owner_file" 2>/dev/null; then
        reject_work_path "既存の空ディレクトリに制御側の所有マーカー（.ses-pitr-owner.json）がありません: $work_canon"
        return 1
      fi
    fi
  else
    if ! mkdir "$work_canon" 2>/dev/null; then
      reject_work_path "作業ディレクトリを排他的に作成できません: $work_canon"
      return 1
    fi
  fi
  if [[ ! -d "$work_canon" || -L "$work_canon" || "$(canonical_dir "$work_canon")" != "$work_canon" ]]; then
    reject_work_path "検証中に作業ディレクトリが変化しました: $work_canon"
    return 1
  fi
  WORK=$work_canon

  RUN_ID="$(date +%s)-$$-$RANDOM"
  PROJECT="ses-bkup-int-$RUN_ID"
  if ! printf 'run=%s\nwork=%s\nproject=%s\n' "$RUN_ID" "$WORK" "$PROJECT" > "$WORK/.ses-pitr-run-owner"; then
    echo "FAIL: integration work ownership marker could not be written" >&2
    return 1
  fi
  WORK_OWNED=1
  mkdir -p "$WORK/evidence" "$WORK/drill-evidence" "$WORK/uploads" "$WORK/work" "$WORK/repo" "$WORK/staging" \
    "$WORK/locks" "$WORK/capath" "$WORK/capath-tgt" "$WORK/heartbeats" "$WORK/scheduler" "$WORK/keys" || return 1
  export INTEGRATION_SRC_PW INTEGRATION_TGT_PW
  WWORK=$(cygpath -w "$WORK" 2>/dev/null || echo "$WORK")
  WORK_AWK=$(cygpath -u "$WORK" 2>/dev/null || echo "$WORK")
  export INTEGRATION_WORK_DIR="$WWORK"
  printf '%s\n' "$INTEGRATION_SRC_PW" > "$WORK/src-password"
  printf '%s\n' "$INTEGRATION_TGT_PW" > "$WORK/tgt-password"
  printf '%s\n' "$INTEGRATION_SRC_PW" > "$WORK/repo-password"
  printf '%s\n' 'restore-svc-pw' > "$WORK/restore-svc-pw"
  : > "$WORK/keys/priv1.pem"
  : > "$WORK/keys/priv2.pem"
  DIAG_FILE="$WORK/evidence/failure-diagnostics.txt"
  return 0
}

# >>> harness-selftest: diagnostics helpers >>>
# この区間は harness-selftest-test.sh が sed で切り出して単体検証する。区間外の関数へ依存しないこと。
#
# 失敗時診断の上限（静的見積り）:
#   - Docker 呼び出しは ps 1 回 + 所有確認 inspect 最大 5 回 + service ごとの state/logs 最大 10 回 = 最大 16 回。
#     各回は timeout -k 1s により DIAG_CALL_TIMEOUT_SECONDS（既定 5 秒）+ 強制終了猶予 1 秒で打ち切る。
#   - redactor（awk）は対応する Docker 呼び出しと並行に走り、同じ上限 + 2 秒（+ 猶予 1 秒）で打ち切る。
#   - 最終 secret scan は DIAG_SCAN_TIMEOUT_SECONDS（既定 10 秒）+ 猶予 1 秒で打ち切る。
#   時計の挙動に依存しない上限は「呼び出し回数 × 個別 timeout」（約 127 秒）である。
#   DIAG_BUDGET_SECONDS（既定 15 秒）は bash の SECONDS（壁時計ベース・1 秒分解能）による目安で、
#   使い切った後の呼び出しを省略するだけの best-effort であり、硬い期限ではない。
DIAG_MAX_BYTES=$((512 * 1024))
DIAG_SECTION_MAX_BYTES=65536
DIAG_BUDGET_SECONDS=15
DIAG_CALL_TIMEOUT_SECONDS=5
DIAG_SCAN_TIMEOUT_SECONDS=10
DIAG_BUDGET_END=0

# 診断上限の環境変数による上書き（selftest 用）。既定値より小さい値だけを受け付ける。
init_diag_limits() {
  local value
  value=${INTEGRATION_DIAG_BUDGET_SECONDS:-}
  if [[ "$value" =~ ^[1-9][0-9]?$ ]] && (( value < DIAG_BUDGET_SECONDS )); then
    DIAG_BUDGET_SECONDS=$value
  fi
  value=${INTEGRATION_DIAG_CALL_TIMEOUT_SECONDS:-}
  if [[ "$value" =~ ^[1-9]$ ]] && (( value < DIAG_CALL_TIMEOUT_SECONDS )); then
    DIAG_CALL_TIMEOUT_SECONDS=$value
  fi
  return 0
}

diag_budget_start() {
  DIAG_BUDGET_END=$((SECONDS + DIAG_BUDGET_SECONDS))
}

# 次の呼び出しに使う timeout 秒数を出力する。目安予算を使い切っていれば非 0。
diag_call_limit() {
  local remaining limit=$DIAG_CALL_TIMEOUT_SECONDS
  remaining=$((DIAG_BUDGET_END - SECONDS))
  (( remaining > 0 )) || return 1
  if (( remaining < limit )); then limit=$remaining; fi
  printf '%s\n' "$limit"
}

# usage: bounded_call <追加猶予秒> <command...>
bounded_call() {
  local extra=$1 limit
  shift
  command -v timeout > /dev/null 2>&1 || return 127
  limit=$(diag_call_limit) || return 124
  timeout -k 1s "$((limit + extra))s" "$@"
}

bounded_docker() {
  bounded_call 0 docker "$@"
}

# usage: fixed_timeout_call <秒> <command...>
# timeout が無い環境では素の実行になるため、入力サイズで仕事量が有界な処理（最終 scan）にだけ使う。
fixed_timeout_call() {
  local seconds=$1
  shift
  if command -v timeout > /dev/null 2>&1; then
    timeout -k 1s "${seconds}s" "$@"
  else
    "$@"
  fi
}

# ストリーミング redactor 本体（POSIX awk / mawk / gawk 互換）。
#   - 1 行は前段の cut で lineCap+1 バイトに切り詰めてから読むため、awk の行バッファは有界。
#     lineCap を超えた行は lineCap で切り、末尾が secret の接頭辞なら伏字化したうえで以降の出力を止める
#     （切り捨てた部分の PEM 状態が分からないため fail-closed）。
#   - 処理する入力は inputCap バイトまで。上限到達後・出力上限到達後は読み捨てるだけで、上流を SIGPIPE で壊さない。
#   - PEM 秘密鍵ブロックは BEGIN と同じ種別の END（例: END RSA PRIVATE KEY）まで伏字化し、END の後続は残す。
#   - secret literal は全出現位置（重なり・隣接を含む）を区間併合して伏字化する。
#   - パスワード系 secret ファイル（1〜4）が読めない・空のときは何も出力せず失敗する。
REDACT_AWK_PROGRAM='
function load_secret_file(path,    line, n, rc) {
  if (path == "") return -1
  rc = (getline line < path)
  if (rc < 0) return -1
  n = 0
  while (rc > 0) {
    sub(/\r$/, "", line)
    if (length(line) > 0) {
      n++
      if (!(line in secretSeen)) {
        secretSeen[line] = 1
        secrets[++secretCount] = line
      }
    }
    rc = (getline line < path)
  }
  close(path)
  return n
}
function reload_secret_files(    i) {
  for (i = 1; i <= 6; i++) load_secret_file(secretFile[i])
}
function strip_private_keys(text,    kept, endPos, steps) {
  kept = ""
  steps = 0
  while (length(text) > 0) {
    steps++
    if (steps > maxPemSteps) {
      pemLimited = 1
      return kept
    }
    if (insidePrivateKey) {
      endPos = index(text, privateKeyEnd)
      if (endPos == 0) return kept
      text = substr(text, endPos + length(privateKeyEnd))
      insidePrivateKey = 0
      privateKeyEnd = ""
      continue
    }
    if (!match(text, /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/)) return kept text
    kept = kept substr(text, 1, RSTART - 1) "[PRIVATE KEY BLOCK REDACTED]"
    # "-----BEGIN " は 11 バイト。BEGIN 行の種別部分（例: "RSA PRIVATE KEY-----"）をそのまま END に使う。
    privateKeyEnd = "-----END " substr(text, RSTART + 11, RLENGTH - 11)
    text = substr(text, RSTART + RLENGTH)
    insidePrivateKey = 1
  }
  return kept
}
function redact_literals(text,    textLen, i, s, sLen, start, pos, endPos, matches, p, redacted, coveredEnd, plainStart, inRun) {
  textLen = length(text)
  if (textLen == 0 || secretCount == 0) return text
  split("", matchEnd)
  matches = 0
  for (i = 1; i <= secretCount; i++) {
    s = secrets[i]
    sLen = length(s)
    start = 1
    while (start + sLen - 1 <= textLen) {
      pos = index(substr(text, start), s)
      if (pos == 0) break
      pos += start - 1
      endPos = pos + sLen - 1
      if (!(pos in matchEnd) || matchEnd[pos] < endPos) matchEnd[pos] = endPos
      matches++
      if (matches > maxMatches) return "[REDACTED LINE: secret match limit exceeded]"
      start = pos + 1
    }
  }
  if (matches == 0) return text
  redacted = ""
  coveredEnd = 0
  plainStart = 1
  inRun = 0
  for (p = 1; p <= textLen; p++) {
    if (!(p in matchEnd)) continue
    if (!inRun || p > coveredEnd + 1) {
      redacted = redacted substr(text, plainStart, p - plainStart) "[REDACTED]"
      inRun = 1
    }
    if (matchEnd[p] > coveredEnd) coveredEnd = matchEnd[p]
    plainStart = coveredEnd + 1
  }
  return redacted substr(text, plainStart)
}
function guard_secret_prefix_tail(text,    textLen, i, s, k, best) {
  textLen = length(text)
  best = 0
  for (i = 1; i <= secretCount; i++) {
    s = secrets[i]
    k = length(s) - 1
    if (k > textLen) k = textLen
    while (k > best) {
      if (substr(text, textLen - k + 1) == substr(s, 1, k)) {
        best = k
        break
      }
      k--
    }
  }
  if (best == 0) return text
  return substr(text, 1, textLen - best) "[REDACTED]"
}
function emit(text,    room, piece) {
  room = maxBytes - emittedBytes
  if (room <= 0) {
    outputLimited = 1
    return
  }
  piece = text
  if (length(text) > room) {
    piece = substr(text, 1, room)
    outputLimited = 1
  }
  printf "%s", piece
  printf "%s", piece >> out
  emittedBytes += length(piece)
}
BEGIN {
  maxBytes += 0
  lineCap += 0
  inputCap += 0
  maxPemSteps = 64
  maxMatches = 512
  secretFile[1] = secretFile1
  secretFile[2] = secretFile2
  secretFile[3] = secretFile3
  secretFile[4] = secretFile4
  secretFile[5] = secretFile5
  secretFile[6] = secretFile6
  printf "" > out
  close(out)
  for (i = 1; i <= 4; i++) if (load_secret_file(secretFile[i]) <= 0) failClosed = 1
  load_secret_file(secretFile[5])
  load_secret_file(secretFile[6])
}
{
  if (failClosed) next
  if (stopped) {
    if (emittedBytes >= maxBytes) outputLimited = 1
    next
  }
  inputBytes += length($0) + 1
  if (inputBytes > inputCap) {
    stopped = 1
    inputLimited = 1
    next
  }
  text = $0
  overLong = 0
  if (length(text) > lineCap) {
    text = substr(text, 1, lineCap)
    overLong = 1
  }
  reload_secret_files()
  text = redact_literals(strip_private_keys(text))
  if (overLong) text = guard_secret_prefix_tail(text)
  emit(text "\n")
  if (overLong) {
    stopped = 1
    lineLimited = 1
  }
  if (pemLimited || outputLimited || emittedBytes >= maxBytes) stopped = 1
}
END {
  if (failClosed) {
    print "[REDACTOR FAIL-CLOSED: required secret file missing or empty; output omitted]" > "/dev/stderr"
    exit 3
  }
  if (inputLimited) print "[SECTION INPUT LIMIT REACHED; REMAINDER OMITTED]" > "/dev/stderr"
  if (lineLimited) print "[LINE LENGTH LIMIT REACHED; REMAINDER OMITTED]" > "/dev/stderr"
  if (pemLimited) print "[PRIVATE KEY PARSE LIMIT REACHED; REMAINDER OMITTED]" > "/dev/stderr"
  if (outputLimited) print "[SECTION TRUNCATED AT BYTE LIMIT]" > "/dev/stderr"
  if (emittedBytes > 0 && close(out) != 0) exit 1
}
'

# usage: redact_stream_to_file <出力ファイル> <最大出力バイト> [bounded]
#   既定（stream）: compose/PITR/drill 本体の出力用。上流コマンドの寿命に従うため時間上限は付けないが、
#     処理する入力バイト（最大出力の 4 倍）・行長・出力バイトは有界で、上限後は読み捨てて上流の終了コードを保つ。
#   bounded: 失敗時診断用。awk も診断予算付き timeout で打ち切る（打ち切り時は非 0）。
redact_stream_to_file() {
  local output_file=$1 max_bytes=$2 mode=${3:-stream}
  local -a awk_cmd status
  [[ "$max_bytes" =~ ^[1-9][0-9]{0,7}$ ]] || return 2
  : > "$output_file" || return 1
  awk_cmd=(env LC_ALL=C awk -v out="$output_file" -v maxBytes="$max_bytes"
    -v lineCap="$max_bytes" -v inputCap="$((max_bytes * 4))"
    -v secretFile1="$WORK_AWK/src-password" -v secretFile2="$WORK_AWK/tgt-password"
    -v secretFile3="$WORK_AWK/repo-password" -v secretFile4="$WORK_AWK/restore-svc-pw"
    -v secretFile5="$WORK_AWK/keys/priv1.pem" -v secretFile6="$WORK_AWK/keys/priv2.pem"
    "$REDACT_AWK_PROGRAM")
  if [[ "$mode" == bounded ]]; then
    LC_ALL=C cut -b "1-$((max_bytes + 1))" | bounded_call 2 "${awk_cmd[@]}"
    status=("${PIPESTATUS[@]}")
  else
    LC_ALL=C cut -b "1-$((max_bytes + 1))" | "${awk_cmd[@]}"
    status=("${PIPESTATUS[@]}")
  fi
  if (( ${status[1]:-1} != 0 )); then return "${status[1]:-1}"; fi
  if (( ${status[0]:-1} != 0 )); then return "${status[0]:-1}"; fi
  return 0
}

append_diagnostic_line() {
  local line=$1 current room
  current=$(wc -c < "$DIAG_FILE" 2>/dev/null || echo 0)
  current=${current//[[:space:]]/}
  room=$((DIAG_MAX_BYTES - current))
  (( room > 0 )) || return 0
  printf '%s\n' "$line" | head -c "$room" >> "$DIAG_FILE"
}

append_diagnostic_section() {
  local label=$1 section_file=$2 current room cap header_bytes
  [[ -f "$section_file" ]] || return 0
  current=$(wc -c < "$DIAG_FILE" 2>/dev/null || echo 0)
  current=${current//[[:space:]]/}
  local header="[$label]"
  header_bytes=$(LC_ALL=C printf '\n%s\n' "$header" | wc -c)
  header_bytes=${header_bytes//[[:space:]]/}
  room=$((DIAG_MAX_BYTES - current - header_bytes))
  (( room > 0 )) || return 0
  cap=$DIAG_SECTION_MAX_BYTES
  (( room < cap )) && cap=$room
  printf '\n%s\n' "$header" >> "$DIAG_FILE"
  head -c "$cap" "$section_file" >> "$DIAG_FILE" 2>/dev/null || true
}

# 保存直前の最終検査。DIAG_FILE がサイズ上限を超える・secret ファイルが読めない・
# secret literal / PEM BEGIN が残っている・scan が時間内に終わらない場合は非 0（呼び出し側で全体を破棄）。
scan_failure_diagnostics() {
  local size
  [[ -n "$DIAG_FILE" && -f "$DIAG_FILE" && ! -L "$DIAG_FILE" ]] || return 1
  size=$(wc -c < "$DIAG_FILE" 2>/dev/null) || return 1
  size=${size//[[:space:]]/}
  [[ "$size" =~ ^[0-9]+$ ]] || return 1
  (( size <= DIAG_MAX_BYTES )) || return 1
  fixed_timeout_call "$DIAG_SCAN_TIMEOUT_SECONDS" env LC_ALL=C awk \
    -v secretFile1="$WORK_AWK/src-password" -v secretFile2="$WORK_AWK/tgt-password" \
    -v secretFile3="$WORK_AWK/repo-password" -v secretFile4="$WORK_AWK/restore-svc-pw" \
    -v secretFile5="$WORK_AWK/keys/priv1.pem" -v secretFile6="$WORK_AWK/keys/priv2.pem" '
    function load_secret_file(path,    line, n, rc) {
      if (path == "") return -1
      rc = (getline line < path)
      if (rc < 0) return -1
      n = 0
      while (rc > 0) {
        sub(/\r$/, "", line)
        if (length(line) > 0) {
          n++
          secrets[++secretCount] = line
        }
        rc = (getline line < path)
      }
      close(path)
      return n
    }
    BEGIN {
      if (load_secret_file(secretFile1) <= 0 || load_secret_file(secretFile2) <= 0 \
          || load_secret_file(secretFile3) <= 0 || load_secret_file(secretFile4) <= 0) exit 2
      load_secret_file(secretFile5)
      load_secret_file(secretFile6)
    }
    {
      if ($0 ~ /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/) exit 1
      for (i = 1; i <= secretCount; i++) if (index($0, secrets[i]) > 0) exit 1
    }
  ' < "$DIAG_FILE"
}
# <<< harness-selftest: diagnostics helpers <<<

verify_project_container() {
  local id=$1 expected_service=${2:-} details found_id found_project found_service
  [[ "$id" =~ ^[a-fA-F0-9]{12,64}$ ]] || return 1
  details=$(docker inspect --format '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' "$id" 2>/dev/null) || return 1
  IFS='|' read -r found_id found_project found_service <<< "$details"
  [[ "${found_id:0:12}" == "${id:0:12}" && "$found_project" == "$PROJECT" ]] || return 1
  case "$found_service" in
    source|target|tool|scheduler|replica) ;;
    *) return 1 ;;
  esac
  [[ -z "$expected_service" || "$found_service" == "$expected_service" ]] || return 1
  case "$found_service" in
    source) SOURCE_CID=$id ;;
    target) TARGET_CID=$id ;;
    tool) TOOL_CID=$id ;;
    scheduler) SCHEDULER_CID=$id ;;
    replica) REPLICA_CID=$id ;;
  esac
  PROJECT_CONTAINER_IDS+=("$id")
}

refresh_project_containers() {
  local ids id count=0
  PROJECT_CONTAINER_IDS=()
  SOURCE_CID="" TARGET_CID="" TOOL_CID="" SCHEDULER_CID="" REPLICA_CID=""
  ids=$(compose ps -aq 2>/dev/null) || return 1
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    count=$((count + 1))
    (( count <= 5 )) || return 1
    verify_project_container "$id" || return 1
  done <<< "$ids"
  PROJECT_IDS_VERIFIED=1
}

service_container_id() {
  local service=$1 ids id count=0
  ids=$(compose ps -q "$service" 2>/dev/null) || return 1
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    count=$((count + 1))
    (( count == 1 )) || return 1
    verify_project_container "$id" "$service" || return 1
  done <<< "$ids"
  (( count == 1 ))
}

# source/target が同一の、このrunの Compose network にだけ接続していることを確認する。
# Go template の改行は {{println}} で出力する（文字列リテラル内の改行エスケープはシェルの
# クォートを経由すると字面の バックスラッシュ+n になり、network ID が壊れるため使わない）。
verify_project_network() {
  local source_networks target_networks source_network_id target_network_id details network_id network_name project_label network_label
  local network_format='{{range $name,$network := .NetworkSettings.Networks}}{{$name}}|{{$network.NetworkID}}{{println}}{{end}}'
  source_networks=$(docker inspect --format "$network_format" "$SOURCE_CID" 2>/dev/null) || return 1
  target_networks=$(docker inspect --format "$network_format" "$TARGET_CID" 2>/dev/null) || return 1
  # $(...) は末尾の改行を除去する。残る改行は複数 network への接続を意味するため拒否する。
  [[ -n "$source_networks" && -n "$target_networks" ]] || return 1
  [[ "$source_networks" != *$'\n'* && "$target_networks" != *$'\n'* ]] || return 1
  IFS='|' read -r network_name source_network_id <<< "$source_networks"
  IFS='|' read -r network_name target_network_id <<< "$target_networks"
  [[ "$source_network_id" =~ ^[a-fA-F0-9]{12,64}$ ]] || return 1
  [[ "$source_network_id" == "$target_network_id" ]] || return 1
  details=$(docker network inspect --format '{{.Id}}|{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.network"}}' "$source_network_id" 2>/dev/null) || return 1
  IFS='|' read -r network_id network_name project_label network_label <<< "$details"
  [[ "$network_id" == "$source_network_id" && "$project_label" == "$PROJECT" && "$network_label" == "ses-bkup" ]] || return 1
  NET=$network_id
}

finalize_failure_diagnostics() {
  if ! scan_failure_diagnostics; then
    : > "$DIAG_FILE"
    printf '%s\n' 'DIAGNOSTIC ONLY — redaction/secret scan failed; raw section omitted.' > "$DIAG_FILE"
  fi
  echo "failure diagnostics saved (diagnostic only): $DIAG_FILE" >&2
}

collect_failure_diagnostics() {
  local primary_rc=$1 ids id count=0 service details state_file log_file inspect_format
  local found_id found_project found_service section_rc redact_rc
  local -a command_status
  PROJECT_IDS_VERIFIED=0
  PROJECT_CONTAINER_IDS=()
  SOURCE_CID="" TARGET_CID="" TOOL_CID="" SCHEDULER_CID="" REPLICA_CID=""
  if (( ! WORK_OWNED )) || [[ -z "$DIAG_FILE" ]]; then return 0; fi
  : > "$DIAG_FILE" || return 1
  diag_budget_start
  append_diagnostic_line "DIAGNOSTIC ONLY — not PITR or restore-drill success evidence"
  append_diagnostic_line "run=$RUN_ID project=$PROJECT stage=${FAILURE_STAGE:-unknown} native_exit=${FAILURE_NATIVE_RC:-$primary_rc} runner_exit=$primary_rc time_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  append_diagnostic_line "collector-limits advisory_budget_seconds=$DIAG_BUDGET_SECONDS per_call_timeout_seconds=$DIAG_CALL_TIMEOUT_SECONDS (advisory budget uses the wall-clock SECONDS counter; only per-call timeouts are hard bounds)"
  if [[ -n "$FAILURE_OUTPUT_FILE" && -f "$FAILURE_OUTPUT_FILE" ]]; then
    append_diagnostic_section "failing-command-output" "$FAILURE_OUTPUT_FILE"
  elif [[ -n "$FAILURE_OUTPUT_FILE" ]]; then
    append_diagnostic_line "failing-command-output=missing"
  fi
  if (( ! PROJECT_OWNED || ! upAttempted )); then
    append_diagnostic_line "collector=preflight failed before this run owned/attempted a Compose project; no Docker resources inspected"
    finalize_failure_diagnostics
    return 0
  fi
  if ! command -v timeout > /dev/null 2>&1; then
    append_diagnostic_line "collector=bounded timeout command unavailable; Docker diagnostics omitted"
    finalize_failure_diagnostics
    return 0
  fi

  ids=$(bounded_docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -aq 2>/dev/null | head -c 4096)
  section_rc=$?
  if (( section_rc != 0 )); then
    append_diagnostic_line "container-list=unavailable rc=$section_rc"
  else
    while IFS= read -r id; do
      [[ -n "$id" ]] || continue
      count=$((count + 1))
      if (( count > 5 )); then
        append_diagnostic_line "container-list=more than five entries; excess and cleanup omitted"
        break
      fi
      if ! [[ "$id" =~ ^[a-fA-F0-9]{12,64}$ ]]; then
        append_diagnostic_line "container-list=malformed entry omitted"
        continue
      fi
      if ! diag_call_limit > /dev/null; then
        append_diagnostic_line "container-validation=diagnostic budget exhausted"
        break
      fi
      details=$(bounded_docker inspect --format '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' "$id" 2>/dev/null | head -c 4096)
      section_rc=$?
      if (( section_rc != 0 )); then
        append_diagnostic_line "container=${id:0:12} ownership=unverified rc=$section_rc"
        continue
      fi
      IFS='|' read -r found_id found_project found_service <<< "$details"
      if [[ "${found_id:0:12}" != "${id:0:12}" || "$found_project" != "$PROJECT" ]] \
          || ! [[ "$found_service" =~ ^(source|target|tool|scheduler|replica)$ ]]; then
        append_diagnostic_line "container=${id:0:12} ownership=unverified omitted"
        continue
      fi
      PROJECT_CONTAINER_IDS+=("$id")
      case "$found_service" in
        source) SOURCE_CID=$id ;;
        target) TARGET_CID=$id ;;
        tool) TOOL_CID=$id ;;
        scheduler) SCHEDULER_CID=$id ;;
        replica) REPLICA_CID=$id ;;
      esac
    done <<< "$ids"
    if (( count <= 5 && ${#PROJECT_CONTAINER_IDS[@]} == count )); then PROJECT_IDS_VERIFIED=1; fi
  fi

  inspect_format='{{.Id}}|{{.State.Status}}|{{.State.Running}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.Error}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|{{if .State.Health}}{{.State.Health.Status}}{{range .State.Health.Log}}{{println}}health_start={{.Start}} health_exit={{.ExitCode}} health_output={{.Output}}{{end}}{{end}}|{{.Image}}{{range .Mounts}}{{println}}mount_type={{.Type}} source={{.Source}} destination={{.Destination}} rw={{.RW}}{{end}}'
  for service in target source tool scheduler replica; do
    case "$service" in
      target) id=$TARGET_CID ;;
      source) id=$SOURCE_CID ;;
      tool) id=$TOOL_CID ;;
      scheduler) id=$SCHEDULER_CID ;;
      replica) id=$REPLICA_CID ;;
    esac
    [[ -n "$id" ]] || continue
    if ! diag_call_limit > /dev/null; then
      append_diagnostic_line "$service-diagnostics=diagnostic budget exhausted; omitted"
      continue
    fi
    state_file="$WORK/.failure-state-$service"
    log_file="$WORK/.failure-log-$service"
    bounded_docker inspect --format "$inspect_format" "$id" 2>&1 \
      | redact_stream_to_file "$state_file" "$DIAG_SECTION_MAX_BYTES" bounded
    command_status=("${PIPESTATUS[@]}")
    section_rc=${command_status[0]:-1}
    redact_rc=${command_status[1]:-1}
    if (( redact_rc != 0 )); then
      printf '%s\n' '[state section redaction failed or timed out; content omitted]' > "$state_file"
    fi
    append_diagnostic_section "$service-state-health-image-mounts" "$state_file"
    append_diagnostic_line "$service-state-collector_rc=$section_rc redactor_rc=$redact_rc"
    if diag_call_limit > /dev/null; then
      bounded_docker logs --timestamps --tail 200 "$id" 2>&1 \
        | redact_stream_to_file "$log_file" "$DIAG_SECTION_MAX_BYTES" bounded
      command_status=("${PIPESTATUS[@]}")
      section_rc=${command_status[0]:-1}
      redact_rc=${command_status[1]:-1}
      if (( redact_rc != 0 )); then
        printf '%s\n' '[log section redaction failed or timed out; content omitted]' > "$log_file"
      fi
      append_diagnostic_section "$service-logs tail=200" "$log_file"
      append_diagnostic_line "$service-log-collector_rc=$section_rc redactor_rc=$redact_rc"
    else
      append_diagnostic_line "$service-logs=diagnostic budget exhausted; omitted"
    fi
    rm -f "$state_file" "$log_file"
  done

  finalize_failure_diagnostics
}

check_fixed_container_names() {
  local name ids rc
  for name in ses-bkup-int-source ses-bkup-int-target ses-bkup-int-tool ses-bkup-int-schedule ses-bkup-int-replica; do
    ids=$(docker ps -aq --filter "name=^/${name}$" 2>/dev/null)
    rc=$?
    if (( rc != 0 )) || [[ -n "$ids" ]]; then
      echo "FAIL: fixed container name is occupied or ownership cannot be checked: $name" >&2
      return 1
    fi
  done
}

init_diag_limits
INTEGRATION_SRC_PW=${INTEGRATION_SRC_PW:-"int-src-$(date +%s)-pw"}
INTEGRATION_TGT_PW=${INTEGRATION_TGT_PW:-"int-tgt-$(date +%s)-pw"}
if ! validate_work_path; then FAILURE_STAGE=work-preflight; exit 1; fi
export INTEGRATION_SRC_PW INTEGRATION_TGT_PW
if ! check_fixed_container_names; then FAILURE_STAGE=fixed-name-preflight; exit 1; fi
existing_project_ids=$(compose ps -aq 2>/dev/null)
existing_project_rc=$?
if (( existing_project_rc != 0 )) || [[ -n "$existing_project_ids" ]]; then
  FAILURE_STAGE=project-preflight
  echo "FAIL: generated Compose project is not fresh" >&2
  exit 1
fi
PROJECT_OWNED=1

echo "== compose up（pinned MySQL 8.0.36 ×2 + tool image build） =="
upAttempted=1
UP_OUTPUT_FILE="$WORK/.compose-up-output"
compose up -d --build 2>&1 | redact_stream_to_file "$UP_OUTPUT_FILE" 65536
UP_STATUS=("${PIPESTATUS[@]}")
UP_RC=${UP_STATUS[0]:-1}
UP_REDACT_RC=${UP_STATUS[1]:-1}
if (( UP_RC != 0 || UP_REDACT_RC != 0 )); then
  FAILURE_STAGE=compose-up
  FAILURE_NATIVE_RC=$UP_RC
  FAILURE_OUTPUT_FILE="$UP_OUTPUT_FILE"
  if (( UP_REDACT_RC != 0 )); then printf '%s\n' '[redactor failed; output omitted]' > "$UP_OUTPUT_FILE"; fi
  echo "FAIL: compose up" >&2
  if (( UP_RC != 0 )); then exit "$UP_RC"; else exit 1; fi
fi
rm -f "$UP_OUTPUT_FILE" "$UP_OUTPUT_FILE.bytes"
echo "== compose up --wait（healthcheck） =="
WAIT_OUTPUT_FILE="$WORK/.compose-wait-output"
compose up --wait -d 2>&1 | redact_stream_to_file "$WAIT_OUTPUT_FILE" 65536
WAIT_STATUS=("${PIPESTATUS[@]}")
WAIT_RC=${WAIT_STATUS[0]:-1}
WAIT_REDACT_RC=${WAIT_STATUS[1]:-1}
if (( WAIT_RC != 0 || WAIT_REDACT_RC != 0 )); then
  FAILURE_STAGE=compose-wait
  FAILURE_NATIVE_RC=$WAIT_RC
  FAILURE_OUTPUT_FILE="$WAIT_OUTPUT_FILE"
  if (( WAIT_REDACT_RC != 0 )); then printf '%s\n' '[redactor failed; output omitted]' > "$WAIT_OUTPUT_FILE"; fi
  echo "FAIL: コンテナが healthy になりませんでした" >&2
  if (( WAIT_RC != 0 )); then exit "$WAIT_RC"; else exit 1; fi
fi
rm -f "$WAIT_OUTPUT_FILE" "$WAIT_OUTPUT_FILE.bytes"

if ! refresh_project_containers || [[ -z "$SOURCE_CID" || -z "$TARGET_CID" ]] || ! verify_project_network; then
  FAILURE_STAGE=project-network-verification
  echo "FAIL: このrunのCompose container/network ownershipを確認できません" >&2
  exit 1
fi
echo "network: $NET"

# TLS capath を抽出（source/target の CA 証明書）
docker cp "$SOURCE_CID:/var/lib/mysql/ca.pem" "$WWORK/ca.pem" 2>/dev/null || { FAILURE_STAGE=source-ca-copy; exit 1; }
docker cp "$TARGET_CID:/var/lib/mysql/ca.pem" "$WWORK/ca-tgt.pem" 2>/dev/null || { FAILURE_STAGE=target-ca-copy; exit 1; }
CA_HASH=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  openssl x509 -in /work/ca.pem -noout -subject_hash 2>/dev/null)
CA_TGT_HASH=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  openssl x509 -in /work/ca-tgt.pem -noout -subject_hash 2>/dev/null)
[[ -n "$CA_HASH" ]] && cp "$WWORK/ca.pem" "$WORK/capath/$CA_HASH.0"
[[ -n "$CA_TGT_HASH" ]] && cp "$WWORK/ca-tgt.pem" "$WORK/capath-tgt/$CA_TGT_HASH.0"

echo "== integration-pitr.sh 実行（tool コンテナ内・実 MySQL PITR） =="
PITR_OUTPUT_FILE="$WORK/.integration-pitr-output"
docker run --rm --network "$NET" \
  -e SRC_HOST=ses-bkup-int-source -e TGT_HOST=ses-bkup-int-target \
  -e SRC_PASSWORD_FILE=/work/src-password -e TGT_PASSWORD_FILE=/work/tgt-password \
  -e TARGET_HOST=ses-bkup-int-target -e TARGET_PORT=3306 -e TARGET_USER=restore-svc \
  -e TARGET_PASSWORD_FILE=/work/restore-svc-pw -e TARGET_DATABASE=ses_manager_db \
  -e TARGET_ALLOWLIST_FILE=/work/target-allowlist.txt \
  -e APPROVAL_PUBKEY_DIR=/work/pubkeys \
  -e SOURCE_HOST=ses-bkup-int-source -e SOURCE_PORT=3306 \
  -e WORK_DIR=/work -e MYSQL_DATABASE=ses_manager_db \
  -e MYSQL_HOST=ses-bkup-int-source -e MYSQL_PORT=3306 -e MYSQL_USER=root \
  -e MYSQL_PASSWORD_FILE=/work/src-password \
  -e MYSQL_SSL_CAPATH=/work/capath -e MYSQL_TLS_MODE=VERIFY_CA \
  -e TARGET_SSL_CAPATH=/work/capath-tgt -e TARGET_TLS_MODE=VERIFY_CA \
  -e BACKUP_REPOSITORY=/work/repo -e RESTIC_PASSWORD_FILE=/work/repo-password \
  -e RESTIC_REPOSITORY=/work/repo \
  -e FULL_COORDINATE_FILE=/work/full-coordinate \
  -e BACKUP_WORK_DIR=/work/work -e PLANS_DIR=/work/plans -e UPLOADS_DIR=/work/uploads \
  -e UPLOADS_STAGING_PARENT=/work/staging \
  -e REPLICA_HEARTBEAT_DIR=/work/heartbeats -e SCHEDULER_ACK_DIR=/work/scheduler \
  -e QUIESCE_DEADLINE_SECONDS=60 -e QUIESCE_STALE_SECONDS=60 \
  -e REPOSITORY_LOCK_DIR=/work/locks \
  -e APP_COMMIT=integration -e FLYWAY_VERSION=42 \
  -e CRITICAL_TABLES=marker_test,t_report_run,t_report_section_snapshot,t_report_section_attempt,t_document,t_document_version,t_notification_outbox,t_report_delivery \
  -v "$WWORK:/work:rw" \
  ses-backup-tool:integration /usr/local/bin/integration-pitr.sh 2>&1 | redact_stream_to_file "$PITR_OUTPUT_FILE" 65536
PITR_STATUS=("${PIPESTATUS[@]}")
PITR_RC=${PITR_STATUS[0]:-1}
PITR_REDACT_RC=${PITR_STATUS[1]:-1}
if (( PITR_RC != 0 || PITR_REDACT_RC != 0 )); then
  FAILURE_STAGE=integration-pitr
  FAILURE_NATIVE_RC=$PITR_RC
  FAILURE_OUTPUT_FILE="$PITR_OUTPUT_FILE"
  if (( PITR_REDACT_RC != 0 )); then printf '%s\n' '[redactor failed; output omitted]' > "$PITR_OUTPUT_FILE"; fi
  echo "FAIL: integration-pitr.sh（rc=$PITR_RC）" >&2
  if (( PITR_RC != 0 )); then exit "$PITR_RC"; else exit 1; fi
fi
rm -f "$PITR_OUTPUT_FILE" "$PITR_OUTPUT_FILE.bytes"
normalize_evidence_permissions

echo "== restore-drill 実行（実環境に対する drill。RPO/RTO segment 記録） =="
PLAN_ID=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq -r '.plan_id' /work/integration-summary.json)
# BUG-06: --target 必須。plan.requested_target と一致させる（不一致は drill が非 0）
TARGET_TS=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq -r '.requested_target // empty' "/work/plans/${PLAN_ID}.json")
if [[ -z "$TARGET_TS" || "$TARGET_TS" == "null" ]]; then
  echo "FAIL: plan.requested_target を取得できません（plan_id=$PLAN_ID）" >&2
  exit 1
fi
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/drill-cutover-smoke.sh"
chmod +x "$WORK/drill-cutover-smoke.sh"
# drill の restore 用に target を初期化（target guard の空チェックを通す）
docker exec "$TARGET_CID" mysql -uroot -p"$INTEGRATION_TGT_PW" \
  -e "DROP DATABASE IF EXISTS ses_manager_db; CREATE DATABASE ses_manager_db;" > /dev/null 2>&1
DRILL_OUTPUT_FILE="$WORK/.restore-drill-output"
docker run --rm --network "$NET" \
  -e TARGET_HOST=ses-bkup-int-target -e TARGET_PORT=3306 -e TARGET_USER=restore-svc \
  -e TARGET_PASSWORD_FILE=/work/restore-svc-pw -e TARGET_DATABASE=ses_manager_db \
  -e TARGET_ALLOWLIST_FILE=/work/target-allowlist.txt \
  -e APPROVAL_PUBKEY_DIR=/work/pubkeys \
  -e INDEX_DIR=/work/work/index -e BINLOG_INDEX=/work/immutable/binlog-index.json \
  -e PLANS_DIR=/work/plans -e BACKUP_WORK_DIR=/work/work \
  -e SOURCE_HOST=ses-bkup-int-source -e SOURCE_PORT=3306 \
  -e RESTIC_PASSWORD_FILE=/work/repo-password -e RESTIC_REPOSITORY=/work/repo \
  -e BACKUP_REPOSITORY=/work/repo \
  -e TARGET_SSL_CAPATH=/work/capath-tgt -e TARGET_TLS_MODE=VERIFY_CA \
  -e APP_SMOKE_SCRIPT=/work/drill-cutover-smoke.sh \
  -e DRILL_SMOKE_SCRIPT=/work/smoke.sh \
  -e REPORT_RESTORE_EVIDENCE=/work/evidence/report-restore-contract.txt \
  -e RTO_SECONDS=14400 -e RPO_MAX_SECONDS=900 \
  -v "$WWORK:/work:rw" \
  ses-backup-tool:integration /usr/local/bin/restore-drill.sh \
  --target "$TARGET_TS" \
  --plan "$PLAN_ID" \
  --approval /work/claim-alice.json --approval /work/claim-bob.json \
  --report-dir /work/drill-evidence 2>&1 | redact_stream_to_file "$DRILL_OUTPUT_FILE" 65536
DRILL_STATUS=("${PIPESTATUS[@]}")
DRILL_RC=${DRILL_STATUS[0]:-1}
DRILL_REDACT_RC=${DRILL_STATUS[1]:-1}
if (( DRILL_RC != 0 || DRILL_REDACT_RC != 0 )); then
  FAILURE_STAGE=restore-drill
  FAILURE_NATIVE_RC=$DRILL_RC
  FAILURE_OUTPUT_FILE="$DRILL_OUTPUT_FILE"
  if (( DRILL_REDACT_RC != 0 )); then printf '%s\n' '[redactor failed; output omitted]' > "$DRILL_OUTPUT_FILE"; fi
  echo "FAIL: restore-drill（rc=$DRILL_RC）" >&2
  if (( DRILL_RC != 0 )); then exit "$DRILL_RC"; else exit 1; fi
fi
rm -f "$DRILL_OUTPUT_FILE" "$DRILL_OUTPUT_FILE.bytes"
normalize_evidence_permissions

docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq '{state, rpo_seconds, rto_seconds, total_seconds, rto_ok, rpo_ok, segments}' \
  /work/drill-evidence/drill-report.json | tee "$WORK/evidence/drill-report.json"
if ! docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq -e '.rto_ok == true and .rpo_ok == true' /work/drill-evidence/drill-report.json > /dev/null; then
  echo "FAIL: drill の RPO/RTO が目標内にない" >&2
  exit 1
fi

echo "== 結果検証 =="
SUMMARY="$WORK/integration-summary.json"
[[ -f "$SUMMARY" ]] || { echo "FAIL: summary がありません" >&2; exit 1; }
docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration jq -r . /work/integration-summary.json \
  | tee "$WORK/evidence/integration-summary.json"
STATE=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration jq -r '.state' /work/integration-summary.json)
if [[ "$STATE" != "SUCCESS" ]]; then
  echo "FAIL: state=$STATE" >&2
  exit 1
fi
for k in target_db.before_marker target_db.mid_dml_replayed target_db.after_marker \
  uploads_staging.before_marker uploads_staging.after_marker; do
  V=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration jq -r ".$k" /work/integration-summary.json)
  case "$k" in
    target_db.before_marker|target_db.mid_dml_replayed|uploads_staging.before_marker) [[ "$V" == "1" ]] || { echo "FAIL: $k=$V" >&2; exit 1; } ;;
    *) [[ "$V" == "0" ]] || { echo "FAIL: $k=$V" >&2; exit 1; } ;;
  esac
done

echo "== secret scan（evidence から合成パスワード・署名秘密鍵が漏れていないこと） =="
if grep -rl "$INTEGRATION_SRC_PW\|$INTEGRATION_TGT_PW\|PRIVATE KEY" "$WORK/evidence" 2>/dev/null \
  | grep -vE "src-password|tgt-password"; then
  echo "FAIL: evidence に secret が含まれます" >&2
  exit 1
fi
echo "secret scan: 0 matches"

echo "== evidence SHA =="
(cd "$WORK/evidence" && sha256sum integration-summary.json validate.json restore.log target-markers.txt uploads-markers.txt source-state.txt report-restore-contract.txt | tee "$WORK/evidence/evidence-sha.txt")
normalize_evidence_permissions

echo "== integration suite SUCCESS（skip 0・全ステップ実実行） =="
