# NF-05 外部Provider 接続準備・sandbox受入計画

## 1. 目的・判定境界

本書は NF-05 Public API / Webhook の実provider接続前に必要な決定、隔離sandbox受入、停止・復旧手順を定義する計画である。対象は実装変更ではなく readiness 設計だけとする。固定 Control `9bac40c9624e7d964bcf6e18f480b3457662206e` のコードを前提にした計画であり、NF05候補 `f99c993a67d63afc3572ceeeb1cd00c957b17101` は read-only 比較資料である。候補の差分・実行結果を本Controlの実装事実や承認として扱わない。

MOCK/STUB/LOOPBACKの成功は、その実装・契約と合成fixtureを確認するだけで、実provider、実ネットワーク、実credential、署名・TLS相互運用、契約、費用、Privacy、production readinessの受入ではない。現行transport enumに実provider実装はなく、実provider接続は未接線。

## 2. 現行能力と未接線点

| 境界 | 現状として確認できる契約 | readiness後も別途必要な作業 |
|---|---|---|
| 選択 | `IntegrationHubExternalApiProperties`はproductionをpublic API OFF / external transport OFF / MOCK固定にし、transport mode enumは`MOCK` / `STUB` / `LOOPBACK`。`IntegrationHubWebhookTransport` 実装はMOCK/STUBが無通信、LOOPBACKが開発/test限定のloopback接続。LOOPBACKは開発/test専用でliteral loopback、allow-list port、DNSなし、redirect/proxy等の制限を持つ | provider名・製品・API契約・HTTP認証方式・endpoint・region・組織/契約主体の選定。実provider専用実装と合成契約 |
| event配送 | HMAC署名event、subscription単位のdelivery ledger、固定idempotency key、lease/CAS、bounded retry/DLQ/replayの設計 | providerの重複排除期間・key長/書式・応答契約と当方のdelivery generationの対応確認。相手側で副作用を一度だけ適用する確認 |
| credential | NF05 inbound用client credential世代/nonce/revokeモデルとsecret envelopeは存在。outbound署名鍵も存在 | outbound相手認証に必要なcredential種別は未決。secret manager/KMSの正式名称・権限・注入経路・監査主体・rotation runbookは未承認 |
| 経路 | `integration.hub.public-api.enabled=false`、`external-transport.enabled=false`、`provider.mode=MOCK` がproductionの固定条件。missing/unknown configはfail-closed | 実provider URL/証明書/egressを追加する場合は別変更・別レビュー・承認が必要。現行LOOPBACKの成功を実transportの証明にしない |
| 監査 | metadata-only audit、safe result code、raw body/secret/PIIを保存しない契約 | provider request IDのallow-list、相手側監査ログ照合、incident連携、保存/削除主体と期間の合意 |

確認箇所: `IntegrationHubExternalApiProperties`, `IntegrationHubWebhookTransport`, `MockIntegrationHubWebhookTransport`, `StubIntegrationHubWebhookTransport`, `LoopbackIntegrationHubWebhookTransport`, `IntegrationHubWebhookDeliveryWorker`, `CredentialVersionService`, `IntegrationHubWebhookSigner`。本段落はControlの静的ソース確認で、テストの実行証拠ではない。

## 3. Provider選択の未決事項

Provider、サービス/製品、APIバージョン、event schema version、署名/auth方式、region、availability/SLO、障害責任分界、TLS要件、idempotency仕様、rate limit、契約主体、subprocessor、削除/retention、費用上限はすべて未決。特定製品・endpoint・versionを本書で選定しない。

選定後、契約ownerは providerの公式API仕様と契約付属文書から、採用するAPI version、日付付き仕様snapshot/hash、deprecation期限、認証、TLS、idempotency、timeout/retry、429/5xx、データ所在・保持、サポート窓口を記録する。一般HTTPの参考規範は [RFC 9110 HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110) と [RFC 9333 Idempotency-Key HTTP Header Field](https://www.rfc-editor.org/rfc/rfc9333)。RFCは相手providerが実装している証拠ではなく、相手契約との照合が必要。

## 4. Credential計画（原文禁止）

| 論点 | NF05 readiness要件 |
|---|---|
| 候補名（値は作らない） | 相手がmTLSを要求する場合: `NF05_WEBHOOK_CLIENT_CERT` / `NF05_WEBHOOK_CLIENT_KEY`。Bearer/OAuth client credentialを要求する場合: `NF05_WEBHOOK_CLIENT_ID` / `NF05_WEBHOOK_CLIENT_SECRET`。outbound HMACを相互合意する場合: `NF05_WEBHOOK_SIGNING_KEY`。実名はprovider選択後にSecret Managerのresource名へ決定し、本書へ値を書かない。方式の併用を推測しない |
| 最小権限 | 専用sandbox project/tenant、送信先専用principal、許可eventとendpointのみ。read/admin/account/billing権限を付与しない。発行・閲覧・利用・rotate・revokeを分離し、CI/開発端末から本番secretを読めない |
| 注入 | 承認済みsecret managerから実行identityへ短時間注入。env/config repo、DB、artifact、command line、browser、test fixtureへ保存しない。欠落・不一致・期限切れは送信前fail-closed |
| 監査 | secretの本文・hash・headerを記録しない。secret version ID、主体、用途、発行/利用/rotation/revoke時刻と結果だけを制限付き監査へ記録 |
| Rotation | 旧新二世代の短い重複期間、相手との切替確認、synthetic署名検査、旧世代拒否確認。期限はprovider契約・security owner決定。固定期間を推測しない |
| Revoke | incident時にsecret managerで即時revoke、provider側credentialも無効化、egress/transport gateを閉じ、未送信deliveryはhold/DLQ。失効後credentialによるsandbox拒否を確認。再発行と再開は再承認 |

## 5. Sandbox受入（人工承認済み隔離環境のみ）

開始条件: provider選定済み、専用sandbox契約・環境、synthetic tenant/client/event、送信先allow-list、clock、費用枠、観測と証拠保存先、Security/Privacy/Provider ownerのsandbox実行許可。これが揃うまで実provider sandboxも呼ばず、synthetic local fixtureだけを使う。

| シナリオ | 合成fixtureで確認 | provider sandboxで追加確認 |
|---|---|---|
| 正常/署名/TLS | 正しい署名のみ検証、証明書期限/host/SNI、改ざん拒否 | 相互の証明書/署名と実際の応答、契約済みendpoint |
| timeout/slow drip | deadline超過、cancel、lease期限後の旧worker CAS拒否 | sandboxの遅延/切断が再現可能な場合のみ、人工許可の範囲で照合 |
| 429/5xx/4xx | Retry-After上限、429/一時5xxのみbounded retry、恒久4xx no-retry、attempt上限後DLQ | provider固有quota/code/headerとretry指針。相手仕様が不明ならretryしない |
| bad response | oversize、malformed JSON、redirect、unexpected content-type/status/request IDでfail-closed | 実response schema/versionの一致。raw応答を証拠へ保存しない |
| 誤identity/越境 | wrong client/key/version/tenant/legal entity/scope/subscription、unknown providerをdeny。A→B data混入ゼロ | sandboxの誤principal拒否を観測。実tenant/顧客データは使用しない |
| retry/idempotency | 同じevent/keyの再送は同一結果、別payload同keyはconflict、provider成功後worker crash/stale leaseでも外部副作用重複なしをmodel/fixtureで確認 | providerが同じidempotency keyを保持し重複副作用を抑止する仕様・実挙動を確認。loopbackの200だけでは不可 |
| revoke/rotation | stale/revoked/expired version拒否、rotation重複期間・終了後拒否、監査にsecret本文なし | sandbox credentialを実rotate/revokeし旧鍵拒否を確認。許可された実アカウント担当者が操作 |
| quota/cost | local token/rate budget、累積費用上限、日次/月次上限、kill switch発動をfake clock/counterで確認 | sandbox quota/billing pageの上限・alert・停止挙動。provider費用上限は契約/請求運用と照合 |
| audit | 成功/拒否/timeout/retry/revokeが有限route/result codeで記録され、body/secret/PII/IPなし | provider request IDの安全な相関、双方の監査記録時刻/retentionを照合 |

Sandbox証跡はsynthetic ID、日時、provider/API version、credential version ID（値なし）、test case、status/result code、latency、attempt/cost bucket、artifact hashのみ。body、secret、PII、実顧客IDは禁止。

## 6. Flag、Emergency Stop、rollback

- 初期・通常値を明示固定: `public-api.enabled=false`; `external-transport.enabled=false`; `provider.mode=MOCK`。runtime provider endpoint/credentialの未設定・矛盾・unknownはfail-closed。productionにMOCK以外を許す変更は本書で実施しない。
- 将来の承認後も独立した最小単位（環境→client→subscription→event type→必要なら送信割合）で段階有効化する。全flagはOFFから開始し、送受信の双方とscheduler/workerを個別遮断できること。flag変更は二者承認・監査対象。
- Emergency stopは一操作で外向きtransportとworker dispatchを閉じる運用手順をprovider onboarding前に定義する。併せてingress拒否、scheduler停止、credential revoke、delivery claim停止、未送信をhold/DLQ、既送信の照合/補償、監査保存を行う。DB配送履歴の削除や自動再送をしない。
- 発火条件: secret漏えい/想定外主体、tenant越境・PII/未許可event、署名/TLS異常、二重副作用、費用枠到達または不明、429/5xx/timeoutの閾値超過、audit欠落、provider仕様/契約変更、Privacy/Legal承認撤回。
- rollback: flag OFF → transport dispatch停止 → 必要ならprovider側credential revoke → retry/replayを凍結 → 安全なstatusを記録 → inflightを照合し手動補償 → root causeと再試験。再開はprovider/security/Privacy/Operations各ownerの記録された承認後のみ。

## 7. 人工gateと検証可能範囲

**合成fixtureで可能:** signer/parser/schema/property fail-closed、timeout/429/bad response、retry/DLQ/idempotency/CAS、wrong identity/scope、rotation state machine、revocation denial、budget boundary、audit redaction、flag/emergency-stop制御のpure/unit/mock/loopback検証。loopbackはliteral loopbackのみで、成功を実provider受入へ換算しない。

**人工の環境/権限/実行が必要:** provider/製品/API version決定、sandboxアカウント/契約/egress許可、provider credentials発行・注入・rotate・revoke、実TLS/署名互換、実429/quota/billing、data region/retention/training/subprocessor/DPA評価、Security/Privacy/Legal/Production Owner承認、代表的本番topologyでのcanary。本番は別途明示承認されるまで禁止。人手担当者は操作前に実行scope・時間窓・synthetic-only data・費用上限を記録する。

## 8. 未解決の人手決定

1. Provider/製品、API/event schema version、認証方式、endpoint/region、provider ownerと契約主体。
2. Sandbox環境/アカウント、許可egress、人工実行者、運用連絡先、費用上限・停止連絡網。
3. Secret Manager/KMS名、実行service identity、RBAC承認者、rotation/revoke SLAと相手側失効手順。
4. Provider idempotency semantics、Retry-After/429/5xx契約、最大delivery期限、manual replay承認者。
5. NF-07 data inventory/classification/retention/purge/hold、Privacy/Legal DPA・subprocessor・地域/越境・incident通知の承認。
6. Security/Operations/Production Ownerによるemergency stop演習、flag二者承認、production独立release gate。

**状態:** provider readiness設計案。provider契約・sandbox受入・Security/Privacy/Legal/production承認は未完。実provider acceptance、CODE PASS、production approvalを宣言しない。

## 9. 公式根拠と適用版

Provider未選定のため、各providerの公式API文書/契約バージョンは**未適用・未決**。候補決定後、その日付の公式文書を保存し、endpoint/auth/schema/deprecation/data/costをversioned evidenceにする。本書作成時（2026-10-26）のHTTP一般規範として [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110)（2022-06）と [RFC 9333](https://www.rfc-editor.org/rfc/rfc9333)（2023-02）を参照。これらはprovider実装適合、契約、法務承認を意味しない。
