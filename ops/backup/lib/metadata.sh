#!/usr/bin/env bash
# ============================================================
# metadata.json 生成（HFP-03-003 / RQ-003）
# 秘密、接続 URL、個人データ、生の host/user/DB 名を含めない。
# DB 名は非可逆 fingerprint（database_fingerprint）でのみ表現する。
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

# metadata.json を生成する（引数は全て build 済みの値）
metadata::build() { # staging_dir out_json
  local staging=$1 out_json=$2
  local quiesce_json="${QUIESCE_STATE_DIR}/quiesce.json"
  local quiesce="{}"
  if [[ -f "$quiesce_json" ]]; then
    quiesce=$(cat "$quiesce_json")
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

  jq -n \
    --arg schema_version "$METADATA_SCHEMA_VERSION" \
    --arg kind "$KIND" \
    --arg status "$STATUS" \
    --arg consistency_time_utc "$CONSISTENCY_TIME_UTC" \
    --arg source_server_uuid "$SOURCE_SERVER_UUID" \
    --arg source_lineage "$SOURCE_LINEAGE" \
    --arg database_fingerprint "$DATABASE_FINGERPRINT" \
    --arg binlog_file "$BINLOG_START_FILE" \
    --arg binlog_position "$BINLOG_START_POSITION" \
    --arg gtid_executed "$GTID_EXECUTED" \
    --arg app_commit "${APP_COMMIT:-unknown}" \
    --arg flyway_max_success "${FLYWAY_VERSION:-unknown}" \
    --arg mysql_server_version "$MYSQL_SERVER_VERSION" \
    --arg mysql_client_version "$MYSQL_CLIENT_VERSION" \
    --arg tool_image_digest "${BACKUP_TOOL_IMAGE_DIGEST:-unknown}" \
    --arg uploads_snapshot_id "$UPLOADS_SNAPSHOT_ID" \
    --argjson quiesce "$quiesce" \
    --argjson uploads "$uploads_json" \
    --argjson critical_table_counts "$counts" \
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
      quiesce: $quiesce,
      uploads: $uploads,
      critical_table_counts: $critical_table_counts}' > "$out_json"
  chmod 600 "$out_json"
  return 0
}
