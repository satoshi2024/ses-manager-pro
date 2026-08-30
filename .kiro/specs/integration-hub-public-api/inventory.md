# NF-05 Discovery Inventory

## 1. Inventoryの読み方

状態は次の意味で使う。

- EXISTING: 現行コードで確認できる正本または既存経路。
- REUSE-CANDIDATE: 契約を満たすよう拡張できる候補。ただしそのまま流用しない。
- DECIDED_SEPARATE: 既存経路を変更・二重書込みせず、NF-05専用保存モデルへ分離する決定済み事項。
- CONTRACT_FIXED: 保存・状態・遷移の仕様を正本へ固定済みだが、production実装は未着手。
- GAP: 公開APIに必要だが現行実装で確認できない。
- BLOCKER: 承認、設計、または既存境界との不整合により実装開始を止める事項。
- UNAPPROVED: 承認scopeに含まれず、実装・公開できない項目。

## 2. Repository / worktree / base

| 項目 | 確認結果 |
|---|---|
| 通常checkout | C:\work\ses-manager-pro |
| 通常branch | fix-ui-rendering-and-tests |
| 通常checkout status | 既存未コミット変更あり。NF-05では変更していない |
| remote | origin = https://github.com/satoshi2024/ses-manager-pro.git |
| Discovery worktree | C:\work\ses-manager-pro-integration-hub-public-api |
| Discovery branch | codex/integration-hub-public-api |
| approved Base | origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd |

## 2.1 Scope expansion decisionとwave状態

| 項目 | 正本値 |
|---|---|
| DecisionId | DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02 |
| Decision date / Owner | 2026-08-30 / PROJECT_OWNER（ROLE） |
| Scope expansion approval reviewed Head | 7e50bf1360ea8d7271acc0667593635451300268（承認時点の履歴値） |
| F1 | PLAN PASS / IMPLEMENTATION PASS。再オープンしない |
| F2 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING。Implementation FAIL後の8 findingをremediate済み、独立再Review待ち |
| A1 | APPROVED_SEQUENCED。F2 Review PASS後にGET-only 11 pathsを実装 |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION。approved command=0件、command/exportはdefault deny |
| B1 | APPROVED_SEQUENCED。A1 Review後、mock/stub/loopbackのみ |
| B2 | APPROVED_SEQUENCED。B1 Review後、production受信enablementなし |
| M | APPROVED_SEQUENCED。B2 Review後にsecurity/回復/性能/scan/runbookを実施 |
| 禁止 | production enablement、実顧客credential、実provider送信、force push、main変更、PR、merge |

### 2.2 現在のscope expansion Plan delta

固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcの独立Plan deltaはPLAN FAIL
（P0=0、P1=4、P2=2）となり補正した。固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13の
再ReviewもPLAN FAIL（P0=0、P1=3、P2=0）である。F1のPLAN/IMPLEMENTATION PASS、Owner Gate、
0R/0R-DおよびP1-EXP-004/P2-EXP-005/006の対応状態は再オープンしない。3契約の再補正後、
固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPlan delta PASSを受領し、F2実装を開始した。

| finding | inventoryで固定する契約 | status |
|---|---|---|
| P1 security boundary | trusted proxy/IP/CIDR→HMAC（nonce未永続化）→nonce commit、専用audit、401/403/CORS/CSRF/error boundary | SPEC_REMEDIATION |
| P1 canonicalTarget | OpenAPI wire header、raw target取得元、path/query完全再構築、空値、header/target/body上限、Content-Encoding、signature 32-byte、golden vector | SPEC_REMEDIATION |
| P1 disabled startup | false/MOCKの明示profile、missing拒否、deny-only chain、controller/worker/scheduler/transport bean条件 | SPEC_REMEDIATION |

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

### 3.1 F2 dedicated chain境界（remediation済み、独立再Review待ち）

| 項目 | 契約 |
|---|---|
| chain | externalApiSecurityFilterChain、@Order(0)、`/external-api/v1` と `/external-api/v1/**`（rootを含む） |
| 排他 | portal/internal chain、/api/webhooks/**、/loginとmatcherを重複させない |
| 順序 | correlation+audit開始→size/raw target→trusted proxy/source IP→HMAC→client principal後のclient CIDR→nonce atomic commit→scope/data scope/command→rate/quota→全decision監査→controller |
| audit | ExternalApiAuditBoundaryが認証前/後principal、allow-list route template、GETを含む全成功/error/rejectを一件化。認証前はUNAUTHENTICATED、未一致はEXTERNAL_UNKNOWN_ROUTE。既存ApiAuditFilterは内部更新系でありexternal監査の正本にしない |
| error boundary | CSRF/CORS/anonymousをexternal境界で明示拒否。専用401/403 stable JSON entrypoint（AUTHENTICATION_FAILED/FORBIDDEN_SCOPE）、correlation header、internal form/error fall-through禁止 |
| state | STATELESS、NullSecurityContextRepository、request cache無効、session/form/basic/OIDC/anonymous継承なし |
| deny | 承認済みGET allow-list以外とunknown path/methodはanyRequest().denyAll() |
| disabled | public-api.enabled=falseでもdeny-only chainを生成し、controller/worker/scheduler/transportを生成せずinternal/portalへfall-throughさせない |
| registration | HMAC/ExternalApiAuditBoundaryをServlet自動登録せず、各request一回だけ実行。外部filter全件はFilterRegistrationBeanでdisable |
| destination | MOCK/STUB（無接続）またはliteral loopback allow-list portのみ。redirect/proxy/DNS/non-loopbackを拒否 |

### 3.2 HMACと起動fail-closedの実装入力（実装済み）

canonical bytesはOpenAPI candidateのwire header（X-Client-ID、X-Credential-Version、X-Key-ID、
X-Timestamp、X-Nonce、X-Client-Signature）、raw request-target取得元、path/query split/rebuild、
値なし/空値、encoded byte順sort、固定field/LF framing、raw body SHA-256、Content-Encoding、
header/target/body上限、base64url decode後32-byte制約までdesign 3.1のgolden vectorへ固定する。
productionは明示設定の
public-api=false、external-transport=false、provider.mode=MOCKのみとし、missing/unknown/conflicting
configは起動拒否する。disabled時もdeny-only chainを残し、controller/worker/scheduler/transportを
生成しない。LOOPBACKは127.0.0.1または[::1]のliteralとallow-list portだけで、connection直前の
socket peerも検証する。
| 自動登録抑止 | FilterRegistrationBeanで内部filterをdisable | SecurityConfig.java:65-106 | 外部filter全件もFilterRegistrationBeanでdisableし、SecurityFilterChainへの明示登録と二重登録試験を実施 |

F2実装証跡は専用packageとF2 testsに限定し、A1 controller、B1/B2 provider transport、production enablementは未着手である。

### 3.3 F2 Implementation Review remediation inventory

| finding | 実装正本 | 境界・検証 | status |
|---|---|---|---|
| raw request-target供給 | `ExternalApiRawRequestTargetValve`、`ExternalApiTomcatConfiguration` | Tomcat connectorの`T_BYTES`だけをimmutable copyし、servlet normalized URI/queryを使わない。manual attributeなしのenabled connector E2Eを追加 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| client×route data scope | `ExternalApiDataScope`、`ExternalApiEffectiveScope`、`ExternalApiAuthorizationFilter` | strict typed allow-list、intersection、tenant/legal entity binding、immutable request context。malformed/empty/duplicate/wildcardをdeny | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| external audit | `ExternalApiAuditTrail`、`ExternalApiAuditBoundary`、`ExternalApiAuditService`、`ExternalApiAudit`、V130 | correlation、pre/post principal、credential version/key ID、全decision、route templateを一request一record。永続化失敗は500 fail-closed | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| IP/CIDR | `ExternalApiCidrMatcher` | DNSなしstrict literal parser、IPv4/IPv6/mapped IPv6、曖昧表記拒否 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| metrics | `ExternalApiMetricsRecorder` | route/method/status class/outcome/client tierのみ有限label、識別子label禁止、scrape test | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| namespace root | `ExternalApiRouteCatalog`、`ExternalApiSecurityConfig` | `/external-api/v1`をexact matcher、filter、audit、correlationの同一専用境界で処理 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| authoritative scope | `ExternalApiDataScope`、`ExternalApiEffectiveScope`、`ExternalApiAuthorizationFilter` | tenant/legal entityのexplicit exact singleton照合、空intersection保持、未指定時もprincipal singletonをeffective populationへ注入 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |
| mapped CIDR | `ExternalApiCidrMatcher` | `::ffff:0:0/96`のsource/CIDRを4-byte IPv4へcollapseし、prefix 96〜128を0〜32へ変換 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING |

## 4. Secret encryption / rotation inventory

| 対象 | 現行方式 | 行/契約 | NF-05判断 |
|---|---|---|---|
| accounting IntegrationConnection | encryptedTokens、AES/GCM、token_version、refresh lease、Fencing CAS | IntegrationConnection.java、IntegrationConnectionMapper.java | REUSE-CANDIDATE。token世代とcrypto key versionは別。API credential envelopeを新たに定義する |
| FreeeIntegrationService | AES/GCMの設定key、refreshは短TX→外部HTTP→CAS書戻し | FreeeIntegrationServiceImpl.java:1331-1429, 1467-1492 | provider/job境界の参考。単一設定keyを公開credential保管へ流用しない |
| Portal MFA | AES/GCM、key version検証、decrypt失敗拒否 | PortalMfaServiceImpl.java:197-215 | version付き復号の参考。portal user secretとAPI client secretを同一entityへ混ぜない |
| 内部MFA | key version、current-key-version、rotation用keyring設定 | MfaServiceImpl.java、application.yml | REUSE-CANDIDATE。overlap期間、旧key失効、re-encrypt jobの契約を公開credentialで再確認 |
| Compliance credential | provider経由のversion付きAES/GCM envelope、masked snapshot | ComplianceGateCredentialCryptoServiceImpl.java | REUSE-CANDIDATE。secret原文非表示・safe errorのパターンを採用 |
| 銀行口座 | encryptedAccountNumber、prod key必須 | BpCompanyServiceImpl.java | 公開field候補に含めない |
| 公開API credential | 専用entity、AES-256-GCM envelope、AAD binding、rotation/revoke/expiry service | `ApiClient`、`CredentialVersion`、`CredentialVersionServiceImpl`、`IntegrationHubSecretCryptoService` | CONTRACT_FIXED。原文非表示、24時間overlap、即時revoke、90日expiryを実装済み（F1独立Implementation Review PASS） |

F1実装で固定する承認値:

1. AES-256-GCM envelope、環境注入keyring、AAD（clientId/credentialVersion/purpose）、hashまたは参照。
2. rotation overlap 24時間、revoke即時、credential expiry 90日、decrypt failureはfail-closed。
3. secret原文は発行時一度だけ表示し、再表示・ログ・監査・metrics・exception保存を禁止する。

## 5. Outbox / provider / job / idempotency inventory

| 対象 | 現行方式 | 行/契約 | NF-05判断 |
|---|---|---|---|
| NotificationOutbox | t_notification_outbox、dedupe unique、PENDING/PROCESSING/RETRY/SENT/FAILED | V79__notification_webhook_outbox.sql | DECIDED_SEPARATE。アプリ内notification専用として変更・二重書込みしない |
| NotificationOutbox claim | status条件付きUPDATE、attempt count、locked_at | NotificationOutboxMapper.java | claimの参考。lease token、tenant/client binding、generationが不足 |
| Notification dispatcher | REQUIRES_NEW dispatchOne内でclaim→外部notifyNow→結果update | NotificationOutboxDispatcher.java:44-70 | 既存gap。NF-05 F1/B1ではclaim TX、HTTP、result CASを分離する |
| Accounting IntegrationJob | payloadSnapshot、payloadHash、idempotencyKey、leaseToken、leaseExpiresAt、providerRequestId、安全なerror | IntegrationJob.java | DECIDED_SEPARATE。会計provider専用としてNF-05 deliveryへ流用・二重書込みしない |
| Accounting worker | due job claim、provider dispatch、stale lease recovery、provider request ID | AccountingIntegrationWorker.java | REUSE-CANDIDATE。公開API deliveryと業務会計jobの責務分離を決める |
| Accounting provider | providerName、canonical DTO、外部I/Oはtransaction外の契約 | AccountingProvider.java | provider adapter境界の参考。公開API DTOをcanonical会計DTOへ流用しない |
| Accounting idempotency | snapshot bytesのSHA-256、業務job unique、状態CAS | IntegrationJobMapper.java、各integration service | REUSE-CANDIDATE。同key異payload conflictの外部契約を追加検証 |
| NF-05 delivery ledger | event_id、subscription_id、delivery_generation、external DTO snapshot、payload hash、lease/CAS、retry、DLQ | `ApiDelivery`、`ApiDeliveryMapper`、`ApiDeliveryServiceImpl`、`t_api_delivery` | DECIDED_SEPARATE。NF-05専用ledgerを実装済み。第二の汎用outboxは作らない（F1独立Implementation Review PASS） |
| Inbound event | provider event ID、raw hash、allow-listed parsed fields、processing result、retention expiry | `InboundEvent`、`InboundEventMapper`、`InboundEventServiceImpl`、`t_inbound_event` | CONTRACT_FIXED。unique/conflict/claim/CAS基盤を実装済み（F1独立Implementation Review PASS、外部受信・DLQ UIは未着手） |
| Nonce replay ledger | client、credential version、nonce hash、accepted/expiry時刻 | `ApiNonceReplay`、`ApiNonceReplayServiceImpl`、`t_api_nonce_replay` | CONTRACT_FIXED。client+nonce hash unique、TTL bounded purge、raw nonce非保存基盤を実装済み（F1独立Implementation Review PASS） |
| Retention hold/checkpoint | record kind/id、ACTIVE/RELEASED、generation/version、restore epoch、expires-at cursor | `ApiRetentionPurgeServiceImpl`、`t_api_retention_hold`、`t_api_purge_checkpoint` | CONTRACT_FIXED。checkpoint→target→holdの共通lock順序、hold/purge CAS、active lease strict predicate、restore再評価をF1へ実装済み（F1独立Implementation Review PASS） |
| Usage bucket | client×scope×tenant×route template、minute/day counter、capacity 20 token、3秒ごとに1 token refill、burst state | `ApiUsageBucket`、`ApiUsageBucketMapper`、`ApiUsageBucketServiceImpl`、`t_api_usage_bucket` | CONTRACT_FIXED。IP/raw pathを保存キーにせず、DB unique upsert＋READ COMMITTEDの短いtransaction、FOR UPDATE/条件付きincrement、限定deadlock retryでmulti-node atomicityを実装済み（F1独立Implementation Review PASS） |
| Canonical state/retention | idempotency/delivery/inboundのenum、遷移、terminal class/起算点 | 現行NF-05状態entityなし | CONTRACT_FIXED。RETRYABLE等のcanonical名、逆遷移拒否、全terminalの30/90日mappingをF1へ固定する |

原則: 新しい第二の汎用outboxは作らない。既存notification outboxとAccounting IntegrationJobは責務を維持し、
NF-05は互換性のないretention、scope、lease、replay世代を持つためt_api_deliveryへ分離する。同じeventを
既存outboxへ複製せず、業務stateとt_api_delivery rowだけを同一transactionでatomic commitする。

## 6. Correlation ID inventory

| 現行箇所 | 確認結果 | NF-05判断 |
|---|---|---|
| Freee API | 呼出側がcorrelationIdを受け、X-Correlation-IDへ伝播。provider request IDも保存 | 参考。公開requestの入口生成・検証・response headerは未実装 |
| Accounting job | providerRequestId、payload hash、job idを保持 | 参考。公開client request、delivery、inbound eventを同一traceへ結ぶ必要 |
| Expense/Attendance | provider callへcorrelationIdを渡す経路あり | 参考。global MDC/filterの代替ではない |
| AI/Compliance | traceId/correlationIdを業務recordへ保存する個別経路あり | 参考。横断公開API correlation contractは未実装 |
| HTTP filter/MDC | F2で専用correlation filterを追加し、全responseへheaderを付与 | F2 IMPLEMENTATION_REMEDIATION_REVIEW_PENDING。worker propagationはB1/B2で実装 |

## 7. Rate limiter / IP inventory

| 対象 | 現行方式 | 限界 |
|---|---|---|
| PortalRateLimiterImpl | ConcurrentHashMap + key別ArrayDeque、固定1分window | JVMローカル。multi-node共有なし。eviction上限、memory pressure、client quotaなし |
| PortalRateLimitFilter | login/inviteはresolved client IP、download/upload/acceptanceはportal user | 公開APIのclient、scope、method/resource、burst、Retry-After、quotaとは別 |
| CloudSignRateLimiter | token単位、process内deque、最大800/minを既定500以下へ | provider専用。公開client rate boundaryに流用しない |
| ExportConcurrencyLimiter | static Semaphore、process内2 permits既定 | concurrency制限のみ。公開API quotaや公平性を保証しない |
| ClientIpResolver | trusted proxyのときのみX-Forwarded-For先頭値を採用 | trusted proxy list、forwarded chain、spoof、IPv6、mapped IPv6 family、unknownをF1/F2で受入。IPはrate保存キーへ含めない |
| 公開client rate | ExternalApiAuthorizationFilterからF1 ApiUsageBucketServiceを呼出し | F2 IMPLEMENTATION_REMEDIATION_REVIEW_PENDING。保存キーはclient×scope×tenant×route templateのみ。60 req/min、burst 20、日次50,000をDB atomic counterで適用 |

## 8. External DTO inventory

| DTO群 | 主用途 | 再利用判断 |
|---|---|---|
| dto.portal.* | portal向けの顧客/BP/請求/契約/検収 DTO | REUSE-CANDIDATEの設計例。ただしportal principalと公開client scopeは別 |
| dto.accounting.canonical.* | freee向けcanonical payload/result | internal provider契約。公開responseとしては使用しない |
| dto.contract / engineer / project / invoice | internal UI/APIのresponse shaping | field inventoryとscopeを再確認し、公開DTOへコピーする |
| dto.report / dashboard / analytics | 管理画面集計 | 公開APIのscope・freshness・金額口径を別途承認 |
| InvoiceDetailDto | Invoiceをextendsする既存DTO | 使用禁止。internal entity serializationの危険な前例 |
| Entity全般 | MyBatis-Plus persistence model | 使用禁止。external mapperでallow-list DTOへ変換 |

## 9. 公開resource / field / operation matrix（Owner承認済み初期契約）

この表はDG-05-F1-APPROVAL-20260830-01で承認された初期契約である。実装時もfieldはallow-listを先に定義し、
未記載fieldをdenyする。internal numeric IDではなく、client/tenantにbindしたopaque public IDを使用する。

| Resource | Approved operation | Approved allow-list field | Client scope | Data scope | Command permission | 状態 |
|---|---|---|---|---|---|---|
| engineer-availability | list, detail | publicEngineerId, availabilityStatus, availableFrom, availableTo, skillTagCode（表示許可されたcanonical codeのみ） | integration.availability.read | tenant + legal entity + engineer allow-list | integration.engineer-availability.read | APPROVED |
| project | list, detail, count | publicProjectId, status, startDate, endDate, publicCustomerId（customer nameは別承認） | integration.project.read | tenant + legal entity + project/customer allow-list | integration.project.read | APPROVED |
| contract-status | list, detail, count | publicContractId, publicProjectId, status, startDate, endDate, renewalStatus | integration.contract-status.read | tenant + legal entity + contract/project allow-list | integration.contract-status.read | APPROVED |
| invoice-status | list, detail, count | publicInvoiceId, publicContractId, status, issueDate, dueDate, paidAt, settlementStatus | integration.invoice-status.read | tenant + legal entity + invoice/customer allow-list | integration.invoice-status.read | APPROVED |
| command surface | disabled | なし | integration.command.* | 適用なし | NOT_APPLICABLE_UNDER_CURRENT_DECISION（approved command=0件） | DISABLED |
| export | disabled | なし | 適用なし | 適用なし | NOT_APPLICABLE_UNDER_CURRENT_DECISION | DISABLED |

明示的に公開しないdeny-list:

- password、OAuth token、API key、TOTP/recovery code、暗号文、secret ref。
- internal DB id、role、permission group、audit actor、raw SQL、internal path、stack trace、
  provider raw body、provider credential、DLQ内部エラー。
- 氏名、email、電話、住所、口座、個人番号、文書本文、添付、原価、粗利、単価、銀行情報。
- customer name、engineer name、skill free text、invoice amount。

## 10. Webhook field / operation matrix（契約承認済み・順次実装）

| 種別 | direction | field allow-list | scope/permission | 状態 |
|---|---|---|---|---|
| resource.changed | outbound | eventId, eventType, schemaVersion, createdAt, publicResourceId, changedFieldNames（allow-list）, payload, correlationId, timestamp, signature, keyVersion | subscription scope + integration.webhook.deliver | APPROVED_SEQUENCED（B1） |
| provider event | inbound | providerEventId, provider, eventType, receivedAt, rawBodyHash, canonicalPayload, signatureResult, processingStatus, resultCode | client binding + integration.webhook.receive | APPROVED_SEQUENCED（B2） |
| DLQ replay | admin command | eventId, replayGeneration, reason（入力）、resultCode | integration.webhook.replay + target scope | APPROVED_SEQUENCED（B2 admin UI） |

## 11. Test / evidence inventory

| 領域 | 必須証拠 |
|---|---|
| security | client A/B、scope/data scope/command permission、revoked/expired、rotation overlap/revoke、IP spoof、rate boundary |
| contract | OpenAPI lint、JSON allow-list、禁止field不在、entity serialization negative test、cursor/count/error non-enumeration |
| idempotency | same key/same payload same result、same key/different payload reject、concurrent claim、DB restart、canonical state/terminal mapping |
| nonce | atomic unique、credential rotation跨ぎの再利用拒否、TTL境界、bounded purge、raw nonce非永続化 |
| rate | subject key exact、capacity 20、3秒refill、refill直前/直後、minute/day境界、clock rollback、Retry-After、atomic partial failure |
| outbound | signed payload、timestamp、provider request ID、claim競合、timeout、429/5xx backoff、4xx no-retry、DLQ/replay |
| inbound | signature、raw hash、timestamp、duplicate、event conflict、unique provider event ID、transaction rollback |
| operations | key rotation、secret/PII scan、負荷、DB/worker/provider障害、restore、runbook、alert |
| metrics | route template、method、status class、bounded outcome、client tierのみ。client/correlation/request/resource/user/IP/provider IDはlabel禁止 |
| delivery architecture | t_notification_outbox・Accounting IntegrationJobへの二重書込みなし。t_api_delivery分離、event/subscription/generation unique、atomic insert、claim/HTTP/CAS |
| retention | idempotency digest/safe snapshot、inbound hash/allow-list fields、outbound external DTO snapshot。retention class/expiry、succeeded 30日、failed/DLQ 90日、audit metadata 1年 |
| purge | t_api_retention_holdのlock/CAS、active lease競合、期限境界、再実行、部分失敗、restore epoch後の全件再評価の証拠が必要 |
| boundary | external callがDB transaction外、通常checkout無変更、base/head固定、push後remote/local一致 |

F1実装後の証跡更新:

- `5a2a023178433882bc1c5dcf92e19b5ecfa19db6`で、snapshotのfield-specific構造検証、delivery leaseのfail-closed、
  checkpoint→target→hold lock順序、quota初期化競合のupsert/短transactionを実装した。
- `96d6801c37d4b952e2601a06cf7edc1bc1a1bef8`で、snapshotのpublic ID、date/date-time、status/resultCode、
  signature/processing status、error codeのfield固有pattern/enum、nested深度、配列項目検証を追加し、許可field内の
  raw JSON/provider body scalar埋込みをnegative testで拒否した。
- H2 F1 targeted suiteは31 tests、MySQL `IntegrationHubF1MySqlConcurrencyTest`は5 testsで、いずれもfailure/error/skipなし。
- 独立Reviewの固定Head `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`ではP1-FU-001のみ残り、FU-002〜004はクローズ済みだった。`96d6801c`後の
  固定Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`は独立Implementation Review PASS（P0/P1/P2=0）である。
- F1 persistence基盤はImplementation PASS済み。Plan deltaはca27f455でPASSし、F2は再ReviewのP1/P2追加指摘をa16cdcbaでremediate済み（独立再Review待ち）。A1/B1/B2/Mは各wave Review後に順次実装する。
  A2はN/A、production enablement、実顧客credential、実provider送信は引き続き禁止する。
