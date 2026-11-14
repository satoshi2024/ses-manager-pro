# NF07 本番只読境界スライス Batch 4a 設計書

状態: **Astra限定 PLAN PASS（plan SHA `7ad5d9ab5670b70d454e91335c880f38665a8add`）/ CODE REVIEW PENDING（直近REQUEST CHANGESへの修正後、再審査待ち）**（作成時の記録）

2026-11-14 状態整合（[`status-alignment.md`](status-alignment.md) C-11 / C-12）: Batch 4a の独立CODE verdictは **UNKNOWN** とする。本書の「CODE REVIEW PENDING」と、`policy-source-catalog-wiring-plan.md` 作成時の「`498f04ba` 限定CODE/runtime PASS 76/0/0/0」は食い違い、76件の証拠dirはrepo内に無い。Batch 4a の3 classは `73aa7000` のFULL_GATE artifactで19/0/0/0（ProductionApprovalEnvelopeVerifierTest 8、ProductionEnvironmentGuardTest 4、SecurityContextCallerContextProviderTest 7）であり、これは同SHAの技術証拠に留まる。コード（`97007872` で統合）は基準 `1be03bea` まで無変更。下の「Batch 3はAstra独立CODE PASS済み」「Batch 3で独立審査済み」も repo内に受理記録が無いため **UNKNOWN** と読む。

対象worktree基準: `7e1a9cdfa78d914e55e031c37762d245f12571d0`

作業branch: `codex/snf-nf07-prod-boundary-20261104`

基準の由来: 統合candidateの固定SHA。`origin/main`（`1443fb68943849a3260d7ebe005259f3eb609d60`）をbaseとしていない。candidateはmainから派生した統合線であり、先行するNF07 source実装の内容を取り込んでいる。source branch `codex/snf-prod-nf07-evidence-contract` の固定SHA `91a67611b8320d20ae3a9d1040a9109c4464899b` はこの作業branchで変更・再利用しない。NF07 privacy/foundationコードおよびBatch 3計画との差分は空であり、source SHA自体はcherry-pick経由のためancestorではなく、両者のmerge-baseは固定control `9bac40c9624e7d964bcf6e18f480b3457662206e` である。
対象計画: `prod-wiring-decision-contract.md`、`pure-policy-verification-kernel-plan.md`、`completion-matrix.md`。Batch 3はAstra独立CODE PASS済みとの受理記録に基づく。

---

## 1. 目的と限定範囲

この設計は、Batch 3で独立審査済みのpackage-private純粋検証kernelを再利用し、Batch 4の最初の狭い安全境界だけを定める。対象は次の三コンポーネントとFast単体テストに限る。

1. `ProductionEnvironmentGuard`
2. `SecurityContextCallerContextProvider`
3. `ProductionApprovalEnvelopeVerifier`

本スライスは、認証済み主体・tenant境界・本番profile/audience・署名済みpolicy候補の検証境界を固定する。ポリシーを業務へ提供する照会サービスやAPIを作らず、検証結果を実行権限へ昇格させない。

本スライスに含めないもの:

* `@Configuration`、`@Bean`登録、Controller、REST API、`ProductionPolicyReadonlyQueryService`
* 実カタログ、現在版envelope store、Trust Anchor配布、KMS/HSM、CRL/OCSP等の供給元
* Journal、DB監査、DB migration、write、delete、匿名化、outbound dispatch、処分executor
* 人手判断D01..D09の補完、承認、フラグ有効化

対象の三クラスはまだSpring Beanとして登録しない。未設定の供給元から合成データへfallbackせず、未設定・欠落・不一致は常に拒否する。

## 2. package・可視性・依存方向

`PurePolicyVerificationKernel` は `com.ses.privacy.foundation` のpackage-private classで、helperもpackage-privateである。Javaのsubpackageは同一packageではないため、新コードも同じ `com.ses.privacy.foundation` に置く。

| 新規ファイル | 可視性 | 責務 |
|---|---|---|
| `src/main/java/com/ses/privacy/foundation/ProductionEnvironmentGuard.java` | package-private final | 信頼された起動profileと固定audienceを検査する |
| `src/main/java/com/ses/privacy/foundation/SecurityContextCallerContextProvider.java` | package-private final | 現在のSpring Security Authenticationから、許可された内部principalだけを検証済みcallerへ写像する |
| `src/main/java/com/ses/privacy/foundation/ProductionApprovalEnvelopeVerifier.java` | package-private final | production用の入口検査と検証順を担い、既存純粋kernelへ委任する。説明専用metadataのみを返す |

`PurePolicyVerificationKernel` をpublic化・複製しない。`ApprovalEnvelopeVerifier`、`SyntheticEnvironmentGuard`、synthetic query/catalog/providerも変更しない。Production verifierから合成用ラッパーを呼び出さず、同じ純粋kernel helperを直接使う。VerifiedApproval等の実行証明をこのwrapperから生成・返却する経路は設けない。

## 3. 本番環境と署名検証のfail-closed契約

### 3.1 ProductionEnvironmentGuard

* 実行環境は注入されたtrusted `Environment`のactive profileで判定し、呼出し元・query・envelopeがprofileを選ばない。
* `Environment.getActiveProfiles()`に明示的な`prod`が含まれることと、検証対象metadataの`audience`が固定値`PRODUCTION`であることの両方を要求する。default profileのみが`prod`でも受け付けない。
* `prod`でない、audienceが欠落・`SYNTHETIC`・その他の値、または環境情報を確定できない場合は拒否する。
* 既存`SyntheticEnvironmentGuard`を変更・無効化・迂回しない。

### 3.2 ProductionApprovalEnvelopeVerifier

入力には現在版として取得された`PolicyLookupResult`を受け取り、`CURRENT_CANDIDATE`、非null候補、expected `policyId`・`revision`・`digest`の一致を必須にする。既存`PolicyLookupResult`にはpolicyId fieldがないため、expected IDは将来のtrusted internal catalogが解決する値として明示引数で受け取り、queryから直接採用しない。Batch 4aにはそのcatalogとcurrent sourceがないため、production経路は登録せず、currentness/IDを運用環境で保証しない。未設定、`NOT_FOUND`、`UNAPPROVED`、`STORE_UNAVAILABLE`、未知status、欠落revision/digest、三点不一致はすべて拒否する。過去revisionへのfallbackは行わない。

署名検証は固定順で行う: schema allow-list → production Trust Anchor有無とID → Authority audience/tenant/scope/data/action/provider適合 → 有効期間 → keyおよびenvelope両方のrevocation → Ed25519署名 → Policy SoD → expected binding。暗号・時刻・失効・binding計算は`PurePolicyVerificationKernel`へ委任する。供給providerが未設定/例外/`null`を返す、anchorが未登録、key statusまたはenvelope statusのいずれかが`ACTIVE`以外なら検証成功を返さない。

このBatchにはcurrent-policyの信頼できる取得元がない。`PolicyLookupResult`を引数として検査しても、その引数だけでは保存先に対する「current」の真正性を証明できない。sourceが未設定またはcandidateが未取得なら拒否し、lookup metadataが示すrevision/digestと署名envelopeを照合する範囲に限定する。Controller/query serviceがないため、照会者に結果を公開するproduction経路は存在しない。

本Batchにはproduction Trust Anchorが存在しない。production anchor mapの未設定/空を拒否するconstructor/runtime contractを設け、test fixtureや`SyntheticPolicyCatalog`、synthetic test keysへのfallbackは禁止する。実鍵・CRL/OCSP等の設定を追加しない。

戻り値はpackage-privateな`VerifiedPolicyMetadata`相当の説明・検証 metadata に限る。`VerifiedApproval`、dispatch fence、認可boolean、Capability tokenを作らない。入力がcurrent candidateでない場合は拒否し、`APPROVED_ACTIVE` query resultをこのBatchから発行しない。

## 4. SecurityContext identity mapping

`SecurityContextCallerContextProvider`は認証済み`Authentication`だけを見る。anonymous、null、unauthenticated、未知principalは拒否する。principalの判定順序は`OidcLoginUser`を先にし、その後に一般`LoginUser`とする（OIDC principalはLoginUserのsubclass）。

`Authentication.isAuthenticated()`だけではアカウント有効性の根拠にしない。`LoginUser`/`OidcLoginUser`の内部`SysUser`が存在し、内部IDがあり、`status == 1`、`deletedFlag == 0`、`lockedUntil == null || lockedUntil <= identityObservationClock`のすべてをprincipal内snapshotと現在DB行の両方で満たさなければ拒否する。`SysUserMapper.selectByIdAndTenant(id, boundTenant)`をread-onlyで使い、row欠落、tenant mismatch、mapper障害も拒否する。DB書込は行わない。

Providerと`ProductionApprovalEnvelopeVerifier`は注入されたtrusted `Clock`を使う。queryに`asOf`を追加しない。既存`TrustedCallerContext.authenticatedAt` fieldへはproviderがcontextを観察・構築した時刻を記録するだけで、実際のlogin/authentication時刻やsession freshnessを示すとは主張しない。

### 4.1 OIDC tenant

1. `oidc.getTenantId()`による内部認証bindingを唯一のtenant正本とする。null/空白なら拒否し、OIDC claimへのfallbackはしない。
2. `tenant_id`、`tenantId`、`tenant`の各claimを個別に検査する。存在する値は全て内部bindingと完全一致しなければならない。claim間衝突、型・値不正、不一致は拒否する。claimは一致性検査だけに使う。
3. その後でquery tenantとの一致をcaller/verification boundaryで確認する。
4. この実装は`SecurityUtils.currentTenantId()`をtenantの正本として呼ばない。同helperは現在OIDC claimを先に選ぶため、本契約の単一正本規則を満たさない。

通常`LoginUser`でも認証時に固定された内部tenantが必要である。tenant、actor ID、roleをquery payloadから決めない。actor IDは内部`SysUser`の永続IDを使い、欠落時は拒否する。username fallbackはID一意性を保証しないため使用しない。

### 4.2 roleとprincipalの制限

* roleは許可されたprincipalの実際の`GrantedAuthority`から正確に判定する。DB role名`管理者`、`ROLE_管理者`または文字列一致だけで`ROLE_ADMIN`へ昇格させない。
* `ROLE_ADMIN`は`ROLE_ADMIN` authorityと`PRIVACY_ADMIN_READ` privilegeの両方が明示付与されている場合のみ許可する。片方でもなければ拒否する。
* Privacy auditor roleは、別途承認済みのauthority定数・付与規則がない限り拒否する。新しい権限やroleをこの設計だけで作らない。
* 本Batchでは`ExternalApiPrincipal`、`PortalLoginUser`、service/daemon principalを拒否する。client IDやportal identityを内部ユーザーへ変換しない。`ROLE_SYSTEM_SERVICE`発行・昇格経路を作らない。
* Query DTOからroleや`asOf`を受け取らない。既存`PolicyEvaluationQuery`の形を変えない。

### 4.3 current legal entityの限界

認識済みinternal userについては、OIDC claim一致を先に確認した後、`LegalEntityContextService.requireCurrentLegalEntityId()`をread-onlyで呼び、現在のtenantに一意な法人scopeが解決できる場合だけidentity stageを通過させる。context未設定、mapper利用不可、法人0件、複数法人の曖昧さは拒否する。`ExternalApiPrincipal`は自身の`legalEntityId`を持つが、本Batchではprincipal種別ごと拒否する。

ただし、現行`PolicyEvaluationQuery`と`ApprovalEnvelopeMetadata`にはlegalEntity ID/claimがなく、署名payloadも法人境界を表現しない。したがってこの検査は「呼出元の現在法人contextが一意」の確認に限られ、policy自体が法人単位で署名・認可されたことを意味しない。現契約どおりpolicyをtenant-wideとするか、将来の法人単位policyを設計するかは未決であり、正式な人手判断なしに法人単位の認可を主張しない。適用範囲を確定できないcaller/policyはproduction側では拒否する。

## 5. Fast tests

新規Fast testsは同packageに置いてpackage-private境界を直接検証する。

* `src/test/java/com/ses/privacy/foundation/ProductionEnvironmentGuardTest.java`
* `src/test/java/com/ses/privacy/foundation/SecurityContextCallerContextProviderTest.java`
* `src/test/java/com/ses/privacy/foundation/ProductionApprovalEnvelopeVerifierTest.java`

### 5.1 Negative test groups

1. `Authentication`なし、unauthenticated token、anonymous principalを拒否する。
2. 未知principalを拒否する。
3. OIDC内部tenant binding欠落時、tenant claimが存在してもfallbackしない。
4. OIDC内部tenant Aとclaim tenant Bの不一致を拒否する。
5. 複数tenant claim間の衝突、内部bindingとの衝突を拒否する。
6. trusted caller tenantとpolicy candidate tenantの不一致を拒否する。
7. 内部SysUser ID欠落を拒否し、actor IDはpersisted rowの内部IDから構築する。
8. `ROLE_管理者`/一般業務roleだけのprincipalをadmin/auditorへ昇格させない。
9. `ROLE_ADMIN`があるが`PRIVACY_ADMIN_READ`がないprincipalを拒否する。
10. `PRIVACY_ADMIN_READ`だけで`ROLE_ADMIN`がないprincipal、および`ROLE_SYSTEM_SERVICE`を自称するLoginUserを拒否する。
11. External/Portal/service principalを拒否し、下流検証・catalog相当処理を呼ばない。
12. active `prod`欠落（default profileのみ`prod`を含む場合も含む）、audience不一致、空Trust Anchor集合、current candidate欠落/三点不一致、key/ envelope別の失効状態、revocation第2照会例外、署名改竄、署名済みSoD違反、trusted Clockの期限境界、scope/data/action/provider各expected binding mismatchを個別にfail-closedで検証し、いずれも対象外理由のsuccess metadataを返さない。

### 5.2 Positive fixtures and interpretation

* identity positive: `LoginUser`の有効な内部tenant/actorと明示authority、およびOIDC内部bindingと全claimが一致するケースでcaller contextが作られる。
* guard positive: test `Environment`が明示的に`prod`をactive profileとして返し、audienceが`PRODUCTION`のときだけguardが通る。
* wrapper positive fixture: unit test内でのみ生成したephemeral key/current-candidate/revocation ACTIVE fixtureで、共通検証経路がmetadataを返すことを確認する。fixture keyはproduction Trust Anchorではなく、bean/config/storeへ登録されない。結果はテスト専用コード経路の検証であり、本番鍵登録、本番ポリシー承認、Production PASSを意味しない。
* synthetic fixtureでの既存合成経路の正向テストはsynthetic suite内に保つ。Production wrapperにsynthetic audience/keyを通すテストは作らず、拒否を確認する。

いずれのテストも、DB、Spring ApplicationContext、外部ネットワーク、KMS/CRL、Controllerを起動しない。成功テストは純粋kernel委任と防護境界の検証に限る。

## 6. Scope invariants and human decisions

* 0 DB write / 0 DB audit persistence / 0 delete / 0 outbound / 0 executor。
* 構造化ログの追加をこのBatchに含めない。DB監査・永続監査の完全性を主張しない。
* NF03の`POLICY_UNKNOWN`はretention期限後の自動処分だけをblockする。資格登録・upload・read・updateへのglobal gate/interceptorには接続しない。
* NF08では将来の外部送信直前にpolicy結果を強制する別sliceが必要であり、本Batchはdispatchを追加しない。非`APPROVED_ACTIVE`の外送は将来もdenyし、allowlist単独で許可しない。
* D01..D09はすべて未承認のままとし、AIが署名・承認日・Privacy Ownerを補完しない。
* tenant-wide policy適用範囲、privacy role/authority付与、正式Trust Anchor/KMS、revocation運用、法人単位scope、DG-07、監査・法務・security・ops判断は未解決であり、本Batchで解決したと扱わない。

Batch 4aの実装・テスト・CODE review・この計画のPLAN承認が全て完了しても、production policy provider未設定・D01..D09未承認のため、Full Feature / Production PASSや本番有効化を意味しない。
