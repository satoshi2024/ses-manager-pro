# NF07 最小 Production Current Policy Source / Catalog 接続計画

状態: **PLAN REVIEW 提出前ドラフト。実装・Spring Bean登録・本番接続は未実施。**
基準（作成時、2026-11-04）: `498f04ba459557d93d180b9f1a910bf3dff3c71f`（NF07 Batch4a）。作成時の本行は「限定CODE/runtime PASS 76/0/0/0」と記していたが、その76件の証拠dirはrepo内に無く、Batch 4aの独立CODE verdictは **UNKNOWN** として扱う（[`status-alignment.md`](status-alignment.md) C-12）。同SHA範囲の技術証拠としては、`73aa7000` のFULL_GATE artifactにBatch 4aの3 class 19/0/0/0がある（歴史的固定証拠。本取込で再実行していない）。
取込記録（2026-11-14、W3-04 NF07-P）: 取込元は `origin/codex/backup-20261113-nf07-policy-source-711aa557` の commit `711aa557`（branchはmergeせず、このファイルだけを `git show` で取り出した）。取込先の基準は `1be03bea`（S-NF統合候補。`com.ses.privacy` 配下のコードは `73aa7000` から無変更）。本文の変更は、見出しと「対象」行の中国語表記を日本語へ直したこと（意味は変えていない）、基準行と本取込記録の追加、末尾 §7 の追記だけである。§1〜§6 は原文のまま。
対象: 合成の読み取り専用照会の次段として、署名済みcurrent policyを信頼できるcatalog/sourceから一件だけ取得し説明評価する契約。削除・外送 capability は作らない。

## 1. 実在コードと証拠化済み境界

- `PolicyReadonlyQueryService` は `SyntheticPolicyCatalog`、`PolicyEnvelopeSource`、`SyntheticCallerContextProvider` と `SyntheticEnvironmentGuard` を使う合成専用サービスである。`PolicyEvaluationQuery` に `asOf` / role はないが tenant/actor は含み、provider context と一致照合する。
- `ProductionApprovalEnvelopeVerifier` と `ProductionEnvironmentGuard` はpackage内クラスとして存在するが Spring Bean ではない。Verifier はコンストラクタへ渡された Map / revocation provider / Clock を使い、呼出側の `expectedPolicyId` との一致、source snapshotのrevision/digest、PRODUCTION audience、署名、SoD、tenant/scope/data-element/action/providerの検証を行う。この値だけでは正本から得たcurrentnessを証明しない。
- `SecurityContextCallerContextProvider` は内部のactive `SysUser`、明示 `ROLE_ADMIN` + `PRIVACY_ADMIN_READ`、内部tenant bindingとOIDC tenant claim一致を検査する。`LegalEntityContextService` のcurrent IDも要求するが、得たIDを破棄し `TrustedCallerContext` に含めない。法人contextの成立は署名済みpolicyの法人bindingを意味しない。
- `ApprovalEnvelopeMetadata` の署名対象にはtenant/scope/audience/revision/requester/approver/時刻等があるが `legalEntityId` はない。適用actor/法人を署名payloadへ含めるかは、schema/canonical encoding互換性を含む明示決定が必要。
- `PolicyExplanationResult` は説明status/metadataのみを返し、処分・dispatch capabilityを持たない。`SyntheticPolicyCatalog.createDefault()` の NF03/NF08 エントリと g10 allow-list は本番policy承認や外送許可ではない。

Batch4aの限定76件と限定PASSは当該固定SHA/範囲の証拠であり、本計画、Spring配線、production trust source、DG-07、Full Gateまたは本番受入を証明しない。

## 2. このバッチで計画する最小境界

一つの同期・読取専用 `ProductionPolicyReadonlyQueryService` が、呼出し元の認証済みcontext、信頼済みpolicy catalog、authoritative current source、trust registry、revocation provider、運用管理された `Clock` を内部注入で受ける。戻り値は現行 `PolicyExplanationResult` 相当の説明DTOのみ。DB/registryはread-only、監査書込・case承認・dispatch・削除は対象外。

提案するport契約（実装時の型名案。fake契約を本番adapterと誤認しないこと）:

```java
interface ProductionPolicyCatalogPort {
    Optional<ProductionCatalogBinding> resolve(PolicyLookupKey key);
}
record PolicyLookupKey(String moduleCode, String tenantId, String actorId, long legalEntityId,
                       String dataElementId, String action, String providerId) {}
record ProductionCatalogBinding(String policyId, String scope, String actorBinding,
                                String dataElementId, String action,
                                String providerId, long legalEntityId) {}

interface CurrentPolicySource {
    CurrentPolicySnapshot readCurrent(PolicyLookupKey key, ProductionCatalogBinding binding);
}
record CurrentPolicySnapshot(CurrentStatus status, PolicyApprovalEnvelope envelope,
                             String revision, String digest) {}
enum CurrentStatus { CURRENT, NOT_FOUND, UNAPPROVED, CONFLICT, UNAVAILABLE }
```

`ProductionPolicyTrustRegistry.requireAnchor(authorityId, keyId)` は本番管理された有効鍵履歴から一意な鍵を返すか拒否し、既存 `RevocationStatusProvider` を鍵・envelope双方へ適用する。`ProductionPolicyReadonlyQueryService.evaluate(PolicyEvaluationQuery)` は actor/tenant を認証済みcallerと照合し、法人IDはcaller contextとcatalog binding双方から一致を要求する。query入力を信頼源にしない。`actorBinding` は署名済みpolicyの適用対象（actor個別または承認済みactor scope）と照合し、単なるcatalog文字列だけで署名上の適用範囲を拡張しない。現行 `ProductionApprovalEnvelopeVerifier` は候補検証部として再利用できるが、その期待値をauthoritative catalog/sourceから組み立てるservice境界を追加するまで本番接続不可。

### currentness と検証順序

1. prod profileと `PRODUCTION` audienceを確認。caller identity/権限/tenant/actor/法人を内部trusted providerから解決し、queryとの不一致はsource照会前に拒否。
2. `(module, tenant, legalEntity, dataElement, action, provider)` 完全一致の登録済catalog entryを一意に解決。未登録・重複・不整合は拒否。
3. sourceは当該bindingのcurrent pointerと署名envelope/revision/digestを同一の一貫したread snapshotで返す。current pointer自体も承認済みpublisherに追跡でき、改竄・rollbackを検知できる正本の一部でなければならない。0件=NOT_FOUND、2件以上/current pointer競合=CONFLICT。履歴から「最新そうなもの」を選ばず、revisionを数値比較せず、旧版へfallbackしない。
4. catalog policyId、current revision、canonical digestを照合した後、署名済claimのtenant/scope/data-element/action/providerと有効期間を検証。authenticated actorはquery/source keyでcallerへ結合し、actor scopeとlegal entity applicabilityは署名対象payloadで検証する。現行schema v1に必要bindingが無い場合は未接続のまま停止し、署名外のcatalog値で代替しない。
5. trust registryのauthority/key ID・鍵状態・有効期間・署名を検証する。鍵rotationは有効区間とrevocationの明示状態で扱い、未知鍵・重複鍵・期限/失効確認不能・旧鍵fallbackを拒否する。`Clock.instant()` は内部の運用管理Clockだけから得る。
6. 正常時も説明status/metadataのみを返す。source、catalog、trust、revocation、clockの障害/矛盾/timeoutは固定理由の `POLICY_UNKNOWN` または `POLICY_DRIFT` へ閉じ、例外詳細/秘密を返さない。

## 3. 保留中の人工決定・非接続条件

production adapter/Bean/APIを作る前に所有者が次を決定し、追跡可能な承認記録を示す必要がある。

- 正式Privacy Owner、DG-07の保持根拠・trigger・期間、policy issuer/approver権限とSoD。
- authoritative policy catalog/sourceの所有システム、可用性/SLA、current pointerの原子的一意性、整合snapshot/read consistency、競合時運用、障害/復旧手順。候補ストア/テーブルは未決定であり、この計画ではDDLを選定しない。
- production trust anchorの登録/発行主体、KMS/HSM保護、鍵ローテーション・重複/重複期間・失効伝播・緊急revoke手順、envelope revocationの正本。
- tenantとlegal entityの認証済み結合source。法人IDを署名payloadに追加するか既存署名bindingで代替できるか、schema/canonical versionと再署名移行を決める。現在のLegalEntityContextは証拠にならない。
- actorのpolicy適用binding（全actor共有policyかactor別か）、role/authorityのprivacy read権限source、信頼できるClock/NTP・許容skew・時刻異常時のfail-closed方針。
- API/下流呼出しの認可・監査責任。現時点はSpring Beanもproduction serviceも未接続であり、これら未決の間はproduction serviceを登録しない。

`CurrentPolicySource` / catalog / trust registry用のin-memory fakeは契約テスト専用に限る。fakeの署名鍵・fixture・current pointerはproduction profileで構築/注入不能とする。fakeを使ったテスト結果はadapter契約の証拠であり、production接続証拠ではない。

## 4. 次段の正確な実装範囲案と後続テスト契約

Astra PLAN Review後、別途承認された次段で許可する候補範囲は次のファイル群のみ。実backend、migration、Controller/UI、NF03/NF08呼出し元変更は所有者決定/別計画まで除外する。

- `src/main/java/com/ses/privacy/foundation/ProductionPolicyCatalogPort.java`（または同等interface）、`CurrentPolicySource.java`、`ProductionPolicyTrustRegistry.java`、必要なimmutable key/snapshot records。
- `ProductionPolicyReadonlyQueryService.java` と `ProductionCallerContext` の法人binding契約。既存 `SecurityContextCallerContextProvider` は値を保持して署名binding決定後に限り変更。
- `src/test/java/com/ses/privacy/foundation/ProductionPolicyReadonlyQueryServiceTest.java` と必要最小限の `ProductionApprovalEnvelopeVerifierTest` 拡張。専用test fakeのみ。

後続Fast testは既存 `ProductionApprovalEnvelopeVerifierTest` / `PurePolicyVerificationKernelTest` の回帰を保った上で、(a) unregistered/zero/multiple current source、競合/不可達/未承認、stale revision・digest・policyId、(b) forged caller tenant/actor/legal entity、OIDC claim conflict、空binding、(c) 未登録/rotation中/旧/失効/UNKNOWN鍵、失効provider例外、expired/effective境界とClock異常、(d) data-element/action/provider/scope drift、(e) 読取以外のport呼出し0回・戻り値にcapability無しを到達可能なnegative caseとして確認する。検証前のprovider呼出し順序と失敗status/reasonもassertする。fake testsが通っても実production currentnessは未証明と明記する。

## 5. hard gate と利用側境界

- NF03の日常資格CRUD/閲覧/更新はretention unknownだけを理由に停止・readonly化しない。封鎖対象は未承認のretention処分経路だけ（本計画は処分経路を作らない）。
- NF08 field allow-listは技術上限であって外送許可ではない。policy explanation `APPROVED_ACTIVE` も外送を許可しない。外送には別途承認case、journal直列化、verified dispatch fence等が必要だが本計画外。
- Full DSAR/export/delete/anonymize/worker/restore unquarantine/production enablement、本番DB変更・migration・API・Bean登録は本バッチの範囲外。
- Privacy/LegalがDG-07、scope、owner、保持/処分を承認し、Securityがissuer/KMS/HSM/key rotation/revocationを承認し、Identity/Securityがactor/tenant/legal-entity binding sourceを承認し、Operationsがcurrent store consistency/availability/Clock運用と障害時手順を承認するまでproduction接線は未接続。
- PR作成・merge・deploy・本番有効化には別途人手判断が要る。限定CODE/runtime PASS、計画Review、fake test、PR #116の状態からGo判断を導かない。

## 6. 完了条件

この計画1ファイルを固定SHAで総責任者/Astraが独立PLAN Reviewし、範囲・現在性の証明方法・人手前提・負例・利用側境界を受理した時点で本計画タスクを完了とする。受理後も実装は開始せず、承認済み別タスク/実backend決定を待つ。Review結果が要求変更ならこのファイルだけを改訂して再提出する。

## 7. 2026-11-14 追記（取込時の読み替え。§1〜§6 の原文は変更しない）

依頼者の決定 B-NF07-1 / B-NF07-2（2026-11-13）と Q-1 / Q-7 / Q-8（2026-11-14）により、本書の次の記述は [`production-bridge-phased-plan.md`](production-bridge-phased-plan.md) で読み替える。本書のport契約（§2）、currentnessと検証順序（§2）、負例試験 (a)〜(e)（§4）、利用側境界（§5）はそのまま W3-14 の受入条件として引き継ぐ。

| 本書の記述 | 読み替え | 根拠 |
|---|---|---|
| §3「候補ストア/テーブルは未決定であり、この計画ではDDLを選定しない」 | 第1期は application DB の F1 表 `m_retention_policy`（current pointer を生成列＋UNIQUEで一意化）を候補ストアとして実装する。正本ストアの所有・可用性・改竄検知（P-SRC）は未署名のままで、署名までは「本番currentnessは未証明」と扱う | Q-7（NF07 F1 DDL 承認）、Q-8 |
| §3「未決の間はproduction serviceを登録しない」、§4「Bean登録は所有者決定/別計画まで除外」 | `ProductionPolicyReadonlyQueryService` を prod profile の Spring Bean として登録する。ただし `privacy.retention.enabled` 既定OFFの間は source を読まずに `POLICY_UNKNOWN` を返し、trust anchor 未設定・失効状態UNKNOWNでも `POLICY_UNKNOWN` / `POLICY_DRIFT` に閉じる | Q-1（F1 = 表・読み取り専用 policy source Bean・flag OFF）、Q-8 |
| §1/§2「明示 `ROLE_ADMIN` + `PRIVACY_ADMIN_READ`」 | privacy read 権限の正本は permission group action（`privacy.policy.view`、`privacy-admin` group だけが許可）とする。GrantedAuthority 文字列は発行しない。caller provider は永続 `SysUser` を再読した後に action 判定を行う（production-bridge-phased-plan.md §6.4） | B-NF07-2 |
| §5「NF03/NF08呼出し元変更は所有者決定/別計画まで除外」 | 変更なし（除外を維持）。NF08 との接続手順は production-bridge-phased-plan.md §8 に別batch（NF07-NF08-LINK）として定める | Q-1 |
