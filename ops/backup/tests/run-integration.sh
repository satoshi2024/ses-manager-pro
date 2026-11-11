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
DIAG_DEADLINE_MS=0
FAILURE_STAGE=""
FAILURE_NATIVE_RC=0
FAILURE_OUTPUT_FILE=""
WORK_AWK=""
WWORK=""

compose() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
}

normalize_evidence_permissions() {
  (( WORK_OWNED )) || return 0
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

validate_work_path() {
  local work_item
  if [[ -e "$WORK" || -L "$WORK" ]]; then
    if [[ ! -d "$WORK" || -L "$WORK" ]]; then
      echo "FAIL: integration work path is not a fresh owned directory" >&2
      return 1
    fi
    if [[ -n "$(find "$WORK" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
      echo "FAIL: integration work path is not empty; existing contents are preserved" >&2
      return 1
    fi
  else
    mkdir -p "$WORK" || return 1
  fi
  WORK=$(cd "$WORK" && pwd -P) || return 1
  if [[ "$WORK" == "/" || "$WORK" == "$ROOT" || "$WORK" == "$HERE" ]]; then
    echo "FAIL: integration work path is outside the permitted fresh-work boundary" >&2
    return 1
  fi
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

redact_stream_to_file() {
  local output_file=$1 max_bytes=$2
  shift 2
  : > "$output_file" || return 1
  LC_ALL=C awk -v out="$output_file" -v maxBytes="$max_bytes" \
    -v secretFile1="$WORK_AWK/src-password" -v secretFile2="$WORK_AWK/tgt-password" \
    -v secretFile3="$WORK_AWK/repo-password" -v secretFile4="$WORK_AWK/restore-svc-pw" \
    -v secretFile5="$WORK_AWK/keys/priv1.pem" -v secretFile6="$WORK_AWK/keys/priv2.pem" '
    function load_secret_file(path, line) {
      if (path == "" || (getline line < path) < 0) return
      do {
        sub(/\r$/, "", line)
        if (length(line) > 0 && !(line in secretSeen)) {
          secretSeen[line] = 1
          secrets[++secretCount] = line
        }
      } while ((getline line < path) > 0)
      close(path)
    }
    function reload_secret_files() {
      load_secret_file(secretFile1)
      load_secret_file(secretFile2)
      load_secret_file(secretFile3)
      load_secret_file(secretFile4)
      load_secret_file(secretFile5)
      load_secret_file(secretFile6)
    }
    function redact_literals(text, result, cursor, bestPos, bestLen, pos, i) {
      result = ""
      cursor = 1
      while (cursor <= length(text)) {
        bestPos = 0
        bestLen = 0
        for (i = 1; i <= secretCount; i++) {
          pos = index(substr(text, cursor), secrets[i])
          if (pos > 0) {
            pos += cursor - 1
            if (bestPos == 0 || pos < bestPos || (pos == bestPos && length(secrets[i]) > bestLen)) {
              bestPos = pos
              bestLen = length(secrets[i])
            }
          }
        }
        if (bestPos == 0) {
          result = result substr(text, cursor)
          break
        }
        result = result substr(text, cursor, bestPos - cursor) "[REDACTED]"
        cursor = bestPos + bestLen
      }
      return result
    }
    function safe_line(text, output, beginPos, header, keyType, keyEnd, endPos, rest) {
      reload_secret_files()
      output = ""
      while (length(text) > 0) {
        if (insidePrivateKey) {
          endPos = index(text, privateKeyEnd)
          if (endPos == 0) break
          text = substr(text, endPos + length(privateKeyEnd))
          insidePrivateKey = 0
          privateKeyEnd = ""
          continue
        }
        beginPos = match(text, /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/)
        if (beginPos == 0) {
          output = output text
          break
        }
        header = substr(text, RSTART, RLENGTH)
        keyType = header
        sub(/^-----BEGIN /, "", keyType)
        sub(/-----$/, "", keyType)
        sub(/ PRIVATE KEY$/, "", keyType)
        keyEnd = "-----END " keyType "-----"
        output = output substr(text, 1, RSTART - 1) "[PRIVATE KEY BLOCK REDACTED]"
        text = substr(text, RSTART + RLENGTH)
        insidePrivateKey = 1
        privateKeyEnd = keyEnd
      }
      return redact_literals(output)
    }
    function emit(text, room, piece) {
      room = maxBytes - emittedBytes
      if (room <= 0) { truncated = 1; return }
      if (length(text) > room) {
        piece = substr(text, 1, room)
        truncated = 1
      } else piece = text
      printf "%s", piece
      printf "%s", piece >> out
      emittedBytes += length(piece)
      if (length(text) > length(piece)) truncated = 1
    }
    BEGIN {
      printf "" > out
      close(out)
      reload_secret_files()
    }
    {
      emit(safe_line($0) "\n")
    }
    END {
      if (truncated) print "[SECTION TRUNCATED AT BYTE LIMIT]" > "/dev/stderr"
      if (emittedBytes > 0 && close(out) != 0) exit 1
    }
  '
}

append_diagnostic_line() {
  local line=$1 current room
  current=$(wc -c < "$DIAG_FILE" 2>/dev/null || echo 0)
  room=$((512 * 1024 - current))
  (( room > 0 )) || return 0
  printf '%s\n' "$line" | head -c "$room" >> "$DIAG_FILE"
}

append_diagnostic_section() {
  local label=$1 section_file=$2 current room cap header_bytes
  [[ -f "$section_file" ]] || return 0
  current=$(wc -c < "$DIAG_FILE" 2>/dev/null || echo 0)
  local header="[$label]"
  header_bytes=$(LC_ALL=C printf '\n%s\n' "$header" | wc -c)
  room=$((512 * 1024 - current - header_bytes))
  (( room > 0 )) || return 0
  cap=65536
  (( room < cap )) && cap=$room
  printf '\n%s\n' "$header" >> "$DIAG_FILE"
  head -c "$cap" "$section_file" >> "$DIAG_FILE" 2>/dev/null || true
}

bounded_docker() {
  local now remaining limit_ms limit
  now=$(current_time_ms) || return 127
  remaining=$((DIAG_DEADLINE_MS - now))
  (( remaining > 0 )) || return 124
  limit_ms=5000
  (( remaining < limit_ms )) && limit_ms=$remaining
  command -v timeout >/dev/null 2>&1 || return 127
  printf -v limit '%d.%03ds' "$((limit_ms / 1000))" "$((limit_ms % 1000))"
  timeout -k 1s "$limit" docker "$@"
}

current_time_ms() {
  local epoch=${EPOCHREALTIME:-}
  [[ "$epoch" == *.* ]] || return 1
  epoch=${epoch/./}
  ((${#epoch} >= 13)) || return 1
  printf '%s\n' "$((10#${epoch:0:13}))"
}

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

verify_project_network() {
  local source_networks target_networks source_network_id target_network_id details network_id network_name project_label network_label
  source_networks=$(docker inspect --format '{{range $name,$network := .NetworkSettings.Networks}}{{printf "%s|%s\\n" $name $network.NetworkID}}{{end}}' "$SOURCE_CID" 2>/dev/null) || return 1
  target_networks=$(docker inspect --format '{{range $name,$network := .NetworkSettings.Networks}}{{printf "%s|%s\\n" $name $network.NetworkID}}{{end}}' "$TARGET_CID" 2>/dev/null) || return 1
  source_networks=${source_networks%$'\n'}
  target_networks=${target_networks%$'\n'}
  [[ "$source_networks" != *$'\n'* && "$target_networks" != *$'\n'* ]] || return 1
  IFS='|' read -r network_name source_network_id <<< "$source_networks"
  IFS='|' read -r network_name target_network_id <<< "$target_networks"
  [[ -n "$source_network_id" && "$source_network_id" == "$target_network_id" ]] || return 1
  details=$(docker network inspect --format '{{.Id}}|{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.network"}}' "$source_network_id" 2>/dev/null) || return 1
  IFS='|' read -r network_id network_name project_label network_label <<< "$details"
  [[ "$network_id" == "$source_network_id" && "$project_label" == "$PROJECT" && "$network_label" == "ses-bkup" ]] || return 1
  NET=$network_id
}

scan_failure_diagnostics() {
  local diagnostic_awk
  diagnostic_awk=$(cygpath -u "$DIAG_FILE" 2>/dev/null || echo "$DIAG_FILE")
  LC_ALL=C awk -v secretFile1="$WORK_AWK/src-password" -v secretFile2="$WORK_AWK/tgt-password" \
    -v secretFile3="$WORK_AWK/repo-password" -v secretFile4="$WORK_AWK/restore-svc-pw" \
    -v secretFile5="$WORK_AWK/keys/priv1.pem" -v secretFile6="$WORK_AWK/keys/priv2.pem" '
    function load_secret_file(path, line) {
      if (path == "" || (getline line < path) < 0) return
      do { sub(/\r$/, "", line); if (length(line) > 0) secrets[++secretCount] = line }
      while ((getline line < path) > 0)
      close(path)
    }
    BEGIN {
      load_secret_file(secretFile1); load_secret_file(secretFile2); load_secret_file(secretFile3)
      load_secret_file(secretFile4); load_secret_file(secretFile5); load_secret_file(secretFile6)
      if (secretCount == 0) exit 2
    }
    {
      if ($0 ~ /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/) exit 1
      for (i = 1; i <= secretCount; i++) if (index($0, secrets[i]) > 0) exit 1
    }
  ' "$diagnostic_awk"
}

collect_failure_diagnostics() {
  local primary_rc=$1 ids id count=0 service details state_file log_file inspect_format
  local found_id found_project found_service section_rc redact_rc command_status timeout_available=1
  PROJECT_IDS_VERIFIED=0
  PROJECT_CONTAINER_IDS=()
  SOURCE_CID="" TARGET_CID="" TOOL_CID="" SCHEDULER_CID="" REPLICA_CID=""
  if (( ! WORK_OWNED )) || [[ -z "$DIAG_FILE" ]]; then return 0; fi
  : > "$DIAG_FILE" || return 1
  append_diagnostic_line "DIAGNOSTIC ONLY — not PITR or restore-drill success evidence"
  append_diagnostic_line "run=$RUN_ID project=$PROJECT stage=${FAILURE_STAGE:-unknown} native_exit=${FAILURE_NATIVE_RC:-$primary_rc} runner_exit=$primary_rc time_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ -n "$FAILURE_OUTPUT_FILE" && -f "$FAILURE_OUTPUT_FILE" ]]; then
    append_diagnostic_section "failing-command-output" "$FAILURE_OUTPUT_FILE"
  fi
  if (( ! PROJECT_OWNED || ! upAttempted )); then
    append_diagnostic_line "collector=preflight failed before this run owned/attempted a Compose project; no Docker resources inspected"
    if ! scan_failure_diagnostics; then
      : > "$DIAG_FILE"
      printf '%s\n' 'DIAGNOSTIC ONLY — redaction/secret scan failed; raw section omitted.' > "$DIAG_FILE"
    fi
    echo "failure diagnostics saved (diagnostic only): $DIAG_FILE" >&2
    return 0
  fi
  command -v timeout >/dev/null 2>&1 || timeout_available=0
  if (( ! timeout_available )); then
    append_diagnostic_line "collector=bounded timeout command unavailable; Docker diagnostics omitted"
  else
    local diag_start_ms
    diag_start_ms=$(current_time_ms) || diag_start_ms=0
    if (( diag_start_ms == 0 )); then
      append_diagnostic_line "collector=high-resolution Bash clock unavailable; Docker diagnostics omitted"
      if ! scan_failure_diagnostics; then
        : > "$DIAG_FILE"
        printf '%s\n' 'DIAGNOSTIC ONLY — redaction/secret scan failed; raw section omitted.' > "$DIAG_FILE"
      fi
      echo "failure diagnostics saved (diagnostic only): $DIAG_FILE" >&2
      return 0
    fi
    DIAG_DEADLINE_MS=$((diag_start_ms + 15000))
    ids=$(bounded_docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -aq 2>/dev/null)
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
        local now_ms
        now_ms=$(current_time_ms) || now_ms=$DIAG_DEADLINE_MS
        if (( now_ms >= DIAG_DEADLINE_MS )); then
          append_diagnostic_line "container-validation=diagnostic deadline exhausted"
          break
        fi
        details=$(bounded_docker inspect --format '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' "$id" 2>/dev/null)
        section_rc=$?
        if (( section_rc != 0 )); then
          append_diagnostic_line "container=$id ownership=unverified rc=$section_rc"
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

    inspect_format='{{.Id}}|{{.State.Status}}|{{.State.Running}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.Error}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|{{if .State.Health}}{{.State.Health.Status}}{{range .State.Health.Log}}{{printf "\\nhealth_start=%s health_exit=%d health_output=%s" .Start .ExitCode .Output}}{{end}}{{end}}|{{.Image}}{{range .Mounts}}{{printf "\\nmount_type=%s source=%s destination=%s rw=%t" .Type .Source .Destination .RW}}{{end}}'
    for service in target source tool scheduler replica; do
      case "$service" in
        target) id=$TARGET_CID ;;
        source) id=$SOURCE_CID ;;
        tool) id=$TOOL_CID ;;
        scheduler) id=$SCHEDULER_CID ;;
        replica) id=$REPLICA_CID ;;
      esac
      [[ -n "$id" ]] || continue
      state_file="$WORK/.failure-state-$service"
      log_file="$WORK/.failure-log-$service"
      redact_rc=0
      bounded_docker inspect --format "$inspect_format" "$id" 2>&1 | redact_stream_to_file "$state_file" 65536
      command_status=("${PIPESTATUS[@]}")
      section_rc=${command_status[0]:-1}
      redact_rc=${command_status[1]:-1}
      if (( redact_rc == 0 )); then
        append_diagnostic_section "$service-state-health-image-mounts" "$state_file"
      else
        printf '%s\n' '[state section redaction failed; content omitted]' > "$state_file"
        append_diagnostic_section "$service-state-health-image-mounts" "$state_file"
      fi
      local now_ms
      now_ms=$(current_time_ms) || now_ms=$DIAG_DEADLINE_MS
      if (( now_ms < DIAG_DEADLINE_MS )); then
        bounded_docker logs --timestamps --tail 200 "$id" 2>&1 | redact_stream_to_file "$log_file" 65536
        command_status=("${PIPESTATUS[@]}")
        section_rc=${command_status[0]:-1}
        redact_rc=${command_status[1]:-1}
        if (( redact_rc == 0 )); then
          append_diagnostic_section "$service-logs tail=200" "$log_file"
        else
          printf '%s\n' '[log section redaction failed; content omitted]' > "$log_file"
          append_diagnostic_section "$service-logs tail=200" "$log_file"
        fi
        append_diagnostic_line "$service-log-collector_rc=$section_rc"
      fi
      rm -f "$state_file" "$log_file" "$state_file.bytes" "$log_file.bytes"
    done
  fi

  if ! scan_failure_diagnostics; then
    : > "$DIAG_FILE"
    printf '%s\n' 'DIAGNOSTIC ONLY — redaction/secret scan failed; raw section omitted.' > "$DIAG_FILE"
  fi
  echo "failure diagnostics saved (diagnostic only): $DIAG_FILE" >&2
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
