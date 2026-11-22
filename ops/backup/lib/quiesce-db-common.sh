#!/usr/bin/env bash
# ============================================================
# 書込み静止の DB の protocol の共通部品（RB-QUIESCE。.kiro/specs/backup-write-quiesce/design.md §5・§7・§9）
#
# providers/quiesce-db.sh（ONLINE）と providers/quiesce-app-stopped.sh（APP_STOPPED。BWQ-010）と preflight.sh が source する。
# - SQL は providers/quiesce-db.d/*.sql の固定の file だけを実行する。値は検査した整数・hex・列挙だけを
#   「SET @p_<名前> = <値>;」の前置きで渡す（任意の文字列を SQL に埋め込まない。requested_by も hex にして渡す）。
#   時刻は全て UTC_TIMESTAMP(6) で書き・比べる（§3.10）。session の time_zone は +00:00 にする（値には効かない）。
# - 各 file の先頭行は fake の mysql が見分ける印（-- ses-backup:quiesce-db:<名前>）で、前置きはその後に置く。
# - 結果は mysql -N -B の tab 区切りで、各行の 1 欄目が行の種類（VERDICT・INSTANCE 等）。SQL は空の欄を作らない
#   （bash の read は連続する tab を 1 つの区切りと見るため、NULL・空を - にする）。
# - 判定（必要な instance の分類・ack・hold 期限・整合時刻）は SQL の VERDICT が出す。Java の MySQL 契約試験
#   （BackupQuiesceProviderContractMySqlTest）が同じ file を実 MySQL 8.4 で実行して固定する。shell は観測した接続元の照合
#   （文字列の分解）・時間の上限・state dir の印だけを見る。
#
# state dir の file（全て 0600）:
#   hold.pid・hold.log・hold.exited          保持 process（§7.6）の PID・出力・終わった印
#   ddl-lock.ok・ddl-lock.failed・ddl-lock.lost   DDL 凍結の lock を取った・取れなかった・取った後に失った印
#   ddl-session.pid・ddl-session.out         lock を持つ mysql の session の PID と出力
#   request.env                              登録した要求（acquire が INSERT の commit の後に書く。保持 process が延長を始める）
#   renew-stopped・renew-capped              延長が止まった（要求が有効でない）・延長の上限に達した印
#   full-aborted                             ONLINE の full の最中に新しい instance を見つけて要求を中止した印（U-13）
#   release.requested                        release が要求を解いた後に保持 process へ終わりを求める印
#   verified・verify-failed                  verify の成否（release の理由を決める）
#   quiesce.json                             静止区間の記録（metadata.json の quiesce に入る）
# ============================================================

QUIESCE_DB_PROTOCOL_VERSION=1
# DDL 凍結の lock を待つ秒数（Flyway の job が lock を持つ間は短く待って失敗する。Flyway の callback は 120 秒待つ）
QUIESCE_DB_LOCK_WAIT_SECONDS=30
QUIESCE_DB_LOCK_NAME=ses_backup_ddl_freeze

_QDB_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
QUIESCE_DB_SQL_DIR="$_QDB_LIB_DIR/providers/quiesce-db.d"
[[ -d "$QUIESCE_DB_SQL_DIR" ]] || QUIESCE_DB_SQL_DIR="$_QDB_LIB_DIR/../providers/quiesce-db.d"

quiesce_db::log() { echo "[quiesce-db] $*" >&2; }

quiesce_db::now() { date +%s; }

quiesce_db::is_posint() { [[ "$1" =~ ^[1-9][0-9]{0,6}$ ]]; }

# state dir に file を 0600 で書く（印と記録）
quiesce_db::mark() { # <path> [<内容>]
  local path=$1
  shift
  printf '%s\n' "${*:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}" > "$path.tmp" && chmod 600 "$path.tmp" && mv -f "$path.tmp" "$path"
}

# ------------------------------------------------------------ 設定（design.md §12.4）

# 設定を読み、検査する。QDB_* に値を置く。
quiesce_db::load_settings() { # <job_kind: FULL | CHECKPOINT>
  QDB_JOB_KIND=$1
  case "$QDB_JOB_KIND" in
    FULL)
      QDB_DEADLINE=${QUIESCE_DEADLINE_SECONDS:-660}
      QDB_LEASE_MAX=${QUIESCE_LEASE_MAX_SECONDS:-3600}
      QDB_FULL=1
      QDB_JOB_NAME=backup-full
      ;;
    CHECKPOINT)
      QDB_DEADLINE=${QUIESCE_DEADLINE_SECONDS:-90}
      QDB_LEASE_MAX=${QUIESCE_LEASE_MAX_SECONDS:-300}
      QDB_FULL=0
      QDB_JOB_NAME=create-checkpoint
      ;;
    *) quiesce_db::log "job の種類が不正です（FULL / CHECKPOINT）: ${QDB_JOB_KIND:-なし}"; return 2 ;;
  esac
  QDB_STALE=${QUIESCE_STALE_SECONDS:-60}
  QDB_DEAD=${QUIESCE_DEAD_SECONDS:-300}
  QDB_LEASE=${QUIESCE_LEASE_SECONDS:-60}
  QDB_RENEW=${QUIESCE_LEASE_RENEW_SECONDS:-15}
  QDB_MARGIN=${QUIESCE_LEASE_MARGIN_SECONDS:-5}
  QDB_OBSERVE=${QUIESCE_VERIFY_OBSERVE_SECONDS:-10}
  QDB_RESUME=${QUIESCE_RESUME_CONFIRM_SECONDS:-15}
  QDB_NEWINST=${QUIESCE_FULL_NEW_INSTANCE_CHECK_SECONDS:-5}
  local name value
  for name in QDB_DEADLINE QDB_LEASE_MAX QDB_STALE QDB_DEAD QDB_LEASE QDB_RENEW QDB_MARGIN QDB_OBSERVE QDB_RESUME QDB_NEWINST; do
    value=${!name}
    quiesce_db::is_posint "$value" || { quiesce_db::log "設定が正の整数ではありません: ${name/QDB_/QUIESCE_*}=$value"; return 1; }
  done
  (( QDB_STALE < QDB_DEAD )) \
    || { quiesce_db::log "QUIESCE_STALE_SECONDS（$QDB_STALE）は QUIESCE_DEAD_SECONDS（$QDB_DEAD）より小さくしてください"; return 1; }
  (( QDB_RENEW < QDB_LEASE )) \
    || { quiesce_db::log "QUIESCE_LEASE_RENEW_SECONDS（$QDB_RENEW）は QUIESCE_LEASE_SECONDS（$QDB_LEASE）より小さくしてください"; return 1; }
  (( QDB_MARGIN < QDB_LEASE )) \
    || { quiesce_db::log "QUIESCE_LEASE_MARGIN_SECONDS（$QDB_MARGIN）は QUIESCE_LEASE_SECONDS（$QDB_LEASE）より小さくしてください"; return 1; }
  local host
  host=$(hostname 2>/dev/null || echo unknown)
  host=${host//[^A-Za-z0-9._:-]/_}
  QDB_REQUESTED_BY=${QUIESCE_REQUESTED_BY:-$QDB_JOB_NAME:$host}
  QDB_REQUESTED_BY=${QDB_REQUESTED_BY:0:64}
  [[ "$QDB_REQUESTED_BY" =~ ^[A-Za-z0-9._:-]{1,64}$ ]] \
    || { quiesce_db::log "QUIESCE_REQUESTED_BY の形式が不正です（^[A-Za-z0-9._:-]{1,64}$。個人名・秘密を書かない）"; return 1; }
  return 0
}

# 接続の設定（option file。MYSQL_PWD は使わない）
quiesce_db::init_mysql() {
  [[ -n "${MYSQL_HOST:-}" ]] || { quiesce_db::log "MYSQL_HOST が未設定です"; return 1; }
  [[ -n "${MYSQL_DATABASE:-}" ]] || { quiesce_db::log "MYSQL_DATABASE が未設定です"; return 1; }
  MYSQL_PORT=${MYSQL_PORT:-3306}
  MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
  if [[ -z "${MYSQL_OPTFILE:-}" || ! -f "${MYSQL_OPTFILE:-}" ]]; then
    mysql_options::init || { quiesce_db::log "mysql option file を作成できません（接続設定を確認してください）"; return 1; }
  fi
  return 0
}

# ------------------------------------------------------------ SQL の実行

# 引数の 1 つを「SET @p_<名前> = <値>;」にする。型: int（0 以上の整数）・hex（小文字の 16 進）・enum（名前ごとの固定の集合）
quiesce_db::param_line() { # <型>:<名前>=<値>
  local spec=$1 type name value
  type=${spec%%:*}
  spec=${spec#*:}
  name=${spec%%=*}
  value=${spec#*=}
  [[ "$name" =~ ^[a-z][a-z0-9_]{0,40}$ ]] || { quiesce_db::log "SQL の引数名が不正です: $name"; return 1; }
  case "$type" in
    int)
      [[ "$value" =~ ^[0-9]{1,19}$ ]] || { quiesce_db::log "SQL の引数が整数ではありません: $name"; return 1; }
      printf 'SET @p_%s = %s;\n' "$name" "$value"
      ;;
    hex)
      [[ "$value" =~ ^[0-9a-f]{2,256}$ && $(( ${#value} % 2 )) -eq 0 ]] \
        || { quiesce_db::log "SQL の引数が hex ではありません: $name"; return 1; }
      printf "SET @p_%s = '%s';\n" "$name" "$value"
      ;;
    enum)
      case "$name:$value" in
        mode:ONLINE|mode:APP_STOPPED|job_kind:FULL|job_kind:CHECKPOINT|status:RELEASED|status:ABORTED \
          |reason:COMPLETED|reason:ABORTED|reason:VERIFY_FAILED) ;;
        *) quiesce_db::log "SQL の引数が列挙の外です: $name=$value"; return 1 ;;
      esac
      printf "SET @p_%s = '%s';\n" "$name" "$value"
      ;;
    *) quiesce_db::log "SQL の引数の型が不正です: $type"; return 1 ;;
  esac
}

# providers/quiesce-db.d/<名前>.sql を実行し、結果（tab 区切り）を標準出力へ出す。標準エラーは redact して（password の値と
# `password=` 等の形を伏せ、400 文字までにして）QDB_SQL_ERROR に入れる。QDB_SQL_ERROR は呼んだ shell にだけ残る（command
# substitution の中で呼ぶと親には届かない。2d review P2-1）ため、provider・preflight・保持 process は quiesce_db::query で呼ぶ。
quiesce_db::run_sql() { # <名前> [<型>:<名前>=<値>...]
  local name=$1 file sqlin errf rc=0 p line prefix=""
  shift
  QDB_SQL_ERROR=""
  [[ "$name" =~ ^[a-z][a-z0-9-]{0,40}$ ]] || { quiesce_db::log "SQL の名前が不正です: $name"; return 2; }
  file="$QUIESCE_DB_SQL_DIR/$name.sql"
  [[ -f "$file" ]] || { quiesce_db::log "SQL の file がありません: $file"; return 2; }
  for p in "$@"; do
    line=$(quiesce_db::param_line "$p") || return 2
    prefix+="$line"$'\n'
  done
  sqlin=$(mktemp "${TMPDIR:-/tmp}/ses-quiesce-db-sql.XXXXXX") || return 2
  errf=$(mktemp "${TMPDIR:-/tmp}/ses-quiesce-db-err.XXXXXX") || { rm -f "$sqlin"; return 2; }
  {
    head -n 1 "$file"
    printf "SET SESSION time_zone = '+00:00';\n"
    printf 'SET SESSION innodb_lock_wait_timeout = 5;\n'
    printf '%s' "$prefix"
    tail -n +2 "$file"
  } > "$sqlin" || { rm -f "$sqlin" "$errf"; return 2; }
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" --connect-timeout=10 -N -B \
    "$MYSQL_DATABASE" < "$sqlin" 2> "$errf" || rc=$?
  if [[ -s "$errf" ]]; then
    QDB_SQL_ERROR=$(quiesce_db::redacted_error "$errf")
  fi
  rm -f "$sqlin" "$errf"
  return "$rc"
}

# mysql の標準エラーの file を 1 行にし、password の値（MYSQL_PASSWORD_FILE の内容）と `password=`・`secret:` 等の形を伏せ、
# 伏せた後に 400 文字までにする（伏せる前に切ると値の一部が残り得るため）
quiesce_db::redacted_error() { # <file>
  local text pw=""
  text=$(tr '\n\r\t' '   ' < "$1" 2>/dev/null || true)
  if [[ -n "${MYSQL_PASSWORD_FILE:-}" && -r "${MYSQL_PASSWORD_FILE:-}" ]]; then
    IFS= read -r pw < "$MYSQL_PASSWORD_FILE" || true
  fi
  [[ -n "$pw" ]] && text=${text//"$pw"/<redacted>}
  text=$(common::redact <<< "$text")
  printf '%s\n' "${text:0:400}"
}

# run_sql を呼んだ shell の中で実行し（subshell にしない）、結果を <変数名> に、redact した標準エラーを QDB_SQL_ERROR に入れる。
# 結果は command substitution と同じく末尾の改行を除く。終了コードは run_sql のもの。<変数名> は呼出し側で local にしておく。
quiesce_db::query() { # <変数名> <名前> [<型>:<名前>=<値>...]
  local __qdb_var=$1 __qdb_out __qdb_val __qdb_rc=0
  shift
  [[ "$__qdb_var" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || { quiesce_db::log "結果の変数名が不正です: $__qdb_var"; return 2; }
  QDB_SQL_ERROR=""
  __qdb_out=$(mktemp "${TMPDIR:-/tmp}/ses-quiesce-db-out.XXXXXX") || { QDB_SQL_ERROR="結果の一時 file を作れません"; return 2; }
  quiesce_db::run_sql "$@" > "$__qdb_out" || __qdb_rc=$?
  __qdb_val=$(cat "$__qdb_out" 2>/dev/null || true)
  rm -f "$__qdb_out"
  printf -v "$__qdb_var" '%s' "$__qdb_val"
  return "$__qdb_rc"
}

# 結果のうち 1 欄目が <tag> の最初の行の <n> 番目の欄（1 始まり。tag 自身が 1）。無ければ 1 を返す
quiesce_db::col() { # <結果> <tag> <n>
  local line
  local -a f=()
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    if [[ "${f[0]:-}" == "$2" ]]; then
      printf '%s\n' "${f[$(( $3 - 1 ))]:-}"
      return 0
    fi
  done <<< "$1"
  return 1
}

# 結果の VERDICT の行を QDB_VERDICT・QDB_NOW_UTC・QDB_NOW_EPOCH_US・QDB_COUNT・QDB_DETAIL に読む
quiesce_db::read_verdict() { # <結果>
  local line
  local -a f=()
  QDB_VERDICT="" QDB_NOW_UTC="" QDB_NOW_EPOCH_US="" QDB_COUNT="" QDB_DETAIL=""
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    if [[ "${f[0]:-}" == VERDICT ]]; then
      QDB_VERDICT=${f[1]:-}
      QDB_NOW_UTC=${f[2]:-}
      QDB_NOW_EPOCH_US=${f[3]:-}
      QDB_COUNT=${f[4]:-}
      QDB_DETAIL=${f[5]:-}
      break
    fi
  done <<< "$1"
  [[ "$QDB_VERDICT" =~ ^(OK|WAIT|FAIL_[A-Z_]+)$ && "$QDB_NOW_UTC" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ \
     && "$QDB_NOW_EPOCH_US" =~ ^[0-9]{1,19}$ ]] \
    || { quiesce_db::log "判定の行（VERDICT）を読めません"; return 1; }
  return 0
}

# INSTANCE の行のうち、分類が <分類の正規表現> に合うものを「instance_id（host 名、ack 状態、heartbeat の古さ ms）」の
# 一覧にする（message 用）
quiesce_db::instances_text() { # <結果> <分類の正規表現>
  local line text=""
  local -a f=()
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    [[ "${f[0]:-}" == INSTANCE && "${f[2]:-}" =~ $2 ]] || continue
    local reason=""
    [[ "${f[6]:--}" != - ]] && reason="/${f[6]}"
    text+="${text:+, }${f[1]}（${f[7]:-}、${f[2]}、ack=${f[5]:-}${reason}、heartbeat ${f[15]:-?} ms 前、${f[16]:-}/${f[17]:-}）"
  done <<< "$1"
  printf '%s\n' "${text:-なし}"
}

# INSTANCE の行のうち、分類が <分類の正規表現> に合うものを「<件数> 件（instance_id, …）」にする。host 名・接続元を出さない
# （preflight の JSON の理由に使う。2d review P3-4）
quiesce_db::instance_ids_text() { # <結果> <分類の正規表現>
  local line ids="" n=0
  local -a f=()
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    [[ "${f[0]:-}" == INSTANCE && "${f[2]:-}" =~ $2 ]] || continue
    n=$((n + 1))
    ids+="${ids:+, }${f[1]}"
  done <<< "$1"
  printf '%s 件（%s）\n' "$n" "${ids:-なし}"
}

# 観測した接続元が、ack（QUIESCED）した必要な instance の接続元（db_client_host）だけであることを確かめる（§5.4）。
# 外れた host を「,」区切りで出力する（全て含まれれば空）。観測の値は「host=件数」を「,」でつないだもの（OVERFLOW=n を含む）。
quiesce_db::foreign_hosts() { # <結果>
  local line entry host out=""
  local -a f=() entries=()
  local -A allowed=()
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    [[ "${f[0]:-}" == INSTANCE && "${f[2]:-}" == REQUIRED && "${f[5]:-}" == QUIESCED ]] || continue
    allowed["${f[8]}"]=1
  done <<< "$1"
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    [[ "${f[0]:-}" == INSTANCE && "${f[2]:-}" == REQUIRED && "${f[5]:-}" == QUIESCED ]] || continue
    [[ "${f[12]:-}" == - ]] && continue
    IFS=',' read -r -a entries <<< "${f[12]}"
    for entry in "${entries[@]}"; do
      [[ -n "$entry" ]] || continue
      host=${entry%=*}
      if [[ -z "${allowed[$host]:-}" && ",$out," != *",$host,"* ]]; then
        out+="${out:+,}$host"
      fi
    done
  done <<< "$1"
  printf '%s\n' "$out"
}

# INSTANCE の行のうち必要な instance を JSON の配列にする（quiesce.json の instances）
quiesce_db::instances_json() { # <結果>
  local line json='[]'
  local -a f=()
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    [[ "${f[0]:-}" == INSTANCE && "${f[2]:-}" == REQUIRED ]] || continue
    json=$(jq -c --arg id "${f[1]}" --arg host "${f[7]}" --arg client "${f[8]}" --arg started "${f[9]}" \
      --arg acked "${f[10]}" --arg hold "${f[11]}" --arg before "${f[4]}" --arg ack "${f[5]}" \
      '. + [{instance_id: $id, host_name: $host, db_client_host: $client, started_at_utc: $started,
             acked_at_utc: (if $acked == "-" then null else $acked end),
             hold_until_utc: (if $hold == "-" then null else $hold end),
             ack_state: (if $ack == "-" then null else $ack end),
             registered_before_request: ($before == "1")}]' <<< "$json") || return 1
  done <<< "$1"
  printf '%s\n' "$json"
}

# 必要な instance の最大保持時間が QUIESCE_LEASE_MAX_SECONDS より短ければ WARN（延長の上限は小さい方になる。D-P3-6）
quiesce_db::warn_short_max_hold() { # <結果>
  local line
  local -a f=()
  while IFS= read -r line; do
    IFS=$'\t' read -r -a f <<< "$line"
    [[ "${f[0]:-}" == INSTANCE && "${f[2]:-}" == REQUIRED && "${f[14]:-}" =~ ^[0-9]+$ ]] || continue
    if (( f[14] / 1000 < QDB_LEASE_MAX )); then
      quiesce_db::log "WARN: instance ${f[1]} の最大保持時間（$(( f[14] / 1000 )) 秒）が QUIESCE_LEASE_MAX_SECONDS（$QDB_LEASE_MAX 秒）より短いため、延長の上限は前者になります"
    fi
  done <<< "$1"
}

# 16 byte の乱数の hex（要求の token）
quiesce_db::random_token() {
  local t
  t=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  [[ "$t" =~ ^[0-9a-f]{32}$ ]] || return 1
  printf '%s\n' "$t"
}

quiesce_db::hex_of() { # <文字列> → UTF-8 の hex
  printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'
}

# ------------------------------------------------------------ 要求の記録（state dir）

quiesce_db::write_request_env() { # <state_dir> <id> <mode> <job_kind> <token> <requested_at> <requested_epoch_us>
  quiesce_db::mark "$1/request.env" "$(printf 'request_id=%s\nmode=%s\njob_kind=%s\ntoken=%s\nrequested_at_utc=%s\nrequested_epoch_us=%s' \
    "$2" "$3" "$4" "$5" "$6" "$7")"
}

# request.env を QDB_REQUEST_* に読む（値を検査する。source しない）
quiesce_db::load_request_env() { # <state_dir>
  local file="$1/request.env" key value
  QDB_REQUEST_ID="" QDB_REQUEST_MODE="" QDB_REQUEST_KIND="" QDB_REQUEST_TOKEN="" QDB_REQUESTED_AT="" QDB_REQUESTED_EPOCH_US=""
  [[ -f "$file" ]] || return 1
  while IFS='=' read -r key value; do
    case "$key" in
      request_id) QDB_REQUEST_ID=$value ;;
      mode) QDB_REQUEST_MODE=$value ;;
      job_kind) QDB_REQUEST_KIND=$value ;;
      token) QDB_REQUEST_TOKEN=$value ;;
      requested_at_utc) QDB_REQUESTED_AT=$value ;;
      requested_epoch_us) QDB_REQUESTED_EPOCH_US=$value ;;
    esac
  done < "$file"
  [[ "$QDB_REQUEST_ID" =~ ^[0-9]{1,19}$ && "$QDB_REQUEST_MODE" =~ ^(ONLINE|APP_STOPPED)$ \
     && "$QDB_REQUEST_KIND" =~ ^(FULL|CHECKPOINT)$ && "$QDB_REQUEST_TOKEN" =~ ^[0-9a-f]{32}$ ]] \
    || { quiesce_db::log "request.env の内容が不正です: $file"; return 1; }
  return 0
}

# 保持 process が DDL 凍結の lock を取ったことを state dir の印で確かめる（§7.1 の 2・§9.2 の 1）
quiesce_db::wait_ddl_lock() { # <state_dir>
  local d=$1 deadline
  deadline=$(( $(quiesce_db::now) + QUIESCE_DB_LOCK_WAIT_SECONDS + 15 ))
  while :; do
    if [[ -f "$d/ddl-lock.ok" ]]; then
      return 0
    fi
    if [[ -f "$d/ddl-lock.failed" ]]; then
      quiesce_db::log "DDL 凍結の lock（$QUIESCE_DB_LOCK_NAME）を取得できません（$(cat "$d/ddl-lock.failed" 2>/dev/null)）。Flyway の migration・DBA の DDL が実行中の可能性があります。要求は登録しません"
      return 1
    fi
    if [[ -f "$d/hold.exited" ]]; then
      quiesce_db::log "保持 process が DDL 凍結の lock を取る前に終わりました（hold.log を確かめてください）。要求は登録しません"
      return 1
    fi
    if (( $(quiesce_db::now) > deadline )); then
      quiesce_db::log "保持 process が DDL 凍結の lock の結果を返しません。要求は登録しません"
      return 1
    fi
    sleep 0.2
  done
}

# 静止区間の途中で保持 process が残した異常の印（verify・acquire の待ちで見る）。異常が無ければ 0、あれば理由を出して 1
quiesce_db::hold_problem() { # <state_dir>
  local d=$1
  if [[ -f "$d/full-aborted" ]]; then
    echo "full の最中に新しい app の instance が登録されたため、要求を中止しました（errorCode=BACKUP_QUIESCE_FULL_ABORTED。U-13）"
    return 1
  fi
  if [[ -f "$d/ddl-lock.lost" ]]; then
    echo "DDL 凍結の lock の session を失いました（静止区間の DDL 凍結を保証できません）"
    return 1
  fi
  if [[ -f "$d/renew-stopped" ]]; then
    echo "保持 process の lease の延長が止まりました（$(cat "$d/renew-stopped" 2>/dev/null)）"
    return 1
  fi
  if [[ -f "$d/hold.exited" ]]; then
    echo "保持 process が終わっています（lease を延ばせません）"
    return 1
  fi
  return 0
}

# ------------------------------------------------------------ 保持 process（design.md §7.6。D-P2-2・D3-P3-2）

# 保持 process の本体。stdin は job 本体が持つ pipe の読み口で、EOF（job 本体の終わり・SIGKILL を含む）か release の印で終わる。
# DDL 凍結の lock の session（mysql）を左の loop から pipe で持ち、終わる時に session を閉じる（MySQL が lock を返す）。
quiesce_db::hold() { # <state_dir> <job_kind>
  local d=$1 rc=0
  [[ -d "$d" ]] || { quiesce_db::log "state dir がありません: $d"; return 2; }
  quiesce_db::mark "$d/hold.pid" "$$"
  quiesce_db::log "保持 process を始めます（pid $$）"
  if ! quiesce_db::load_settings "$2" || ! quiesce_db::init_mysql; then
    quiesce_db::mark "$d/ddl-lock.failed" "設定・接続の準備に失敗"
    quiesce_db::mark "$d/hold.exited"
    return 1
  fi
  quiesce_db::hold_loop "$d" | quiesce_db::ddl_session "$d" || rc=$?
  quiesce_db::log "保持 process を終えます（DDL 凍結の lock を返しました。rc=$rc）"
  quiesce_db::mark "$d/hold.exited"
  return 0
}

# DDL 凍結の lock の session（pipe の右）。標準入力の SQL を順に実行し、結果を ddl-session.out へ書く。
# --skip-reconnect: 接続が切れたら繋ぎ直さずに終わる（繋ぎ直した session は lock を持たないため。2d review P3-5）
quiesce_db::ddl_session() { # <state_dir>
  printf '%s\n' "$BASHPID" > "$1/ddl-session.pid"
  exec "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" --connect-timeout=10 -N -B --unbuffered \
    --skip-reconnect "$MYSQL_DATABASE" > "$1/ddl-session.out" 2>> "$1/hold.log"
}

quiesce_db::session_alive() { # <pid>
  [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null
}

# DDL の session の最後の行が lock の確認（DDL_HELD）なら、その値（1 = この session が lock を持つ、0 = 持たない）を出す。
# まだ確認の結果が無ければ何も出さない（session が書くのは DDL_LOCK の 1 行と DDL_HELD の行だけ）
quiesce_db::last_ddl_held() { # <state_dir>
  local last
  last=$(tail -n 1 "$1/ddl-session.out" 2>/dev/null || true)
  if [[ "$last" == DDL_HELD$'\t'* ]]; then
    printf '%s\n' "${last#DDL_HELD$'\t'}"
  fi
  return 0
}

# 保持 process の loop（pipe の左）。標準出力は DDL の session への pipe で、診断は全て hold.log（標準エラー）へ書く。
quiesce_db::hold_loop() { # <state_dir>
  local d=$1 ddl_fd line line_rc lock=WAITING session_pid="" now lock_deadline res out sql_rc held
  local request_id="" renewing=0 checking=0 renew_due=0 check_due=0
  local -a lease=()
  exec {ddl_fd}>&1 1>&2
  trap '' PIPE
  # lock を取る文（session の pipe へ流す。印・前置き・本体の順）
  {
    head -n 1 "$QUIESCE_DB_SQL_DIR/ddl-lock.sql"
    printf 'SET @p_lock_wait_seconds = %s;\n' "$QUIESCE_DB_LOCK_WAIT_SECONDS"
    tail -n +2 "$QUIESCE_DB_SQL_DIR/ddl-lock.sql"
  } >&"$ddl_fd" 2>/dev/null || true
  lock_deadline=$(( $(quiesce_db::now) + QUIESCE_DB_LOCK_WAIT_SECONDS + 10 ))
  while :; do
    # job 本体の pipe を 1 秒待つ（DDL lock の待ちの間も読む。job 本体は何も書かないため、読めた行は捨てる）
    line_rc=0
    IFS= read -r -t 1 line || line_rc=$?
    if (( line_rc != 0 && line_rc <= 128 )); then
      quiesce_db::log "job 本体の pipe が閉じたため終わります"
      break
    fi
    if [[ -f "$d/release.requested" ]]; then
      quiesce_db::log "release が要求を解いたため終わります"
      break
    fi
    now=$(quiesce_db::now)
    [[ -n "$session_pid" ]] || session_pid=$(cat "$d/ddl-session.pid" 2>/dev/null || true)
    case "$lock" in
      WAITING)
        res=$(quiesce_db::col "$(cat "$d/ddl-session.out" 2>/dev/null || true)" DDL_LOCK 2 || true)
        if [[ "$res" == 1 ]]; then
          lock=HELD
          quiesce_db::mark "$d/ddl-lock.ok"
          quiesce_db::log "DDL 凍結の lock（$QUIESCE_DB_LOCK_NAME）を取りました"
        elif [[ -n "$res" ]]; then
          quiesce_db::mark "$d/ddl-lock.failed" "GET_LOCK=$res"
          quiesce_db::log "DDL 凍結の lock を取れません（GET_LOCK=$res）"
          break
        elif ! quiesce_db::session_alive "$session_pid" && [[ -n "$session_pid" ]]; then
          quiesce_db::mark "$d/ddl-lock.failed" "session が終わった"
          quiesce_db::log "DDL 凍結の lock の session が結果を返さずに終わりました"
          break
        elif (( now > lock_deadline )); then
          quiesce_db::mark "$d/ddl-lock.failed" "応答なし"
          quiesce_db::log "DDL 凍結の lock の結果が返りません"
          break
        fi
        ;;
      HELD)
        # 毎秒、この session がまだ lock を持つことを確かめる（2d review P3-5）。session は --skip-reconnect で、接続が切れたら
        # mysql が終わる。繋ぎ直しで lock を失ったまま文が通る場合にも気付くよう、IS_USED_LOCK と CONNECTION_ID を比べる。
        # 前の確認の結果が 1 でない・session が終わった・文を送れない場合は lock を失ったとみなし、印を書いて保持 process を
        # 終える（verify は hold_problem で失敗し、app は lease の期限で再開する）
        held=$(quiesce_db::last_ddl_held "$d")
        if [[ -n "$held" && "$held" != 1 ]] || ! quiesce_db::session_alive "$session_pid" \
          || ! printf "SELECT 'DDL_HELD', COALESCE(IS_USED_LOCK('%s') = CONNECTION_ID(), 0);\n" "$QUIESCE_DB_LOCK_NAME" \
            >&"$ddl_fd" 2>/dev/null; then
          lock=LOST
          quiesce_db::mark "$d/ddl-lock.lost" "${held:+IS_USED_LOCK の確認=$held}"
          quiesce_db::log "ERROR: DDL 凍結の lock を失いました（${held:+この session が lock を持たない（$held）。}session の終わり・文の送信の失敗を含む。この静止区間の backup は VALID にしません。保持 process を終えます）"
          break
        fi
        ;;
    esac
    if [[ -z "$request_id" && -f "$d/request.env" ]] && quiesce_db::load_request_env "$d"; then
      request_id=$QDB_REQUEST_ID
      renewing=1
      renew_due=$(( now + QDB_RENEW ))
      check_due=$(( now + QDB_NEWINST ))
      # U-13 の中止は ONLINE の full だけ（APP_STOPPED の full と checkpoint は中止しない。D3-P2-2）
      [[ "$QDB_REQUEST_MODE" == ONLINE && "$QDB_REQUEST_KIND" == FULL ]] && checking=1
      quiesce_db::log "要求（番号 $request_id、$QDB_REQUEST_MODE、$QDB_REQUEST_KIND）の lease を $QDB_RENEW 秒ごとに延ばします（上限 $QDB_LEASE_MAX 秒）"
    fi
    if (( renewing == 1 && now >= renew_due )); then
      renew_due=$(( now + QDB_RENEW ))
      sql_rc=0
      quiesce_db::query out renew "int:request_id=$request_id" "int:lease_max_seconds=$QDB_LEASE_MAX" \
        "int:dead_seconds=$QDB_DEAD" {ddl_fd}>&- || sql_rc=$?
      if (( sql_rc != 0 )); then
        quiesce_db::log "WARN: lease を延ばせませんでした（次の延長で再試行します）: ${QDB_SQL_ERROR:-rc=$sql_rc}"
      elif [[ "$(quiesce_db::col "$out" RENEW 2 || true)" != 1 ]]; then
        IFS=$'\t' read -r -a lease <<< "$(quiesce_db::row "$out" LEASE || true)"
        if [[ "${lease[1]:-}" == REQUESTED && "${lease[2]:-}" == 1 && "${lease[3]:-}" == 1 ]]; then
          quiesce_db::mark "$d/renew-capped" "上限 ${lease[4]:-?} 秒、lease_expires_at=${lease[5]:-?}"
          quiesce_db::log "WARN: lease の延長の上限（requested_at から ${lease[4]:-?} 秒）に達したため延長を止めます"
        else
          quiesce_db::mark "$d/renew-stopped" "要求の状態=${lease[1]:-不明}、lease の有効=${lease[2]:-不明}"
          quiesce_db::log "lease を延ばせない要求のため延長を止めます（状態=${lease[1]:-不明}、lease の有効=${lease[2]:-不明}）"
        fi
        renewing=0
      fi
    fi
    if (( checking == 1 && now >= check_due )); then
      check_due=$(( now + QDB_NEWINST ))
      sql_rc=0
      quiesce_db::query out full-new-instance "int:request_id=$request_id" {ddl_fd}>&- || sql_rc=$?
      if (( sql_rc != 0 )); then
        quiesce_db::log "WARN: full の最中の新しい instance を調べられませんでした（次の確認で再試行します）: ${QDB_SQL_ERROR:-rc=$sql_rc}"
      elif [[ "$(quiesce_db::col "$out" ABORT 2 || true)" == 1 ]]; then
        quiesce_db::mark "$d/full-aborted" "新しい instance $(quiesce_db::col "$out" ABORT 3 || true) 件"
        quiesce_db::log "WARN: errorCode=BACKUP_QUIESCE_FULL_ABORTED full の最中に新しい app の instance が登録されたため、要求（番号 $request_id）を中止しました（NEW_INSTANCE。U-13）。その日の full は取り直します"
        renewing=0
        checking=0
      fi
    fi
  done
  # session を閉じる（EOF で mysql が終わり lock を返す。GET_LOCK の待ちの間は EOF を読まないため止める）
  exec {ddl_fd}>&-
  if [[ -z "$session_pid" ]]; then
    session_pid=$(cat "$d/ddl-session.pid" 2>/dev/null || true)
  fi
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    quiesce_db::session_alive "$session_pid" || break
    sleep 0.2
  done
  if quiesce_db::session_alive "$session_pid"; then
    kill "$session_pid" 2>/dev/null || true
  fi
  return 0
}

# 結果のうち 1 欄目が <tag> の最初の行（そのまま）
quiesce_db::row() { # <結果> <tag>
  local line
  while IFS= read -r line; do
    if [[ "${line%%$'\t'*}" == "$2" ]]; then
      printf '%s\n' "$line"
      return 0
    fi
  done <<< "$1"
  return 1
}

# ------------------------------------------------------------ 解除（design.md §7.3）

# 要求を解く（有効な要求だけ）。release の理由は verify の成否で決める。DB に届かなければ 1（lease の期限で app は再開する）
quiesce_db::release_request() { # <state_dir>
  local d=$1 status=ABORTED reason=ABORTED out rc=0
  quiesce_db::load_request_env "$d" || return 0
  if [[ -f "$d/verified" ]]; then
    status=RELEASED
    reason=COMPLETED
  elif [[ -f "$d/verify-failed" ]]; then
    reason=VERIFY_FAILED
  fi
  quiesce_db::query out release "int:request_id=$QDB_REQUEST_ID" "enum:status=$status" "enum:reason=$reason" || rc=$?
  if (( rc != 0 )); then
    quiesce_db::log "重大: 静止要求（番号 $QDB_REQUEST_ID）を解除できません（app は lease の期限で自動で再開します）: ${QDB_SQL_ERROR:-rc=$rc}"
    return 1
  fi
  local -a st=()
  IFS=$'\t' read -r -a st <<< "$(quiesce_db::row "$out" REQUEST_STATE || true)"
  QDB_RELEASED_STATUS=${st[1]:-unknown}
  QDB_RELEASED_REASON=${st[2]:-unknown}
  QDB_RELEASED_AT=${st[3]:--}
  if [[ "$(quiesce_db::col "$out" RELEASE 2 || true)" == 1 ]]; then
    quiesce_db::log "静止要求（番号 $QDB_REQUEST_ID）を解除しました（$status、$reason）"
  else
    quiesce_db::log "静止要求（番号 $QDB_REQUEST_ID）は既に解かれていました（$QDB_RELEASED_STATUS、$QDB_RELEASED_REASON）"
  fi
  if [[ -f "$d/quiesce.json" ]]; then
    jq --arg t "$QDB_RELEASED_AT" --arg s "$QDB_RELEASED_STATUS" --arg r "$QDB_RELEASED_REASON" \
      '.released_at_utc = (if $t == "-" then null else $t end) | .release_status = $s | .release_reason = $r' \
      "$d/quiesce.json" > "$d/quiesce.json.tmp" && chmod 600 "$d/quiesce.json.tmp" && mv -f "$d/quiesce.json.tmp" "$d/quiesce.json"
  fi
  return 0
}

# 保持 process に終わりを求め、終わり（DDL lock の返却）を待つ（job 本体が pipe を閉じる前に終わらせる。§7.3）
quiesce_db::stop_hold() { # <state_dir>
  local d=$1 i
  [[ -f "$d/hold.pid" ]] || return 0
  quiesce_db::mark "$d/release.requested"
  for (( i = 0; i < 150; i++ )); do
    [[ -f "$d/hold.exited" ]] && return 0
    sleep 0.2
  done
  quiesce_db::log "WARN: 保持 process が 30 秒で終わりません（job 本体が pipe を閉じると終わります）"
  return 0
}

# 再開の確認（ONLINE。§7.3）: この要求に QUIESCED の ack を持ち heartbeat が新しい instance の ack が RESUMED になるのを待つ。
# 確かめられなければ 3（重大）
quiesce_db::confirm_resumed() { # <state_dir>
  local d=$1 out deadline rc line waiting="" down=""
  local -a f=()
  quiesce_db::load_request_env "$d" || return 0
  deadline=$(( $(quiesce_db::now) + QDB_RESUME ))
  while :; do
    rc=0
    quiesce_db::query out resume-check "int:request_id=$QDB_REQUEST_ID" "int:stale_seconds=$QDB_STALE" || rc=$?
    if (( rc == 0 )) && quiesce_db::read_verdict "$out"; then
      waiting="" down=""
      while IFS= read -r line; do
        IFS=$'\t' read -r -a f <<< "$line"
        [[ "${f[0]:-}" == ACK && "${f[2]:-}" == QUIESCED ]] || continue
        if [[ "${f[3]:-}" == 1 ]]; then
          waiting+="${waiting:+, }${f[1]}（${f[4]:-}）"
        else
          down+="${down:+, }${f[1]}（${f[4]:-}）"
        fi
      done <<< "$out"
      if [[ "$QDB_VERDICT" == OK ]]; then
        if [[ -n "$down" ]]; then
          quiesce_db::log "WARN: 区間の中で heartbeat が止まった instance は再開を確かめずに進めます: $down"
        fi
        return 0
      fi
    else
      quiesce_db::log "WARN: 再開を確かめられません（再試行します）: ${QDB_SQL_ERROR:-判定の行がありません}"
    fi
    if (( $(quiesce_db::now) >= deadline )); then
      quiesce_db::log "重大: app の再開を確かめられません（instance ${waiting:-不明}。QUIESCE_RESUME_CONFIRM_SECONDS=$QDB_RESUME 秒）。app は lease の期限で自動で再開します（errorCode=BACKUP_QUIESCE_AUTO_RESUMED を確かめてください）"
      return 3
    fi
    sleep 1
  done
}

# hold.log の要約（最後の 20 行）を標準エラーへ出す
quiesce_db::hold_log_summary() { # <state_dir>
  local f="$1/hold.log"
  [[ -s "$f" ]] || return 0
  echo "[quiesce-db] 保持 process の log（最後の 20 行。$f）:" >&2
  tail -n 20 "$f" | common::redact | sed 's/^/[quiesce-db]   /' >&2
}

# release の共通の手順（ONLINE・APP_STOPPED）: 要求を解き、保持 process を終わらせ、ONLINE なら再開を確かめる
quiesce_db::release() { # <state_dir> <確かめる: online | none>
  local d=$1 rc=0
  if [[ -f "$d/request.env" ]]; then
    quiesce_db::release_request "$d" || rc=1
  fi
  quiesce_db::stop_hold "$d"
  if (( rc == 0 )) && [[ "$2" == online && -f "$d/request.env" ]]; then
    quiesce_db::confirm_resumed "$d" || rc=$?
  fi
  quiesce_db::hold_log_summary "$d"
  return "$rc"
}
