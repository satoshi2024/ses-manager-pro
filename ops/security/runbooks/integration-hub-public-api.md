# Integration Hub Public API 運用 runbook（NF-05 / M）

本 runbook は `integration.hub.public-api.enabled=false` / `integration.hub.external-transport.enabled=false` /
`integration.hub.provider.mode=MOCK` を production 正本とする公開 API の障害対応手順である。
production enablement、実顧客 credential、実 provider 送信は DG-05 Owner 承認と Review PASS 後のみ実施する。

## 1. 監視と alert

| 信号 | 閾値 | 初動 |
|---|---|---|
| `integration_hub_external_api_requests_total{status_class="5xx"}` | 5分連続 > 0 | §2 一般障害 |
| `integration_hub_external_api_requests_total{status_class="429"}` | client tier 単位で急増 | §3 rate/quota |
| `integration_hub_webhook_delivery_status{status="DLQ"}` | 1件以上滞留 | §5 DLQ / manual replay |
| `integration_hub_inbound_event_status{status="FAILED"}` | 15分連続増加 | §4 inbound |
| DB connection pool exhausted | Hikari pending > 0 が継続 | §6 DB障害 |
| startup validator fail-closed | 起動失敗 | §7 config / emergency stop |

metrics label は route template / method / status class / bounded outcome / client tier の有限集合のみ。
client ID、correlation ID、resource ID、IP、provider event ID を label に置かない。

## 2. 一般 API 障害（timeout / 5xx）

1. `integration.hub.public-api.enabled` が意図どおりか確認する。production では default-off。
2. 専用 security chain（`ExternalApiSecurityConfig` @Order(0)）が deny-only か enabled かを profile で確認する。
3. `t_external_api_audit` で直近 reject の status/result code を確認する。secret/PII/raw body は保存されない。
4. correlation ID（`X-Correlation-ID`）で同一 request の監査 1 件を特定する。
5. 原因が DB の場合は §6 へ。アプリのみの場合は rolling restart 前に in-flight idempotency / delivery lease を確認する。
6. 復旧後、enabled connector E2E（read 202/200、inbound duplicate/conflict）を development/test で再実行する。

## 3. rate / quota（429 / Retry-After）

- quota subject key は `client × scope × tenant × route template` のみ。IP や raw path は含まない。
- burst capacity 20、3 秒 1 token refill、minute 60、day 50,000。
- 429 応答の `Retry-After` をそのまま client へ返す。server 側で burst を手動 reset しない。
- 正当な traffic spike と abuse を `client_tier` と audit の route template 分布で切り分ける。
- 恒久対応は client scope / tier 見直し。production で quota を無効化しない。

## 4. inbound webhook 障害

| 症状 | 安全な初動 |
|---|---|
| 400 `REQUEST_INVALID` | Content-Type が単一 `application/json`（許可 charset のみ）か確認 |
| 403 `FORBIDDEN_SCOPE` | provider catalog、subscription、receive permission、tenant/legal scope intersection |
| 409 `INBOUND_PAYLOAD_CONFLICT` | 同一 provider event ID・別 payload。ledger は作成済み。手動修正しない |
| duplicate 200 | 正常。再送は同結果 |
| signature / timestamp 失敗 | credential rotation overlap（24h）と clock skew（±300s）を確認 |

拒否系（400/403）は inbound ledger を作成しない。監査 DB の result code を確認する。

## 5. outbound webhook / DLQ / manual replay

1. `t_api_delivery` の status / attempt_count / lease_expires_at を確認する。
2. worker crash 後は stale lease recovery（`recoverExpiredLeases`）で RETRYABLE へ戻る。transport 再試行は lease 満了後。
3. attempt 8 到達で DLQ。4xx は retry しない。
4. manual replay は authenticated internal admin + `integration.webhook.replay` permission が必要。
5. replay 直前に current scope / membership を再検証する。不一致なら replay せず terminal state を維持する。
6. replay 失敗時は DLQ を維持し、operator reference は server-side 導出値のみを audit へ記録する。

## 6. DB / worker / provider 停止

- 外部 HTTP は DB transaction 外。DB 停止中も in-flight lease は expiry 後に recovery 可能。
- provider 停止時: MOCK/STUB（無接続）または LOOPBACK（development/test のみ）。production は MOCK 以外拒否。
- restore 後: `advanceRestoreEpoch` により purge checkpoint を invalid 化し、retention 対象を先頭から再評価する。
- legal hold 中は purge を停止する。hold 解除後に cursor reset で再 purge する。

## 7. key rotation / revoke / emergency stop

### 7.1 credential rotation（24h overlap）

1. 新 credential を issue する。旧 ACTIVE は OVERLAP（24h）へ。
2. overlap 中は旧 keyId / 新 keyId の双方で HMAC 検証可能。
3. overlap 終了後、旧世代は usable 検索から除外される。
4. secret 平文は API 応答・ログ・監査へ出力しない。envelope（IHG1）のみ保存。

### 7.2 revoke

- `CredentialVersionService.revoke` は version CAS。revoke 後は即座に usable から除外。
- 失効 credential での request は 401 `AUTHENTICATION_FAILED`。client へ詳細を返さない。

### 7.3 emergency stop（production）

1. `integration.hub.public-api.enabled=false`
2. `integration.hub.external-transport.enabled=false`
3. `integration.hub.provider.mode=MOCK`
4. 再起動後、deny-only chain が `/external-api/v1/**` を stable 404 JSON で返すことを確認する。
5. controller / worker / scheduler / transport bean が生成されないことを確認する。

## 8. backup / restore 連携

- DB restore 後は Flyway 状態と `t_api_purge_checkpoint.restore_epoch` を確認する。
- retention purge は restore epoch 以降、checkpoint を信用せず全対象を再評価する。
- idempotency / inbound / delivery の terminal retention（succeeded 30d、failed/DLQ 90d、audit 1y）を維持する。

## 9. エスカレーション

| 重大度 | 条件 | 通知 |
|---|---|---|
| SEV-1 | production enablement 下で全 client 5xx | 60 分以内（IH-R5） |
| SEV-2 | DLQ 滞留 > 1h または inbound FAILED 連続 | 営業時間内 30 分 |
| SEV-3 | 単一 client 429 常態化 | 翌営業日 |

## 10. 参照

- Spec: `.kiro/specs/integration-hub-public-api/`
- Evidence: `.kiro/specs/integration-hub-public-api/evidence-index.md`
- Backup restore: `ops/backup/runbooks/restore-cutover.md`
