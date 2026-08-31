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
| F2 | IMPLEMENTATION_PASS。fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`、P0/P1/P2=0/0/0 |
| A1 | IMPLEMENTATION_PASS。fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`、P0/P1/P2=0/0/0 |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION。approved command=0件、command/exportはdefault deny |
| B1 | IMPLEMENTATION_PASS。独立再Review fixed Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`、P0/P1/P2=0/0/0。mock/stub/loopbackのみ |
| B2 | IMPLEMENTATION_REVIEW_PENDING。initial `122c7c3b`、remediation `cc468e4f`。provider/resource/admin/content-type境界を補正。production受信enablementなし |
| M | APPROVED_SEQUENCED。B2 Review後にsecurity/回復/性能/scan/runbookを実施 |
| 禁止 | production enablement、実顧客credential、実provider送信、force push、main変更、PR、merge |

### 2.2 現在のscope expansion Plan delta

固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcの独立Plan deltaはPLAN FAIL
（P0=0、P1=4、P2=2）となり補正した。固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13の
再ReviewもPLAN FAIL（P0=0、P1=3、P2=0）である。F1のPLAN/IMPLEMENTATION PASS、Owner Gate、
0R/0R-DおよびP1-EXP-004/P2-EXP-005/006の対応状態は再オープンしない。3契約の再補正後、
固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPlan delta PASSを受領し、F2実装を開始した。F2はfixed Head
`d022e60039880dc5d4743f336661819cda7fc3f4`で独立Implementation Review PASSを受領し、A1を実装した。A1はfixed Head
`69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASSを受領し、B1を`971c17d7`で実装した。初回B1 Reviewは
fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`でFAIL（P0=0/P1=4/P2=1）となったため、`30199db8`でremediateした。再Review fixed Head
`29d749bb6db1aad9ca98a9dd253b30d375dbba5c`のP1=2は`2684ff8f1303b6d0cc6550882601405d3d78f3b2`でremediateしている。

| finding | inventoryで固定する契約 | status |
|---|---|---|
| P1 security boundary | trusted proxy/IP/CIDR→HMAC（nonce未永続化）→nonce commit、専用audit、401/403/CORS/CSRF/error boundary | SPEC_REMEDIATION |
| P1 canonicalTarget | OpenAPI wire header、raw target取得元、path/query完全再構築、空値、header/target/body上限、Content-Encoding、signature 32-byte、golden vector | SPEC_REMEDIATION |
| P1 disabled startup | false/MOCKの明示profile、missing拒否、deny-only chain、controller/worker/scheduler/transport bean条件 | SPEC_REMEDIATION |

### 2.3 A1 implementation remediation inventory

| finding | inventoryで固定する契約 | 実装/evidence | status |
|---|---|---|---|
| invoice customer scope | invoiceIds × customerIdsをlist/detail/countへ同一predicate。複数contractは単一publicContractIdを返さない | `ExternalApiReadMapper`、`ExternalApiReadRow.contractCount`、mapper/service tests | IMPLEMENTATION_PASS（A1独立Review） |
| cursor visible population | 初回as-ofのmembershipとallow-list DTO値を`t_api_read_snapshot`/itemへ保存し、snapshot IDをcursorへbind。as-ofは秒精度で固定 | V131、H2 schema、`ExternalApiReadSnapshotMapper`、snapshot integration/pagination precision test | IMPLEMENTATION_PASS（A1独立Review） |
| cursor token encoding | paddingなしBase64URL、decode後canonical再encode完全一致、unused bits拒否 | `ExternalApiCursorCodec`、tamper test | IMPLEMENTATION_PASS（A1独立Review） |
| external contract evidence | 4 DTO allow-list、11 GET-only paths、entity negative、enabled E2E key fixture | `ExternalApiDtoContractTest`、`ExternalApiEnabledConnectorE2ETest` | IMPLEMENTATION_PASS（A1独立Review） |
| snapshot retention purge | expiry index順の最大32 headerを公開readと別schedulerでpurge。FK cascade、partial failure rollback、再実行、無通信時期限超過 | `ExternalApiReadSnapshotPurgeService`/`Scheduler`、H2 purge integration/service tests | IMPLEMENTATION_PASS（A1独立Review） |

### 2.4 B1 Implementation Review remediation inventory（独立再Review待ち）

| finding | inventoryで固定する契約 | 実装/evidence | status |
|---|---|---|---|
| B1-001 outbound signature/envelope | credential versionとprovider idempotency keyをcanonical framingへ含め、必須envelopeとdelivery ledgerを送信前に一致検証 | `IntegrationHubWebhookSigner`、`ExternalDtoSnapshot.requireOutboundEnvelope`、golden/tamper tests | SPEC_ADDRESSED（`30199db8`、独立再Review待ち） |
| B1-002 manual replay authorization | `integration.webhook.replay`、active client/subscription、current permission/data scope、tenant/legal entity、resource payload membershipを再取得・再計算 | `IntegrationHubWebhookReplayAuthorizationService`、revoked/scope narrowing/resource exclusion tests | SPEC_ADDRESSED（`30199db8` → `2684ff8f`、独立再Review待ち） |
| B1-003 retention lifecycle | replay auditをdelivery payloadから分離し、payload 30/90日とaudit metadata 1年を独立bounded purge。FKはdelivery削除を阻害しない | V133、H2/MySQL replay後purge tests | SPEC_ADDRESSED（`30199db8`、独立再Review待ち） |
| B1-004 worker timing/CAS | claim直前・HTTP完了後にclockを再取得し、leaseはtimeout超過、CAS障害はtransport retryへ変換しない | worker/property tests、slow transport/CAS failure tests | SPEC_ADDRESSED（`30199db8`、独立再Review待ち） |
| B1-005 failure/concurrency evidence | timeout、5xx、attempt 8/DLQ、stale recovery、同時claim、atomic rollback、replay後purgeを実DB経路で検証 | H2 retention、MySQL `IntegrationHubF1MySqlConcurrencyTest`、worker tests | SPEC_ADDRESSED（`30199db8`、独立再Review待ち） |
| B1-006 replay operator authorization | 呼出側operatorRefを受け取らず、認証済み内部`LoginUser`、`ROLE_管理者`、`integration.webhook.replay`をservice boundaryで検証し、導出referenceだけをauditへ渡す | `IntegrationHubWebhookReplayAuthorizationServiceImpl`、未認証/非admin/permission拒否/derived operator tests | SPEC_ADDRESSED（`2684ff8f`、独立再Review待ち） |
| B1-007 replay opaque resource binding | primary resource type/内部IDをdeliveryへbindし、`publicResourceId`はprimaryだけへ要求。secondaryは各専用public ID、current DBのdeleted/tenant/legal/parent predicateで再検証 | `ExternalApiPublicIdCodec`、V134、`IntegrationHubWebhookResourceScopeMapper`、project×customer・invoice×customer×contract・soft-delete/reparent/contract付替え tests | SPEC_ADDRESSED（`2684ff8f`残存P1-007を追加remediation、独立再Review待ち） |
| B1-008 initial delivery primary binding | enqueue保存前とworker外部HTTP前にclient bindingからprimary type/内部IDのHMAC opaque IDを再計算し、envelope `publicResourceId`とprimary DTO fieldを一致させる。DuplicateKey収束もpayload hash・primary type・primary IDを同時比較 | `IntegrationHubWebhookDeliveryBindingValidator`、`ExternalDtoSnapshot.requirePrimaryResourceBinding`、`ApiDeliveryServiceImpl`、`IntegrationHubWebhookDeliveryWorker`、type/ID mismatch・同時enqueue・送信前reject tests | SPEC_ADDRESSED（`c2cbfb99133d0df3f8d5eee285be340163747e31`、独立再Review待ち） |

### 2.5 B2 inbound / DLQ / admin UI implementation inventory

| 対象 | 実装正本 | 固定した契約・証跡 | status |
|---|---|---|---|
| inbound route | `ExternalApiInboundWebhookController`、`ExternalApiRouteCatalog`、既存external chain | `POST /external-api/v1/webhooks/{provider}`、HMAC/timestamp/nonce/CIDR/scope通過後のみcontroller、unknown route/method deny | IMPLEMENTATION_REVIEW_PENDING |
| inbound parser | `ExternalApiInboundWebhookParser` | strict JSON、top-level/field allow-list、provider/event ID一致、raw bytesは検証中memoryのみ、DBはhashとparsed snapshotのみ | IMPLEMENTATION_REVIEW_PENDING |
| duplicate/conflict | `InboundEventService`、`t_inbound_event` | client×provider×provider event ID unique、同hash duplicate、別hash `409 INBOUND_PAYLOAD_CONFLICT`、terminal逆遷移なし | IMPLEMENTATION_REVIEW_PENDING |
| processing/DLQ | `InboundEventService`、`NoopInboundEventProcessor` | claim/terminal CAS分離、B2は外部HTTP・business commandなし、processor失敗のみDLQ | IMPLEMENTATION_REVIEW_PENDING |
| admin replay | `InboundEventAdminServiceImpl`、`t_inbound_event_replay`、admin API/page | `ROLE_管理者`＋`integration.webhook.replay`、derived operator ref、generation、current binding再検証、元eventを逆遷移しない | IMPLEMENTATION_REVIEW_PENDING |
| retention | V135、`ApiRetentionPurgeServiceImpl` | replay metadata `AUDIT_METADATA_1Y`、元event purgeをFKで阻害しない、terminal bounded purge、safe projection only | IMPLEMENTATION_REVIEW_PENDING |
| evidence | `IntegrationHubB2InboundH2Test`、`ExternalApiInboundConnectorE2ETest`、`IntegrationHubB2MigrationContractTest`、`FlywayMigrationSmokeTest` | H2/connector/MySQL migrationのfailure/error/skipなし | IMPLEMENTATION_REVIEW_PENDING |

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

### 3.1 F2 dedicated chain境界（remediation済み、独立Review PASS）

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

F2実装証跡は専用packageとF2 testsに限定し、production enablementと実顧客/provider接続は未実施である。A1 controllerは`69f857d3`で独立Implementation Review PASS済み、B1 development/test transportとworkerは`971c17d7`で実装し、初回Review FAILを`30199db8`、再Review P1-006/P1-007を`2684ff8f`、残存P1-007を`5c94367c` → `0618d983`、NF05-IMPL-B1-008を`c2cbfb99133d0df3f8d5eee285be340163747e31`でremediate済み・独立再Review待ちである。

### 3.3 F2 Implementation Review remediation inventory（fixed Head `d022e600`でPASS）

| finding | 実装正本 | 境界・検証 | status |
|---|---|---|---|
| raw request-target供給 | `ExternalApiRawRequestTargetValve`、`ExternalApiTomcatConfiguration` | Tomcat connectorの`T_BYTES`だけをimmutable copyし、servlet normalized URI/queryを使わない。manual attributeなしのenabled connector E2Eを追加 | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| client×route data scope | `ExternalApiDataScope`、`ExternalApiEffectiveScope`、`ExternalApiAuthorizationFilter` | strict typed allow-list、intersection、tenant/legal entity binding、immutable request context。malformed/empty/duplicate/wildcardをdeny | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| external audit | `ExternalApiAuditTrail`、`ExternalApiAuditBoundary`、`ExternalApiAuditService`、`ExternalApiAudit`、V130 | correlation、pre/post principal、credential version/key ID、全decision、route templateを一request一record。永続化失敗は500 fail-closed | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| IP/CIDR | `ExternalApiCidrMatcher` | DNSなしstrict literal parser、IPv4/IPv6/mapped IPv6、曖昧表記拒否 | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| metrics | `ExternalApiMetricsRecorder` | route/method/status class/outcome/client tierのみ有限label、識別子label禁止、scrape test | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| namespace root | `ExternalApiRouteCatalog`、`ExternalApiSecurityConfig` | `/external-api/v1`をexact matcher、filter、audit、correlationの同一専用境界で処理 | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| authoritative scope | `ExternalApiDataScope`、`ExternalApiEffectiveScope`、`ExternalApiAuthorizationFilter` | tenant/legal entityのexplicit exact singleton照合、空intersection保持、未指定時もprincipal singletonをeffective populationへ注入 | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |
| mapped CIDR | `ExternalApiCidrMatcher` | `::ffff:0:0/96`のsource/CIDRを4-byte IPv4へcollapseし、prefix 96〜128を0〜32へ変換 | IMPLEMENTATION_PASS（F2 fixed Head `d022e600`） |

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
| HTTP filter/MDC | F2で専用correlation filterを追加し、全responseへheaderを付与 | F2 IMPLEMENTATION_PASS。B1 workerがcorrelation headerをoutboundへ伝播 |

## 7. Rate limiter / IP inventory

| 対象 | 現行方式 | 限界 |
|---|---|---|
| PortalRateLimiterImpl | ConcurrentHashMap + key別ArrayDeque、固定1分window | JVMローカル。multi-node共有なし。eviction上限、memory pressure、client quotaなし |
| PortalRateLimitFilter | login/inviteはresolved client IP、download/upload/acceptanceはportal user | 公開APIのclient、scope、method/resource、burst、Retry-After、quotaとは別 |
| CloudSignRateLimiter | token単位、process内deque、最大800/minを既定500以下へ | provider専用。公開client rate boundaryに流用しない |
| ExportConcurrencyLimiter | static Semaphore、process内2 permits既定 | concurrency制限のみ。公開API quotaや公平性を保証しない |
| ClientIpResolver | trusted proxyのときのみX-Forwarded-For先頭値を採用 | trusted proxy list、forwarded chain、spoof、IPv6、mapped IPv6 family、unknownをF1/F2で受入。IPはrate保存キーへ含めない |
| 公開client rate | ExternalApiAuthorizationFilterからF1 ApiUsageBucketServiceを呼出し | F2 IMPLEMENTATION_PASS。保存キーはclient×scope×tenant×route templateのみ。60 req/min、burst 20、日次50,000をDB atomic counterで適用 |

## 8. External DTO inventory

| DTO群 | 主用途 | 再利用判断 |
|---|---|---|
| dto.portal.* | portal向けの顧客/BP/請求/契約/検収 DTO | REUSE-CANDIDATEの設計例。ただしportal principalと公開client scopeは別 |
| dto.accounting.canonical.* | freee向けcanonical payload/result | internal provider契約。公開responseとしては使用しない |
| dto.contract / engineer / project / invoice | internal UI/APIのresponse shaping | field inventoryとscopeを再確認し、公開DTOへコピーする |
| dto.report / dashboard / analytics | 管理画面集計 | 公開APIのscope・freshness・金額口径を別途承認 |
| InvoiceDetailDto | Invoiceをextendsする既存DTO | 使用禁止。internal entity serializationの危険な前例 |
| Entity全般 | MyBatis-Plus persistence model | 使用禁止。external mapperでallow-list DTOへ変換 |

### 8.1 A1実装インベントリ（独立再Implementation Review待ち）

| 境界 | 実装正本 | 固定内容 |
|---|---|---|
| read controller/service | `ExternalApiReadController`、`ExternalApiReadService` | GET-only 11 paths、list/detail/count、server-clock as-of、同一immutable effective population |
| SQL boundary | `ExternalApiReadMapper` | deleted除外、allow-list列、scope ID predicate、ID-desc stable sort、limit+1 cursor。internal entityを返さない |
| response DTO | `ExternalApiEngineerAvailability`、`ExternalApiProject`、`ExternalApiContractStatus`、`ExternalApiInvoiceStatus` | inventory allow-listだけ。internal ID、secret、PII、金額/原価/粗利、provider raw bodyを持たない |
| opaque identity | `ExternalApiPublicIdCodec` | client/tenant/resourceへbindしたHMAC-SHA256 public ID。enabled時key未設定は起動拒否 |
| invoice scope | `ExternalApiReadMapper`、`ExternalApiReadService` | invoiceIds × customerIdsをlist/detail/countへ同一predicate。複数contract時は一意の場合だけpublicContractIdを返す |
| cursor | `ExternalApiCursorCodec`、`ExternalApiReadSnapshotMapper` | AES-GCM暗号化、client/tenant/legal entity/route/scope/snapshot/as-of/expiryへbind。初回visible membership/DTO値をV131 snapshotへmaterializeし、noncanonical Base64URLを拒否 |
| tests | `ExternalApiReadMapperIntegrationTest`、`ExternalApiReadSnapshotIntegrationTest`、DTO/path/entity tests、purge tests | remediation focused 23 tests、failure/error/skipなし。browser connector E2Eはcrypto fixture修正後もWindows loopback制約でHTTP assertion未到達 |

### 8.2 B1実装インベントリ（独立Implementation Review待ち）

| 境界 | 実装正本 | 固定内容 |
|---|---|---|
| delivery ledger | `ApiDeliveryServiceImpl`、`ApiDeliveryMapper`、`t_api_delivery` | 第二outboxなし。atomic enqueue、claim/lease、provider idempotency key・payload hash・generation・lease token付きCAS |
| subscription/signing | `WebhookSubscription`、`IntegrationHubWebhookSigner`、AES-GCM crypto abstraction | credential version/key ID、固定framing HMAC-SHA256、timestamp、correlation、secret平文非保存 |
| transport | `MockIntegrationHubWebhookTransport`、`StubIntegrationHubWebhookTransport`、`LoopbackIntegrationHubWebhookTransport` | MOCK/STUB無接続。LOOPBACKはliteral IP・allow-list port・peer検証、DNS/proxy/redirectなし |
| retry/DLQ/replay | `IntegrationHubWebhookDeliveryWorker`、`IntegrationHubWebhookDeliveryReplayServiceImpl`、`IntegrationHubWebhookReplayAuthorizationServiceImpl` | timeout/429/5xxのみ最大8回backoff+jitter、4xx no-retry、DLQ、新generation replay、admin permission、primary/secondaryごとのopaque ID再計算、current DB membership（deleted/parent）再検証、safe audit |
| schema/test | `V132__integration_hub_public_api_b1.sql`、`V133`、`V134__integration_hub_b1_primary_resource_binding.sql`、H2 schema、B1 focused tests | replay audit、scope digest、admin principal、primary binding、secondary専用public ID、project×customer/invoice×customer×contract、soft-delete/reparent、migration/H2、実loopback server、署名golden、設定fail-closed |

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
| resource.changed | outbound | eventId, eventType, schemaVersion, createdAt, publicResourceId（primary）、changedFieldNames（allow-list）, payload（primary/secondary各専用public ID）、correlationId, timestamp, signature, keyVersion | subscription scope + integration.webhook.deliver + current primary/secondary membership | IMPLEMENTATION_REMEDIATED_REVIEW_PENDING（B1、`30199db8` → `2684ff8f` → P1-007追加remediation → NF05-IMPL-B1-008 `c2cbfb99`、独立再Review待ち） |
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
| replay resource authorization | primary bindingと`publicResourceId`を一致させ、secondary専用opaque ID、current deleted_flag、tenant/legal singleton、parent relation、scope据置のsoft-delete/reparent/contract付替えをH2 mapper/serviceで検証 |
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
- F1 persistence基盤はImplementation PASS済み。Plan deltaはca27f455でPASSし、F2はfixed Head `d022e600`で独立Implementation Review PASS済み。A1はfixed Head `69f857d3`で独立Implementation Review PASS、B1は初回Review FAILを`30199db8`、再Review P1-006/P1-007を`2684ff8f`、残存P1-007を`5c94367c` → `0618d983`、NF05-IMPL-B1-008を`c2cbfb99133d0df3f8d5eee285be340163747e31`でremediate済み・独立再Review待ちであり、B2/Mは各wave Review後に順次実装する。
  A2はN/A、production enablement、実顧客credential、実provider送信は引き続き禁止する。

## B2 implementation remediation inventory

| 境界 | server-side正本 | allow-list / deny-list | 証跡 |
|---|---|---|---|
| provider/subscription | approved provider catalog、active client×provider×eventType subscription、receive permission、scope intersection | unknown provider、inactive/missing subscription、未承認eventTypeを拒否 | `IntegrationHubInboundProviderCatalog`、`InboundEventBindingValidator` |
| resource binding | primary type/内部ID、resource別opaque ID、現行tenant/legal/parent membership | soft-delete、reparent、scope narrowing、relation不一致を拒否 | `IntegrationHubInboundEventResourceScopeMapper`、H2 test |
| replay principal | 有効・非ロック`LoginUser`、内部user ID、ROLE_管理者、`integration.webhook.replay` | request supplied operator reference、anonymous、non-adminを拒否 | `InboundEventAdminServiceImpl` |
| admin reference | client-bound opaque event/replay reference | numeric `t_inbound_event.id`、raw body/hash、secret、PIIを非公開 | `InboundEventAdminReferenceCodec`、DTO/DOM/URL test |
| content type | single `application/json`、許可charsetのみ | jsonp、combined、malformed、未許可parameterを拒否 | controller parser/E2E contract |

B2 remediation codeは`cc468e4f`。独立Review受領まではIMPLEMENTATION_REVIEW_PENDINGであり、production受信enablementは行わない。

## B2 quota/error remediation inventory

| 境界 | canonical source | 契約 | status |
|---|---|---|---|
| quota route | `ExternalApiRouteCatalog.QUOTA_ROUTE_TEMPLATES` | read 11 template＋`/external-api/v1/webhooks/{provider}`。raw provider path/queryは保存キーにしない | SPEC_ADDRESSED（独立再Review待ち） |
| inbound provider error | provider catalog＋parser＋external error writer | malformed providerは400/`REQUEST_INVALID`、未承認providerは403/`FORBIDDEN_SCOPE`。ledger/processorへ進ませない | SPEC_ADDRESSED（独立再Review待ち） |
| connector evidence | enabled real Tomcat route | initial 202、duplicate 200、conflict 409、unknown 403、content-type 400をHTTP assertionまで確認 | Linux 5/5 independent recheck pending |

最新code remediationは`251461f1`。B2 remediation codeは`cc468e4f`から継続し、独立Review受領まではPASSへ昇格しない。
