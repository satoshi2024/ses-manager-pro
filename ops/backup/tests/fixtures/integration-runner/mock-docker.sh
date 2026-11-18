#!/usr/bin/env bash
# run-integration.sh の harness selftest 用 docker モック（実 Docker・ネットワーク・コンテナには一切触れない）
#
# harness-selftest-test.sh が一時 bin へ "docker" としてコピーし、PATH 先頭に置いて使う。
# 入力（環境変数）:
#   MOCK_DOCKER_LOG  呼び出し引数を 1 行ずつ追記するログ（呼び出し順序の検証に使う）
#   MOCK_STATE_DIR   compose project 名・compose up 実施有無の状態ファイル置き場
#   MOCK_SCENARIO    シナリオ名（下記 case を参照）
# Go template の改行はランナーが渡した --format 文字列から再現する:
#   {{println}} または単一バックスラッシュの改行エスケープ → 実改行
#   二重バックスラッシュの改行エスケープ → 字面のバックスラッシュ+n（P1 回帰の再現）
set -u
printf '%s\n' "$*" >> "${MOCK_DOCKER_LOG:?MOCK_DOCKER_LOG required}"
state=${MOCK_STATE_DIR:?MOCK_STATE_DIR required}
scenario=${MOCK_SCENARIO:-pitr-native}

# harness-selftest-test.sh はこの値を sed で読み取って期待値に使う（1 行 1 定義を保つこと）
SRC_ID=1111111111111111111111111111111111111111111111111111111111111111
TGT_ID=2222222222222222222222222222222222222222222222222222222222222222
TOOL_ID=3333333333333333333333333333333333333333333333333333333333333333
SCHED_ID=4444444444444444444444444444444444444444444444444444444444444444
REPL_ID=5555555555555555555555555555555555555555555555555555555555555555
FOREIGN_ID=9999999999999999999999999999999999999999999999999999999999999999
NET_ID=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789
OTHER_NET_ID=fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210

project=$(cat "$state/project" 2>/dev/null || true)

template_newline() {
  case "$1" in
    *'{{println}}'*) printf '\n' ;;
    *'\\n'*) printf '%s' '\n' ;;
    *'\n'*) printf '\n' ;;
  esac
}

service_of() {
  case "$1" in
    "$SRC_ID") echo source ;;
    "$TGT_ID") echo target ;;
    "$TOOL_ID") echo tool ;;
    "$SCHED_ID") echo scheduler ;;
    "$REPL_ID") echo replica ;;
    "$FOREIGN_ID") echo source ;;
    *) return 1 ;;
  esac
}

pem_block() {
  printf '%s\n' '-----BEGIN RSA PRIVATE KEY-----' 'mock-key-body-unique' '-----END RSA PRIVATE KEY----- mock-after-pem-tail'
}

compose_ids() {
  if [[ ! -f "$state/up" ]]; then
    # project-not-fresh: 生成した project 名に既存コンテナがある（project preflight で止まる）
    if [[ "$scenario" == project-not-fresh ]]; then printf '%s\n' "$SRC_ID"; fi
    # race-mkdir: WORK の判定後・準備前（project の事前確認中）に第三者が作業ディレクトリを先に作った状況を再現する
    if [[ "$scenario" == race-mkdir && -n "${INTEGRATION_WORK_DIR:-}" && ! -e "$INTEGRATION_WORK_DIR" ]]; then
      mkdir "$INTEGRATION_WORK_DIR" 2>/dev/null || true
      printf '%s\n' "$INTEGRATION_WORK_DIR" >> "${MOCK_STATE_DIR:?}/raced-dirs"
    fi
    return 0
  fi
  case "$scenario" in
    up-fail) return 0 ;;
    partial-up|no-timeout) printf '%s\n' "$SRC_ID" "$TGT_ID" ;;
    foreign-container) printf '%s\n' "$FOREIGN_ID" "$TGT_ID" "$TOOL_ID" "$SCHED_ID" "$REPL_ID" ;;
    diag-ps-fail) return 1 ;;
    *) printf '%s\n' "$SRC_ID" "$TGT_ID" "$TOOL_ID" "$SCHED_ID" "$REPL_ID" ;;
  esac
}

do_compose() {
  local compose_project=""
  if [[ "${1:-}" == version ]]; then exit 0; fi
  while (( $# > 0 )); do
    case "$1" in
      -f) shift 2 ;;
      -p) compose_project=$2; shift 2 ;;
      *) break ;;
    esac
  done
  if [[ -n "$compose_project" ]]; then printf '%s' "$compose_project" > "$state/project"; fi
  case "$*" in
    "ps -aq")
      compose_ids
      exit $? ;;
    "up -d --build")
      : > "$state/up"
      printf '%s\n' "Container ses-bkup-int-target Created" "mock build log secret=${INTEGRATION_SRC_PW:-}"
      pem_block
      case "$scenario" in
        partial-up|slow-diag|no-timeout|diag-ps-fail|up-fail)
          # up-fail: コンテナを 1 つも作らずに失敗する（WORK 所有後の最も早い失敗点として使う）
          printf '%s\n' "Error response from daemon: mock-partial-start-failure"
          exit 17 ;;
        tamper-owner)
          # 実行中に第三者が所有マーカーを書き換えた状況を再現する（ランナーは chmod/chown してはならない）
          printf '%s\n' 'run=forged-by-someone-else' > "${INTEGRATION_WORK_DIR:?}/.ses-pitr-run-owner"
          exit 17 ;;
      esac
      exit 0 ;;
    "up --wait -d")
      if [[ "$scenario" == wait-unhealthy ]]; then
        printf '%s\n' "dependency failed to start: container ses-bkup-int-target is unhealthy tgt=${INTEGRATION_TGT_PW:-}"
        exit 1
      fi
      exit 0 ;;
    "down -v") exit 0 ;;
  esac
  exit 92
}

do_ps() {
  if [[ "$scenario" == fixed-name-occupied && "$*" == *'name=^/ses-bkup-int-source$'* ]]; then
    printf '%s\n' deadbeefdead
  fi
  exit 0
}

network_lines() {
  local svc=$1 fmt=$2 net=$NET_ID
  if [[ "$scenario" == split-network && "$svc" == target ]]; then net=$OTHER_NET_ID; fi
  printf '%s|%s' "${project}_ses-bkup" "$net"
  template_newline "$fmt"
  if [[ "$scenario" == multi-network && "$svc" == source ]]; then
    printf '%s|%s' bridge "$OTHER_NET_ID"
    template_newline "$fmt"
  fi
  # docker inspect は各オブジェクトの出力後に改行を付ける
  printf '\n'
}

state_lines() {
  local id=$1 svc=$2 fmt=$3 health=healthy code=0 status=running running=true
  if [[ "$svc" == target ]]; then health=unhealthy; code=1; status=exited; running=false; fi
  printf '%s|%s|%s|%s|false||2026-11-12T00:00:00Z|2026-11-12T00:01:00Z|%s' "$id" "$status" "$running" "$code" "$health"
  template_newline "$fmt"
  printf 'health_start=2026-11-12T00:00:10Z health_exit=%s health_output=mysqladmin connect failed pw=%s' "$code" "${INTEGRATION_TGT_PW:-}"
  template_newline "$fmt"
  printf 'health_start=2026-11-12T00:00:12Z health_exit=%s health_output=%s-health-second' "$code" "$svc"
  printf '|mysql:8.0.36'
  template_newline "$fmt"
  printf 'mount_type=volume source=/var/lib/docker/volumes/%s destination=/var/lib/mysql rw=true' "$svc"
  printf '\n'
}

do_inspect() {
  local fmt id svc
  [[ "${1:-}" == --format ]] || exit 93
  fmt=$2
  id=$3
  svc=$(service_of "$id") || { echo "Error: No such object: $id" >&2; exit 1; }
  case "$fmt" in
    *'com.docker.compose.project'*)
      if [[ "$id" == "$FOREIGN_ID" ]]; then
        printf '%s|%s|%s\n' "$id" someone-elses-project "$svc"
      else
        printf '%s|%s|%s\n' "$id" "$project" "$svc"
      fi ;;
    *'.NetworkSettings.Networks'*)
      network_lines "$svc" "$fmt" ;;
    *'.State.Status'*)
      if [[ "$scenario" == slow-diag ]]; then exec sleep 30; fi
      state_lines "$id" "$svc" "$fmt" ;;
    *) exit 94 ;;
  esac
  exit 0
}

do_network() {
  local netid=${*: -1} label=$project
  [[ "${1:-}" == inspect ]] || exit 95
  if [[ "$netid" != "$NET_ID" && "$netid" != "$OTHER_NET_ID" ]]; then
    echo "Error: No such network: $netid" >&2
    exit 1
  fi
  if [[ "$scenario" == foreign-network ]]; then label=someone-elses-project; fi
  printf '%s|%s|%s|%s\n' "$netid" "${project}_ses-bkup" "$label" ses-bkup
  exit 0
}

do_logs() {
  local id=${*: -1} svc
  svc=$(service_of "$id") || exit 1
  if [[ "$scenario" == slow-diag ]]; then
    printf '%s\n' "2026-11-12T00:00:00Z $svc slow log start"
    exec sleep 30
  fi
  printf '%s\n' "2026-11-12T00:00:00Z [ERROR] $svc boot failure src=${INTEGRATION_SRC_PW:-} tgt=${INTEGRATION_TGT_PW:-}"
  printf '%s\n' "2026-11-12T00:00:01Z -----BEGIN EC PRIVATE KEY-----" \
    "2026-11-12T00:00:01Z mock-$svc-key-body-unique" \
    "2026-11-12T00:00:01Z -----END EC PRIVATE KEY----- $svc-same-line-tail"
  printf '%s\n' "2026-11-12T00:00:02Z [ERROR] $svc-normal-error-after-pem"
  exit 0
}

do_cp() {
  local dest=${*: -1}
  printf 'mock-ca\n' > "$dest"
  exit 0
}

# docker run の -v <host>:/work:rw|ro から host 側の作業ディレクトリを取り出す
host_work_dir() {
  local previous="" arg
  for arg in "$@"; do
    if [[ "$previous" == "-v" ]]; then
      case "$arg" in
        *:/work:rw) printf '%s\n' "${arg%:/work:rw}"; return 0 ;;
        *:/work:ro) printf '%s\n' "${arg%:/work:ro}"; return 0 ;;
      esac
    fi
    previous=$arg
  done
  return 1
}

# 成功系シナリオ用に integration-pitr.sh が作る summary と evidence を模擬する
write_pitr_outputs() {
  local host file
  host=$(host_work_dir "$@") || return 1
  mkdir -p "$host/evidence"
  printf '%s\n' '{"state":"SUCCESS","plan_id":"plan-mock-1"}' > "$host/integration-summary.json"
  for file in validate.json restore.log target-markers.txt uploads-markers.txt source-state.txt report-restore-contract.txt; do
    printf 'mock %s\n' "$file" > "$host/evidence/$file"
  done
}

do_run() {
  case " $* " in
    *integration-pitr.sh*)
      printf '%s\n' "== pitr step: full backup ==" "pitr secret src=${INTEGRATION_SRC_PW:-} tgt=${INTEGRATION_TGT_PW:-} restore=restore-svc-pw"
      pem_block
      printf '%s\n' pitr-normal-tail-after-pem
      if [[ "$scenario" == pitr-native ]]; then exit 42; fi
      if [[ "$scenario" == success || "$scenario" == state-fail ]]; then write_pitr_outputs "$@" || exit 96; fi
      exit 0 ;;
    *restore-drill.sh*)
      printf '%s\n' "drill failure tgt=${INTEGRATION_TGT_PW:-}" drill-normal-tail
      if [[ "$scenario" == drill-native ]]; then exit 37; fi
      exit 0 ;;
    *openssl*)
      printf '%s\n' 0a1b2c3d
      exit 0 ;;
    *'.requested_target'*)
      printf '%s\n' 2026-11-12T00:00:00Z
      exit 0 ;;
    *'.plan_id'*)
      printf '%s\n' plan-mock-1
      exit 0 ;;
    *' jq -r .state '*)
      if [[ "$scenario" == state-fail ]]; then printf '%s\n' FAILED; else printf '%s\n' SUCCESS; fi
      exit 0 ;;
    *' jq -r .target_db.before_marker '*|*' jq -r .target_db.mid_dml_replayed '*|*' jq -r .uploads_staging.before_marker '*)
      printf '%s\n' 1
      exit 0 ;;
    *' jq -r .target_db.after_marker '*|*' jq -r .uploads_staging.after_marker '*)
      printf '%s\n' 0
      exit 0 ;;
    *' jq -r . '*)
      printf '%s\n' '{"state":"SUCCESS","plan_id":"plan-mock-1"}'
      exit 0 ;;
    *' jq -e '*)
      exit 0 ;;
    *' jq {state'*)
      printf '%s\n' '{"state":"SUCCESS","rto_ok":true,"rpo_ok":true}'
      exit 0 ;;
  esac
  exit 0
}

command_name=${1:-}
shift || true
case "$command_name" in
  info) exit 0 ;;
  compose) do_compose "$@" ;;
  ps) do_ps "$@" ;;
  inspect) do_inspect "$@" ;;
  network) do_network "$@" ;;
  logs) do_logs "$@" ;;
  cp) do_cp "$@" ;;
  exec) exit 0 ;;
  run) do_run "$@" ;;
esac
exit 91
