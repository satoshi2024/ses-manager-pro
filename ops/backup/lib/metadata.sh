#!/usr/bin/env bash
# ============================================================
# metadata.json 生成（HFP-03-003 / RQ-003）
# 秘密、接続 URL、個人データ、生の host/user/DB 名を含めない。
# DB 名は非可逆 fingerprint（database_fingerprint）でのみ表現する。
# 書込み静止の記録（quiesce）は quiesce.json の投影だけを入れる（metadata は restic と index に backup の保存期間まで残るため。
# app の host 名・接続元（db_client_host）・要求の token・state dir の path は入れず、instance は ID と件数だけ。2d review P3-4）。
# quiesce.json の全体は job の state dir にだけあり、container とともに消える（ops/backup/README.md「書込み静止の記録」）。
# 入力変数は未設定でも安全に動作する（unknown / 空文字へフォールバック）。
# ============================================================

METADATA_SCHEMA_VERSION=1

# DB 名の非可逆 fingerprint
metadata::db_fingerprint() { # environment db_name
  printf '%s' "$1|$2" | sha256sum | awk '{print $1}'
}

# source lineage = sha256(server_uuid + db fingerprint)
metadata::lineage() { # server_uuid db_fingerprint
  printf '%s' "$1|$2" | sha256sum | awk '{print $1}'
}

# quiesce.json から metadata に入れる値だけを取り出す（provider・mode・job の種類・要求の番号・時刻・静止区間の秒数と内訳、
# instance の件数と ID）。host_name・db_client_host・request_token・state_dir・instance ごとの時刻は入れない（2d review P3-4）
metadata::quiesce_projection() { # quiesce.json
  jq -c '{provider, mode, job_kind, request_id, requested_at_utc, started_at_utc, verified_at_utc,
          released_at_utc, release_reason, window_seconds, window_breakdown_seconds,
          instance_count: ((.instances // .replicas // []) | length),
          verified_instance_count,
          instance_ids: [(.instances // [])[] | .instance_id]}' "$1"
}

# metadata.json を生成する（引数は全て build 済みの値）
metadata::build() { # staging_dir out_json
  local staging=$1 out_json=$2
  local quiesce_json="${QUIESCE_STATE_DIR:-/tmp}/quiesce.json"
  local quiesce="{}"
  if [[ -f "$quiesce_json" ]]; then
    quiesce=$(metadata::quiesce_projection "$quiesce_json") || return 1
  fi
  local uploads_json="{}"
  if [[ -f "$staging/uploads-snapshot.json" ]]; then
    uploads_json=$(cat "$staging/uploads-snapshot.json")
  fi
  # 主要 table count（呼び出し元が METADATA_TABLE_COUNTS_JSON で渡す）
  local counts="{}"
  if [[ -n "${METADATA_TABLE_COUNTS_JSON:-}" ]]; then
    counts=$METADATA_TABLE_COUNTS_JSON
  fi
  # 主要 table の順序付き内容ハッシュ（A-NF09-5。呼び出し元が METADATA_CONTENT_HASHES_JSON で渡す。
  # 未指定なら {} = 未記録。lib/content-hash.sh 参照）
  local content_hashes="{}"
  if [[ -n "${METADATA_CONTENT_HASHES_JSON:-}" ]]; then
    content_hashes=$METADATA_CONTENT_HASHES_JSON
  fi
  # uploads の pointer の名前と snapshot した版（RB-W 追加修正 C。呼び出し元が UPLOADS_POINTER_NAME /
  # UPLOADS_POINTER_TARGET で渡す。未指定なら null）。uploads 全体の snapshot JSON（uploads）にも同じ値がある。
  # schema fingerprint（G-04 / RB-W W-1。呼び出し元が METADATA_SCHEMA_FINGERPRINT_JSON で渡す。
  # 未指定なら null = 未記録で、復元検証は FAIL にする。lib/schema-fingerprint.sh 参照）
  local schema_fingerprint="null"
  if [[ -n "${METADATA_SCHEMA_FINGERPRINT_JSON:-}" ]]; then
    schema_fingerprint=$METADATA_SCHEMA_FINGERPRINT_JSON
  fi

  jq -n \
    --arg schema_version "$METADATA_SCHEMA_VERSION" \
    --arg kind "${KIND:-unknown}" \
    --arg status "${STATUS:-unknown}" \
    --arg consistency_time_utc "${CONSISTENCY_TIME_UTC:-}" \
    --arg source_server_uuid "${SOURCE_SERVER_UUID:-unknown}" \
    --arg source_lineage "${SOURCE_LINEAGE:-unknown}" \
    --arg database_fingerprint "${DATABASE_FINGERPRINT:-unknown}" \
    --arg binlog_file "${BINLOG_START_FILE:-}" \
    --arg binlog_position "${BINLOG_START_POSITION:-}" \
    --arg gtid_executed "${GTID_EXECUTED:-}" \
    --arg app_commit "${APP_COMMIT:-unknown}" \
    --arg flyway_max_success "${FLYWAY_VERSION:-unknown}" \
    --arg mysql_server_version "${MYSQL_SERVER_VERSION:-unknown}" \
    --arg mysql_client_version "${MYSQL_CLIENT_VERSION:-unknown}" \
    --arg tool_image_digest "${BACKUP_TOOL_IMAGE_DIGEST:-unknown}" \
    --arg uploads_snapshot_id "${UPLOADS_SNAPSHOT_ID:-}" \
    --arg uploads_pointer_name "${UPLOADS_POINTER_NAME:-}" \
    --arg uploads_pointer_target "${UPLOADS_POINTER_TARGET:-}" \
    --argjson quiesce "$quiesce" \
    --argjson uploads "$uploads_json" \
    --argjson critical_table_counts "$counts" \
    --argjson content_hashes "$content_hashes" \
    --argjson schema_fingerprint "$schema_fingerprint" \
    '{schema_version: $schema_version,
      kind: $kind,
      status: $status,
      consistency_time_utc: $consistency_time_utc,
      source_server_uuid: $source_server_uuid,
      source_lineage: $source_lineage,
      database_fingerprint: $database_fingerprint,
      binlog_start: {file: $binlog_file, position: ($binlog_position | tonumber?)},
      gtid_executed: $gtid_executed,
      app_commit: $app_commit,
      flyway_max_success: $flyway_max_success,
      mysql_server_version: $mysql_server_version,
      mysql_client_version: $mysql_client_version,
      tool_image_digest: $tool_image_digest,
      uploads_snapshot_id: $uploads_snapshot_id,
      uploads_pointer: (if $uploads_pointer_name == "" then null
                        else {name: $uploads_pointer_name, target: $uploads_pointer_target} end),
      quiesce: $quiesce,
      uploads: $uploads,
      critical_table_counts: $critical_table_counts,
      content_hashes: $content_hashes,
      schema_fingerprint: $schema_fingerprint}' > "$out_json"
  chmod 600 "$out_json"
  return 0
}
