# completion mapping

## 2026-10-26 NF07 本番接線・意思決定契約および純粋カーネル抽出計画（Base: aad2afee / 直前Base: b2886c47 / c8b78dc3 証拠継承）

現行作業ブランチ: `codex/snf-prod-nf07-evidence-contract`
本フェーズ基準（Base）: `aad2afeec71c2cb2eaa846bf02b2da0344990180`（Astra/総括 限定 PLAN PASS 承認コミット）
直前基準（Previous Base）: `b2886c47a0c20716a37a08dffcfb847f5432a1f1`
固定テスト証拠コミット: `c8b78dc3c8c3906e8ba734a2aa81037bd3a77dfb`
担当: AI-5（接線・意思決定契約および純粋カーネル抽出設計担当）
設計正本:
* 本番接線・意思決定契約: [`prod-wiring-decision-contract.md`](prod-wiring-decision-contract.md)
* 純粋Policy検証カーネル抽出・実施設計: [`pure-policy-verification-kernel-plan.md`](pure-policy-verification-kernel-plan.md)

### 1. 直前固定検証実績（c8b78dc3 の歴史的証拠）
本ラウンドにおいては新規のテスト実行は行っていない（NO NEW TESTS RUN IN THIS ROUND）。以下の実績は直前固定コミット `c8b78dc3` において完了・記録された歴史的証拠であり、全体の COMPLETE 状態を不当に引き上げるものではない。
* **只読Policy照会スライス定向テスト (`verification-evidence/20261025-nf07-targeted-01`)**:
  * 実行コマンド: `mvn test -Dtest=PolicyReadonlyQueryServiceTest,ApprovalEnvelopeVerifierTest,PrivacyFoundationIsolationTest`
  * 結果: **3クラス / 42テスト / 0 failure / 0 error / 0 skip（完全合格、ExitCode: 0）**
  * 内訳:
    * `PolicyReadonlyQueryServiceTest`: 29テスト合格
    * `ApprovalEnvelopeVerifierTest`: 9テスト合格
    * `PrivacyFoundationIsolationTest`: 4テスト合格
* **Fast 回帰テストスイート (`verification-evidence/20261025-nf07-fast-01`)**:
  * 実行コマンド: `mvn test` (Fast H2/Unit)
  * 結果: **697クラス / 4,292テスト / 0 failure / 0 error / 0 skip（完全合格、ExitCode: 0）**

### 2. 履歴スキャン統計の分離と将来リリースの閉環ゲート規約
過去のインベントリスキャン統計は計測時点のコミットSHAが異なっており、直前基準（`9024f7b2` / `b2886c47`）や本フェーズ基準（`aad2afee`）の直接スキャン結果と混同してはならない。
* **歴史的ベースライン 1（2026-08-28、コミット `b678ce98`）**:
  * 116 migration / 180 table / 4,279 CREATE column / 153 ALTER column / 176 entity / 424 provider候補。
  * プライバシーカタログ構造的未分類は 0 であるが、法定保持ポリシーが未確定のテーブルが 78 件存在（`COVERAGE_EXPLICIT_POLICY_UNKNOWN`）。
* **固定読取専用スキャン 2（2026-10-01、Control Head `cde61306`、証拠 `20261001-privacy-coverage-02`）**:
  * 183 SQL / 254 table / 244 entity / 708 provider候補。
  * プライバシーカタログ未分類 74、インベントリ欠落 1,082 列、プロバイダー参照欠落 285、エンティティ未マップ 68 で `BLOCKED`。
* **将来リリースの閉環ゲート規約**:
  * 上記の統計はいずれも本設計の基準コミットの直接再走査結果ではない。
  * Phase 4 実装時および将来の本番公開（Production Enablement）時には、リリース対象コミット SHA に対するインベントリ・カバレッジの閉環スキャンを再実行し、未分類 0・欠落 0 の証拠を固定することを必須ゲートとする。

### 3. 既存合成機能の実在状況と歴史的固定検証
既存の Journal、Hold/Fence、署名検証/SoD、Recovery、Preview は既に合成隔離層として部分実装・検証が存在しており、未実装または白紙として扱ってはならない。
* **SF-W0**: 計画固定Head `f007ef9aecc01e5cbf79bc02e595eb64a2bfe19b`（Astra技術Plan PASS）
* **SF-W1**: 固定Head `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`（Astra限定CODE PASS、対象3クラス 18/0/0/0）
* **SF-W2a**: 固定証拠 `verification-evidence/nf07-w2a`（MySQL並行境界・線形化整合性確認）
* **SF-W2b (Slice D2b2)**: 固定Head `ac894ac6ec9df2eada9e47c5f242f268270daec4`（1クラス 35/0/0/0 合格）
* **SF-W3a (Preview)**: 固定Head `92d91d58172288406f6048e69ad38aa85880eb04`（Astra限定CODE PASS、26/0/0/0、control `b6e2992b` 統合）

### 4. 基準SHA未検証項目（TEST NOT RUN）
以下の項目は Docker/MySQL、パフォーマンステスト環境依存のため AI-1 担当としてキューされ、基準 SHA においては未再実行（NOT RUN）である：
* W2b Journal CAS / 独立 Witness / アプリケーション投影の実 MySQL 統合テスト
* W2b Slice D リカバリ（3-MySQL 独立インスタンス）実 MySQL 統合テスト
* W3a 合成 DSAR プレビュー（`SyntheticPrivacyRequestServiceMySQLTest`）実 MySQL 統合テスト
* パフォーマンステストプロファイル（`-Pperformance-tests`）
* PITR / バックアップリストア統合検証

### 5. 本番接線ギャップおよび人手承認ギャップ
詳細設計は [`prod-wiring-decision-contract.md`](prod-wiring-decision-contract.md) §2 を参照。
* **技術配線ギャップ**: 純粋検証カーネルの独立審査未完、本番用 Trust Anchor / KMS 連携不在、本番独立 Journal/Witness インフラ不在、実テーブル/ファイル/AI プロバイダー不在、Outbox ディスパッチャー不在、本番用 Spring Security Context 呼出元プロバイダー不在。
* **OIDC テナント解決（P1解消）**: `OidcLoginUser` のテナントは内部認証バインディング `oidc.getTenantId()` のみを唯一の根拠とし、トークンクレーム（`tenant_id` 等）は一致性検査のみに使用（上書き・フォールバック禁止）。不一致・内部欠落・クレーム衝突はすべて `ACCESS_DENIED`。
* **ログ証跡の扱い**: 本スライスはデータベース監査（`t_audit_log`）への書き込みを行わない（0 DB write）。ログがファイルや外部基盤で永続化されるかを保証するものではなく、アプリDBへの永続化を行わない規約とする。DB 監査未実装の現段階において生産監査完備を主張してはならない。
* **人手承認ギャップ**: DG-07 未承認、正式 Privacy Owner 未選任、法的留置（Hold）/SoD 権限未承認、DSAR 本人確認・第三者マスキング SOP 未承認、外部 AI ベンダー DPA 未確認。
* **安全保護の根拠**: `privacy.retention.enabled` および `privacy.dsar.enabled` は導入予定・未実装であり、現行の保護根拠は「実際の `SyntheticEnvironmentGuard` による fail-closed 拒絶」および「破壊的実行能力の構造的不在」に立脚する。

### 6. 純粋Policy検証カーネル抽出計画（新規策定）
詳細設計は [`pure-policy-verification-kernel-plan.md`](pure-policy-verification-kernel-plan.md) を参照。
* **抽出対象**: `ApprovalEnvelopeVerifier` から純粋計算（スキーマ検証、半開区間時間窓検査、TrustAnchorスコープ照合、ACTIVE失効判定、Ed25519署名検証、SoD検査、バインディング一致検査）を抽出。
* **維持対象**: `SyntheticEnvironmentGuard`、システム時刻確定、TrustAnchor検索、失効プロバイダー例外ハンドリングは合成ラッパーに残存。
* **非依存不変条件**: 純粋カーネル（`PurePolicyVerificationKernel`）は Spring、DB、ネットワーク、KMS、環境プロファイルへのアクセスを一切持たない。
* **安全原則**: カーネル検証結果 ≠ 実行認可。説明専用DTO（`VerifiedPolicyMetadata`）を維持し、実行認可は付与しない。
* **状態**: **PLAN REVIEW PENDING**（Astra 独立技術審査待ち）。

---

## 2026-10-01 現行追加scope

`SYNTHETIC_FOUNDATION`は新しい技術incrementであり、計画固定Head `f007ef9aecc01e5cbf79bc02e595eb64a2bfe19b`はAstra技術Plan PASS。
SF-W0完了。SF-W1固定Head `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`は対象3 class 18/0/0/0、Astra限定CODE PASS。worker/処分/recoveryは未実装、production BLOCKED。
初期baseは`1443fb68943849a3260d7ebe005259f3eb609d60`、branchは`codex/snf-prod-nf07-foundation-plan`。
以下のDEV-0/D0行、旧base、旧coverage件数は過去の証跡として保持し、現行HeadのPASSへ転用しない。

### SF-W1限定Reviewと現行coverage gap

| scope | fixed evidence | 結果 | 境界 |
|---|---|---|---|
| SF-W1 foundation | `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`、`synthetic-foundation.md`の3 XML SHA-256、対象3 class | Astra CODE PASS、18/0/0/0 | pure envelope/verifier/FSM/test harnessのみ。worker/journal/restore、Full Feature/Production PASSではない |
| 最新read-only source scan | control Head `cde61306`、`verification-evidence/20261001-privacy-coverage-02` | 183 SQL / 254 table / 244 entity / 708 provider候補。unclassified 74、missing columns 1,082、provider 285、entity 68でBLOCKED | source structure gapとpolicy UNKNOWNを別管理。0.4 coverage closure、DG-07、正式Owner/法的判断を満たさない |

上記scanはcontrolで固定されたread-only inventory証拠であり、SF-W1のworktree HEADに対する実行結果とは称さない。旧coverage行の180 tables/unclassified 0/provider 424等はその当時の履歴として保持し、現状gapの解消証拠に転用しない。

開始時base/merge-base: `0333b0a4afadef42639bad27e1ae443758f9804f`
fetch後の現在 `origin/main`: `f131f51c50dbfb68ffc8e71878da52947560c80e`（base drift。`<BASE_COMMIT>`未確定のためrebaseなし）
DEV decision: `NF07-DEV-GATE-20260828` / `APPROVED_DEV_ONLY` / Owner role `Project Maintainer（development-only）` / formal Privacy Owner `UNASSIGNED_UNTIL_PRE_PRODUCTION`。Full Feature/Productionのapproved policy/scope、正式owner、approved Base branch/SHAは未提供でBLOCKED。上記origin/mainは技術比較境界であり承認済みBaseではない。
実装branch: `codex/privacy-retention-dsar`
通常checkout: `C:\work\ses-manager-pro`（変更しない）

| 要件/task | evidence | 検証/Demo | status |
|---|---|---|---|
| NF07-DEV-GATE-20260828 | `dev-gate-evidence.json`、`plan.md` §approval status | DEV-0/D0だけを `APPROVED_DEV_ONLY`、synthetic/redacted only、external I/O/provider/write/destructive operation禁止、法的/本番承認falseとして検証 | APPROVED_DEV_ONLY（development-only） |
| PR-R1 / 0.1 | `pii-inventory.md`、`source-coverage.md` | DB/file/AI、owner/purpose/trigger/retention/hold/disposition/providerをstatic evidenceとunknown/provisional付きで確認 | INCOMPLETE（privacy catalog unclassified 0、policy UNKNOWN/BLOCKED 78、承認未完） |
| PR-R1.2 | `pii-inventory.md` §4、`design.md` §4 | unapproved retentionを法的確定とせず、NULL/未確定を候補外にする | COMPLETE（discovery boundaryのみ） |
| PR-R2 / D0.1 | `read-only-dry-run.ps1`、`dry-run.md` | fixtureでcandidate/blocked/unknownを出力、providerCallCount=0 | COMPLETE（offline） |
| PR-R2.3 | fixture hold/legal-retention/audit/active-business/same-name/scope-out | blockerをBLOCKEDとして説明し、scope外providerを呼ばない | COMPLETE（offline） |
| PR-R2.4 | invalid identity/disposition fixtures、script allow-list | `UNVERIFIED`はBLOCKED、未知dispositionはUNKNOWNで、raw PII keyは拒否 | COMPLETE（offline） |
| PR-R1.3 mechanical coverage | `inventory-coverage.ps1`、`coverage-evidence.md`、`source-coverage.md`、`pii-inventory.md` §4 | migration 116/180/4,279/153（CREATE/ALTER）、entity 176、provider候補424（filename/content semantic scan）、source unique column 2,652、privacy catalog 180/0/78（explicit/unclassified/policy unknown）、source coverage unmapped/missing/extra column/entity/provider 0/0/0/0/0/0 | COMPLETE（構造coverageのみ。policy unknown 78は処分BLOCKED） |
| PR-R1.4 / 0.5 developer gate validator | `gate-evidence-validator.ps1`、`gate-evidence-validation.md`、dev gate evidence、missing fixture、validator test | DEV decisionはDEV scopeのみexit 0、Full evidence/DEV evidence欠落はHARD_STOP・exit 2。F1-M/本番処分/provider/PRをfalse、providerCallCount/writeCountを0にする | COMPLETE（DEV-only補助統制。独立Review verdictは生成しない） |
| PR-R3 | `requirements.md` §2 PR-R3、`design.md` §5 | identity/third-party redaction/export/providerを未実装・fail-closedとして明示 | SPEC ONLY |
| PR-R4 | `requirements.md` §2 PR-R4、`tasks.md` F1〜M | DG-07未完、flag OFF、処分経路なしを確認 | BLOCKED BY GATE |
| DEV-0/D0 plan | `plan.md`、`dev-gate-evidence.json` | `NF07-DEV-GATE-20260828`のscopeに限定してPASS相当。独立ReviewはDEV-0/D0 scopeだけ | APPROVED_DEV_ONLY / REVIEW REQUIRED |
| Full Feature / Production plan | `plan.md` | policy/owner/Base/DG-07/外部gate未完。F1-M、本番処分、PRは不可 | BLOCKED BY GATE |
| Git isolation | start validation/handoff | dedicated worktree、branch、clean status、base/remoteを確認 | COMPLETE |
| F1〜M | tasks.md | DG-07/external/internal gate未完のため未着手 | STOPPED |

## task commit/push record

| task | commit | remote | result |
|---|---|---|---|
| 0 inventory/spec foundation | `6ea110bb` | `origin/codex/privacy-retention-dsar` | pushed |
| D0 dry-run | `eb0e2dd3cb566161e7878bd7a0ee377bdd63393f` | `origin/codex/privacy-retention-dsar` | pushed |
| D0 blocker coverage | `3875ccb578d0fe31546d8e7b33fde1d3bf1c2cc3` | `origin/codex/privacy-retention-dsar` | pushed |
| D0 review-gap closure | `314b6a9bf2b80af6a50e25fa2e6c0a1dcf531524` | `origin/codex/privacy-retention-dsar` | pushed |
| handoff/base drift evidence | `2ea49d44bbe45296d9f69e39fb3e371abd503d13` | `origin/codex/privacy-retention-dsar` | pushed |
| evidence formatting | `5b92bc8b0683e13e3cfcfaab05695e8eaa994dd7` | `origin/codex/privacy-retention-dsar` | pushed |
| review handoff pointer correction | `90a0aec4d60c9b75049b4d8b6b82923eb7ad8b24` | `origin/codex/privacy-retention-dsar` | pushed |
| D0 blocker evidence record | `d525dbd3cb1738fe3dae4764a8aa3d58f2e31c65` | `origin/codex/privacy-retention-dsar` | pushed |
| D0 review-gap closure record | `aba00ab058fd7dc3c9d833005dcf0adb49112d86` | `origin/codex/privacy-retention-dsar` | pushed |
| review Head binding | `3c3006b351e4a42146540946f4da561391ef02db` | `origin/codex/privacy-retention-dsar` | pushed |
| source inventory completion | `b45a2f4f42beb3e87400318b84271d40a8340e37` | `origin/codex/privacy-retention-dsar` | pushed |
| compliance history inventory | `ab06e0a3098e4e072cdee0d6b75f9ec439189bed` | `origin/codex/privacy-retention-dsar` | pushed |
| reviewer qualification inventory | `a05ad24203110090afdd69d2248e054133cf29b9` | `origin/codex/privacy-retention-dsar` | pushed |
| reviewer adoption inventory | `64e33cbc54720264f785c8030b99fa3630a23a95` | `origin/codex/privacy-retention-dsar` | pushed |
| mechanical inventory coverage | `950d3c7126ac45f85cf2286fbab31eec1f011472` | `origin/codex/privacy-retention-dsar` | pushed |
| delta gate traceability alignment | `0a66604c198ffda361544e070cffefcd79295f27` | `origin/codex/privacy-retention-dsar` | pushed |
| source coverage manifest / column verification | `1dffb5d482931765e7472fc105b02bc9e68dd099` | `origin/codex/privacy-retention-dsar` | pushed |
| delta Review evidence synchronization | `59831068153a3a6efa2ccbcd5f6618c6d7105fb8` | `origin/codex/privacy-retention-dsar` | pushed |
| source catalog explicit rows / Review provenance boundary | `b678ce987ad93c8100a14df9776f25e89cdd597e` | `origin/codex/privacy-retention-dsar` | pushed。構造unclassified 0、policy UNKNOWN/BLOCKED 78。0.4/F1-Mは未完了 |
| exhaustive SQL/provider scanner correction | `4ef33ac4` | `origin/codex/privacy-retention-dsar` | pushed。V20 one-line CREATE、false `t_contract_document.INSERT`、Gemini/FileStorage/FileCleanup等を補正 |
| exact manifest/provider content coverage correction | `387e090e` | `origin/codex/privacy-retention-dsar` | pushed。extra column/provider検出、9 stale column refs除去、provider discoveryをfilename/content semantic scanへ変更 |
| developer gate evidence validator | `baa17719` | `origin/codex/privacy-retention-dsar` | pushed。DEV/Full gate mode分離前の安全側validator |
| development / production gate split | `ec06fa64` | `origin/codex/privacy-retention-dsar` | pushed。`NF07-DEV-GATE-20260828`をDEV-0/D0に限定し、Full Feature/ProductionをBLOCKED |
| exact development owner binding | `8ab88d8a` | `origin/codex/privacy-retention-dsar` | pushed。依頼DecisionのOwner role表記を厳密一致。DEV scope以外の許可なし |

## D0 actual verification

2026-08-27にfixtureを実行し、`candidate=1`、`blocked=6`、`unknown=2`、`providerCallCount=0`、`writeCount=0`。fixture SHA-256は実行前後で一致した。invalid raw PII propertyとmissing `asOf` fixtureはともに拒否された。追加した `UNVERIFIED` はBLOCKED、unsupported dispositionはUNKNOWNになった。

## Review handoff条件

独立Reviewへ渡すものは、最終remote Head、DEV-only approved plan/spec/tasks、completion mapping、DEV gate evidence、Full gate blocker、dry-run実行結果である。実装対話からPRは作成しない。DEV-0/D0 scopeのPLAN/IMPLEMENTATION双方PASS後もFull Feature/ProductionはBLOCKEDであり、PR作成を依頼しない。

## DEV-0/D0検証（実装側claim）

remote Headはcommitのたびに変化するため、最新SHAをtracked documentへ固定しない。dispatch時に `git ls-remote origin refs/heads/codex/privacy-retention-dsar` とReview worktree HEADを外部handoffへbindする。validatorはDEV modeで exit `0` / `DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW`、Full modeで exit `2` / `HARD_STOP`を返す。coverageは180 table、unclassified 0、policy UNKNOWN 78、structural missing/extra 0、dry-runはcandidate 1 / blocked 6 / unknown 2、providerCallCount/writeCount 0である。これは実装側の検証claimであり、独立Implementation Reviewのverdictではない。
