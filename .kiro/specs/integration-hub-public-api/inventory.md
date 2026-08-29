# NF-05 Discovery Inventory

## 1. Inventoryの読み方

状態は次の意味で使う。

- EXISTING: 現行コードで確認できる正本または既存経路。
- REUSE-CANDIDATE: 契約を満たすよう拡張できる候補。ただしそのまま流用しない。
- GAP: 公開APIに必要だが現行実装で確認できない。
- BLOCKER: 承認、設計、または既存境界との不整合により実装開始を止める事項。
- UNAPPROVED: 候補として記録したがDG-05で承認されていない。

## 2. Repository / worktree / base

| 項目 | 確認結果 |
|---|---|
| 通常checkout | C:\work\ses-manager-pro |
| 通常branch | fix-ui-rendering-and-tests |
| 通常checkout status | 既存未コミット変更あり。NF-05では変更していない |
| remote | origin = https://github.com/satoshi2024/ses-manager-pro.git |
| Discovery worktree | C:\work\ses-manager-pro-integration-hub-public-api |
| Discovery branch | codex/integration-hub-public-api |
| 比較base | origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd |
| approved Base | 未提供。比較baseをapproved Baseとして扱わない |

## 3. Filter chain inventory

| 境界 | 現行実装 | 行/契約 | NF-05への含意 |
|---|---|---|---|
| 内部管理chain | SecurityConfig.securityFilterChain | SecurityConfig.java:117 | form login、HTTP Basic、OIDC、内部role、CSRFを持つ |
| 内部filter順 | MenuPermissionFilter → ApiAuditFilter → MfaEnforcementFilter → PersistentSessionFilter | SecurityConfig.java:120-126 | 新公開clientを内部Authenticationへ変換しない |
| 内部認可 | ActionPermissionResolver + AuthorizationService + MenuPermissionFilter | MenuPermissionFilter.java:66-117 | menu/API actionの正本は内部向け。公開client scopeとは別モデルにする |
| 内部audit | ApiAuditFilter | ApiAuditFilter.java:103-117 | /api/のPOST/PUT/DELETEとdownload中心。/external-api/は未カバー |
| 内部CSRF | Cookie XSRF-TOKEN → X-XSRF-TOKEN | SecurityConfig.java:310-315 | 公開machine-to-machine chainはCSRF方式と署名認証の役割を分離して承認する |
| 既存webhook例外 | /api/webhooks/** permitAll、CSRF ignore | SecurityConfig.java:130-145, 312-315 | 新公開APIやinbound全体をこの例外へ追加しない。digital invoice webhookと分離する |
| Portal chain | @Order(1)、securityMatcher /portal/** と /api/portal/** | PortalSecurityConfig.java:50-64 | portal principal、専用session、専用CSRF、専用rate limit。公開APIが再利用してはならない |
| Portal認証 | PortalLoginUser + PortalSessionFilter | PortalSecurityConfig.java:31-40 | internal roleへ偽装しない既存パターンをclient principalにも適用 |
| Portal rate filter | PortalRateLimitFilter | PortalRateLimitFilter.java:39-75 | endpoint種別ごとの既存判定を参考にするが、公開client/IP/rateは別設計 |
| 自動登録抑止 | FilterRegistrationBeanで内部filterをdisable | SecurityConfig.java:65-106 | 新filterもSecurityFilterChainへの明示登録と二重登録試験が必要 |

## 4. Secret encryption / rotation inventory

| 対象 | 現行方式 | 行/契約 | NF-05判断 |
|---|---|---|---|
| accounting IntegrationConnection | encryptedTokens、AES/GCM、token_version、refresh lease、Fencing CAS | IntegrationConnection.java、IntegrationConnectionMapper.java | REUSE-CANDIDATE。token世代とcrypto key versionは別。API credential envelopeを新たに定義する |
| FreeeIntegrationService | AES/GCMの設定key、refreshは短TX→外部HTTP→CAS書戻し | FreeeIntegrationServiceImpl.java:1331-1429, 1467-1492 | provider/job境界の参考。単一設定keyを公開credential保管へ流用しない |
| Portal MFA | AES/GCM、key version検証、decrypt失敗拒否 | PortalMfaServiceImpl.java:197-215 | version付き復号の参考。portal user secretとAPI client secretを同一entityへ混ぜない |
| 内部MFA | key version、current-key-version、rotation用keyring設定 | MfaServiceImpl.java、application.yml | REUSE-CANDIDATE。overlap期間、旧key失効、re-encrypt jobの契約を公開credentialで再確認 |
| Compliance credential | provider経由のversion付きAES/GCM envelope、masked snapshot | ComplianceGateCredentialCryptoServiceImpl.java | REUSE-CANDIDATE。secret原文非表示・safe errorのパターンを採用 |
| 銀行口座 | encryptedAccountNumber、prod key必須 | BpCompanyServiceImpl.java | 公開field候補に含めない |
| 公開API credential | 専用entity/credential versionの現行実装なし | GAP | BLOCKER。m_api_clientとt_api_credential_versionの契約・key providerをDG-05後に確定 |

必要な未承認決定:

1. 暗号化本文を保持するか、secret manager reference + hashとするか。
2. key version、cipher format、AAD（tenant/client/credential version）、overlap、re-encrypt、
   revoke/expiry、decrypt failureのfail-closed契約。
3. secret原文を発行時のresponseから一度だけ返す場合のclient側責任と再発行手順。

## 5. Outbox / provider / job / idempotency inventory

| 対象 | 現行方式 | 行/契約 | NF-05判断 |
|---|---|---|---|
| NotificationOutbox | t_notification_outbox、dedupe unique、PENDING/PROCESSING/RETRY/SENT/FAILED | V79__notification_webhook_outbox.sql | REUSE-CANDIDATE。notification専用列が多く、汎用deliveryへ拡張するか証明が必要 |
| NotificationOutbox claim | status条件付きUPDATE、attempt count、locked_at | NotificationOutboxMapper.java | claimの参考。lease token、tenant/client binding、generationが不足 |
| Notification dispatcher | REQUIRES_NEW dispatchOne内でclaim→外部notifyNow→結果update | NotificationOutboxDispatcher.java:44-70 | BLOCKER。外部HTTPがDB transaction中。claim TX、HTTP、result CASへ分離が必要 |
| Accounting IntegrationJob | payloadSnapshot、payloadHash、idempotencyKey、leaseToken、leaseExpiresAt、providerRequestId、安全なerror | IntegrationJob.java | REUSE-CANDIDATE。外部jobのsnapshot/CAS/leaseの正本 |
| Accounting worker | due job claim、provider dispatch、stale lease recovery、provider request ID | AccountingIntegrationWorker.java | REUSE-CANDIDATE。公開API deliveryと業務会計jobの責務分離を決める |
| Accounting provider | providerName、canonical DTO、外部I/Oはtransaction外の契約 | AccountingProvider.java | provider adapter境界の参考。公開API DTOをcanonical会計DTOへ流用しない |
| Accounting idempotency | snapshot bytesのSHA-256、業務job unique、状態CAS | IntegrationJobMapper.java、各integration service | REUSE-CANDIDATE。同key異payload conflictの外部契約を追加検証 |
| Inbound event | provider event ID/raw hash/processing resultのNF-05候補 | 現行公開API向けentityなし | GAP。t_inbound_eventと一意性、conflict、DLQ、manual replayが必要 |
| Usage bucket | 公開client quotaの現行entityなし | GAP | GAP。multi-nodeでのatomic counter/window、tenant/client/IP次元が必要 |

原則: 新しい第二outboxは既存outboxの不備を修正せずに追加しない。notification、accounting、
公開webhook deliveryの共通化/分離をDG-05後のF1で決定する。

## 6. Correlation ID inventory

| 現行箇所 | 確認結果 | NF-05判断 |
|---|---|---|
| Freee API | 呼出側がcorrelationIdを受け、X-Correlation-IDへ伝播。provider request IDも保存 | 参考。公開requestの入口生成・検証・response headerは未実装 |
| Accounting job | providerRequestId、payload hash、job idを保持 | 参考。公開client request、delivery、inbound eventを同一traceへ結ぶ必要 |
| Expense/Attendance | provider callへcorrelationIdを渡す経路あり | 参考。global MDC/filterの代替ではない |
| AI/Compliance | traceId/correlationIdを業務recordへ保存する個別経路あり | 参考。横断公開API correlation contractは未実装 |
| HTTP filter/MDC | correlation ID専用filter、MDC設定・finally解除を確認できない | BLOCKER。edge filter、validation、log/audit/worker propagationをF1/F2で設計 |

## 7. Rate limiter / IP inventory

| 対象 | 現行方式 | 限界 |
|---|---|---|
| PortalRateLimiterImpl | ConcurrentHashMap + key別ArrayDeque、固定1分window | JVMローカル。multi-node共有なし。eviction上限、memory pressure、client quotaなし |
| PortalRateLimitFilter | login/inviteはresolved client IP、download/upload/acceptanceはportal user | 公開APIのclient、scope、method/resource、burst、Retry-After、quotaとは別 |
| CloudSignRateLimiter | token単位、process内deque、最大800/minを既定500以下へ | provider専用。公開client rate boundaryに流用しない |
| ExportConcurrencyLimiter | static Semaphore、process内2 permits既定 | concurrency制限のみ。公開API quotaや公平性を保証しない |
| ClientIpResolver | trusted proxyのときのみX-Forwarded-For先頭値を採用 | trusted proxy list、forwarded chain、spoof、IPv6、unknownをDG-05で受入 |
| 公開client rate | 専用実装なし | GAP/BLOCKER。client×scope×IP×tenantの境界をDB/Redis等で選択 |

## 8. External DTO inventory

| DTO群 | 主用途 | 再利用判断 |
|---|---|---|
| dto.portal.* | portal向けの顧客/BP/請求/契約/検収 DTO | REUSE-CANDIDATEの設計例。ただしportal principalと公開client scopeは別 |
| dto.accounting.canonical.* | freee向けcanonical payload/result | internal provider契約。公開responseとしては使用しない |
| dto.contract / engineer / project / invoice | internal UI/APIのresponse shaping | field inventoryとscopeを再確認し、公開DTOへコピーする |
| dto.report / dashboard / analytics | 管理画面集計 | 公開APIのscope・freshness・金額口径を別途承認 |
| InvoiceDetailDto | Invoiceをextendsする既存DTO | 使用禁止。internal entity serializationの危険な前例 |
| Entity全般 | MyBatis-Plus persistence model | 使用禁止。external mapperでallow-list DTOへ変換 |

## 9. 公開resource / field / operation matrix（未承認候補）

この表はDG-05へ提示する候補inventoryであり、公開許可ではない。fieldはallow-listを先に定義し、
未記載fieldをdenyする。internal numeric IDではなく、client/tenantにbindしたopaque public IDを
使用する想定も未承認である。

| Resource | Candidate operation | Candidate allow-list field | Client scope | Data scope | Command permission | 状態 |
|---|---|---|---|---|---|---|
| engineer-availability | list, detail | publicEngineerId, availabilityStatus, availableFrom, availableTo, skillTagCode（表示許可されたcanonical codeのみ） | integration.availability.read | tenant + legal entity + engineer allow-list | integration.engineer-availability.read | UNAPPROVED |
| project | list, detail, count | publicProjectId, status, startDate, endDate, publicCustomerId（customer nameは別承認） | integration.project.read | tenant + legal entity + project/customer allow-list | integration.project.read | UNAPPROVED |
| contract-status | list, detail, count | publicContractId, publicProjectId, status, startDate, endDate, renewalStatus | integration.contract-status.read | tenant + legal entity + contract/project allow-list | integration.contract-status.read | UNAPPROVED |
| invoice-status | list, detail, count | publicInvoiceId, publicContractId, status, issueDate, dueDate, paidAt, settlementStatus | integration.invoice-status.read | tenant + legal entity + invoice/customer allow-list | integration.invoice-status.read | UNAPPROVED |
| command surface | TBD | TBD。read resourceに含めない | integration.command.* | commandごとにtarget scope | integration.<resource>.<operation> | BLOCKED_BY_DG05 |
| export | 禁止候補（初期） | 原則なし。必要性・最大件数・same-scopeをDG-05で再審査 | resource.read + export scope | 同一resource scope | integration.<resource>.export | UNAPPROVED |

明示的に公開しない候補:

- password、OAuth token、API key、TOTP/recovery code、暗号文、secret ref。
- internal DB id、role、permission group、audit actor、raw SQL、internal path、stack trace、
  provider raw body、provider credential、DLQ内部エラー。
- 氏名、email、電話、住所、口座、個人番号、文書本文、添付、原価、粗利、単価、銀行情報。
- 未承認のcustomer name、engineer name、skill free text、invoice amount。

## 10. Webhook field / operation matrix（未承認候補）

| 種別 | direction | field allow-list | scope/permission | 状態 |
|---|---|---|---|---|
| resource.changed | outbound | eventId, eventType, schemaVersion, createdAt, publicResourceId, changedFieldNames（allow-list）, payload, correlationId, timestamp, signature, keyVersion | subscription scope + integration.webhook.deliver | UNAPPROVED |
| provider event | inbound | providerEventId, provider, eventType, receivedAt, rawBodyHash, canonicalPayload, signatureResult, processingStatus, resultCode | client binding + integration.webhook.receive | UNAPPROVED |
| DLQ replay | admin command | eventId, replayGeneration, reason（入力）、resultCode | integration.webhook.replay + target scope | BLOCKED_BY_DG05 |

## 11. Test / evidence inventory

| 領域 | 必須証拠 |
|---|---|
| security | client A/B、scope/data scope/command permission、revoked/expired、rotation overlap/revoke、IP spoof、rate boundary |
| contract | OpenAPI lint、JSON allow-list、禁止field不在、entity serialization negative test、cursor/count/error non-enumeration |
| idempotency | same key/same payload same result、same key/different payload reject、concurrent claim、DB restart |
| outbound | signed payload、timestamp、provider request ID、claim競合、timeout、429/5xx backoff、4xx no-retry、DLQ/replay |
| inbound | signature、raw hash、timestamp、duplicate、event conflict、unique provider event ID、transaction rollback |
| operations | key rotation、secret/PII scan、負荷、DB/worker/provider障害、restore、runbook、alert |
| boundary | external callがDB transaction外、通常checkout無変更、base/head固定、push後remote/local一致 |
 
