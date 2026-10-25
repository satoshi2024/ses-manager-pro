# SF-Policy-Readonly-Query-Plan: NF03/NF08向け読み取り専用Policy照会・評価契約

状態: **PLAN / 総括および Astra 独立レビュー待ち。未実装。**
本仕様は、NF03（スキル資格情報）および NF08（AI要約・プロンプト・ログ）から参照される「読み取り専用のポリシー照会・評価契約」の限定設計である。実処分、外部送信、worker、IdP 連携、Flyway migration、本番配線、実データ操作は含めない。

---

## 1. 目的と実装境界

### 1.1 目的
既存の `ApprovalEnvelopeVerifier`、`PolicyApprovalEnvelope`、`SyntheticEnvironmentGuard` の厳格な信頼境界を再利用し、下流の業務モジュール（NF03）および連携モジュール（NF08）に対し、バージョン管理・署名された保持・処分ポリシーの安全な「政策説明・評価インターフェース」を提供する。

### 1.2 明確な除外事項（本スライスの非対象）
本スライスでは以下を一切実装せず、設計にも含めない：
* 処分 Executor（物理削除、論理削除、匿名化、アクセス制限の実行器）
* 外部 Provider への実際のディスパッチおよび HTTP クライアント
* 復旧（Restore）後の隔離解除
* 実際の IdP（Keycloak 等）との認証連携
* Flyway migration（新規テーブル・DDL の追加なし）
* Spring `@RestController` や本番業務画面への直接配線
* 実際の業務データまたは PII の変更・読み出し

---

## 2. 信頼境界とコンテキスト検証

本インターフェースは、既存の安全機構を前提として厳格にコンテキストを検証する。

```text
[呼び出し元: NF03 / NF08]
       │
       ▼ (PolicyEvaluationQuery: tenantId, actorContext, dataElementId, action, provider)
[SyntheticEnvironmentGuard] ──▶ prod profile 混入検知時は即座に SecurityException
       │
       ▼
[PolicyReadonlyQueryService] ──▶ 信頼される Clock（システム現在時刻）でのみ判定
       │
       ├──▶ [ApprovalEnvelopeVerifier] ──▶ Ed25519 署名 / Schema v1 / Trust Anchor 検証
       │                                   有効期間 / 失効状態（Revocation）検証
       │                                   SoD（Requester ≠ Approver）検証
       ▼
[PolicyExplanationResult] ──▶ 政策説明メタデータのみ返却（実行 Capability や認可 Receipt は非提供）
```

### 2.1 テナントとアクターコンテキスト
* 呼び出し元は、正規の `tenantId`、`actorId`、`actorRole` を含むコンテキストを提示しなければならない。
* 認証コンテキストが欠落している場合、またはシステム内部／管理者以外の不適切なロールからの照会は `ACCESS_DENIED` として拒絶する。

### 2.2 プロファイル防護
* `SyntheticEnvironmentGuard` を経由し、active または default profile に `prod` が含まれる場合、合成検証用ポリシーの読み出しは即座に拒絶する（fail-closed）。

---

## 3. 契約モデルとバインディング

### 3.1 入力モデル: `PolicyEvaluationQuery`

呼び出し側が恣意的に結果を操作する余地を排除するため、以下の防護を施す：
1. **asOf 日時の外部指定禁止**:
   呼び出し元が過去または未来の任意の `asOf` 日時を指定して期限切れポリシーを有効に見せかける行為を禁止する。判定にはサービス内部に注入された信頼できる `Clock.instant()` の現在時刻のみを用いる。
2. **権限フラグの注入禁止**:
   呼び出し元から `isPermitted=true` のような boolean や承認フラグを受け取る引数は設けない。

```java
package com.ses.privacy.foundation.policy;

import java.util.Objects;

public record PolicyEvaluationQuery(
    String tenantId,
    String actorId,
    String actorRole,
    String moduleCode,       // "NF03_CERTIFICATION" または "NF08_AI_SUMMARY"
    String dataElementId,    // 例: "CERTIFICATION_PII", "AI_PROMPT_LOG"
    String targetAction,     // 例: "DISPOSITION_PURGE", "EXTERNAL_DISPATCH"
    String targetProvider    // 例: "local-db", "gemini-adapter"
) {
    public PolicyEvaluationQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(dataElementId, "dataElementId must not be null");
        Objects.requireNonNull(targetAction, "targetAction must not be null");
        Objects.requireNonNull(targetProvider, "targetProvider must not be null");
    }
}
```

### 3.2 出力モデル: `PolicyExplanationResult`

本契約が返すのは「ポリシーの現状説明（Explanation）」に限定され、**いかなる実行チケット、実行 Capability、認可 Receipt も含まない**。

```java
package com.ses.privacy.foundation.policy;

import java.time.Instant;
import java.util.Optional;

public record PolicyExplanationResult(
    PolicyDecisionStatus status,
    String policyVersion,
    String policyDigest,
    String dataElementId,
    String targetAction,
    String targetProvider,
    String explanationReason,
    Optional<Instant> effectiveFrom,
    Optional<Instant> expiresAt,
    boolean externalDispatchPermitted,
    boolean dispositionPermitted
) {}
```

---

## 4. 判定ステータスとフェイルクローズ（Fail-Closed）規約

| ステータス (`PolicyDecisionStatus`) | 条件 | 外部送信 (`externalDispatchPermitted`) | 処分 (`dispositionPermitted`) |
|---|---|---|---|
| `APPROVED_ACTIVE` | Ed25519 署名、Schema v1、Trust Anchor、SoD、テナント・要素・アクション・プロバイダーが完全一致し、現在有効期間内で未失効のポリシーが存在する。 | ポリシーの定義に従う | ポリシーの定義に従う |
| `POLICY_UNKNOWN` | ポリシー未登録、未承認、catalog 欠落、または政策決定が未完了。 | **false** | **false** |
| `POLICY_EXPIRED` | 現在時刻がポリシーの `expiresAt` または Trust Anchor の `validUntil` を超過。 | **false** | **false** |
| `POLICY_REVOKED` | キーまたはエンベロープの失効ステータスが `ACTIVE` 以外。 | **false** | **false** |
| `POLICY_DRIFT` | 署名ハッシュ不一致、改竄、またはテナント・要素・アクション・プロバイダーの不整合を検知。 | **false** | **false** |
| `ACCESS_DENIED` | テナント不一致、アクター権限不足、またはコンテキスト不正。 | **false** | **false** |

### 4.1 フェイルクローズ原則
* ポリシーが `APPROVED_ACTIVE` 以外のステータスである場合、`externalDispatchPermitted` および `dispositionPermitted` は**例外なく常に `false`** となる。
* 不明またはエラーの発生時に、デフォルトで許可方向（Permissive）に倒すフォールバックは一切設けない。

---

## 5. NF03 / NF08 固有の運用解釈規則

### 5.1 NF03（スキル資格管理）における業務継続と処分境界の分離
* **背景**: 現時点で NF03 の `CERTIFICATION_PII` に対する DG-07 保持ポリシーは未承認（`POLICY_UNKNOWN`）である。
* **解釈規約**:
  1. `POLICY_UNKNOWN` は「保持期限到達による廃棄・自動削除ポリシーが未承認である」ことを意味する。
  2. これを**「NF03 の通常の資格登録・閲覧・更新・編集業務をすべて停止し、全画面を只読（Read-only）に封鎖しなければならない」と過剰拡大して解釈してはならない**。
  3. 封鎖されるのは「retention 処分（廃棄・パージ）」のトリガーのみであり、エンジニアの資格管理という日常の通常業務機能は妨げられない。

### 5.2 NF08（AI 要約・ログ連携）における外部送信の厳格解釈
* **解釈規約**:
  1. `externalDispatchPermitted = false` が返された場合、**「白名单（allowlist）に含まれている項目であっても外部 AI への送信を行ってはならない」**ことを意味する。
  2. 「白名单に記載されたフィールドだから、ポリシー未承認でも送信してよい」という例外措置や迂回解釈は厳格に禁止する。
  3. 白名单は「送信が許容され得る技術的上限範囲」を定めるものであり、「送信を強制・認可するライセンス」ではない。ポリシー判定が `APPROVED_ACTIVE` かつ `externalDispatchPermitted = true` でない限り、一切の外部通信を遮断する。

---

## 6. クラス設計と配置計画

```text
src/main/java/com/ses/privacy/foundation/policy/
├── PolicyReadonlyQueryPort.java       // 照会インターフェース
├── PolicyEvaluationQuery.java         // 入力レコード
├── PolicyExplanationResult.java       // 出力レコード
├── PolicyDecisionStatus.java          // 判定ステータス Enum
└── PolicyReadonlyQueryService.java    // 実装クラス（package-private）

src/test/java/com/ses/privacy/foundation/policy/
└── PolicyReadonlyQueryServiceTest.java // 厳格な単体・負向テスト
```

---

## 7. 負向テスト設計（Negative Test Cases）

すべて本番 DB・実外部通信を使用せず、メモリ内の分離環境（`SyntheticEnvironmentGuard` + 署名 Fixture）で検証する。

1. **未登録要素の照会 (`testUnregisteredDataElement_ReturnsPolicyUnknown`)**:
   * カタログに存在しない要素を照会した場合、`POLICY_UNKNOWN` を返し、両許可フラグが `false` であること。
2. **未承認ポリシーの照会 (`testUnapprovedPolicy_ReturnsPolicyUnknown`)**:
   * NF03 の `CERTIFICATION_PII` のようにポリシーが承認されていない場合、`POLICY_UNKNOWN` を返すこと。
3. **期限切れポリシーの照会 (`testExpiredPolicy_ReturnsPolicyExpired`)**:
   * `expiresAt` を過去に設定したエンベロープに対し、信頼された Clock で判定して `POLICY_EXPIRED` を返すこと。
4. **失効済みポリシーの照会 (`testRevokedPolicy_ReturnsPolicyRevoked`)**:
   * 失効プロバイダーで `REVOKED` または `SUSPENDED` を返した場合、`POLICY_REVOKED` を返すこと。
5. **署名改竄の検知 (`testTamperedSignature_ReturnsPolicyDrift`)**:
   * エンベロープのペイロードが 1 バイトでも改竄されていた場合、Ed25519 検証で弾き `POLICY_DRIFT` を返すこと。
6. **職務分離（SoD）違反の拒絶 (`testSodViolation_ReturnsPolicyDrift`)**:
   * ポリシーの `requesterId` と `approverId` が同一である場合、`POLICY_DRIFT`（または拒絶）となること。
7. **テナント・アクション・プロバイダー不一致の拒絶 (`testMismatchedBinding_ReturnsPolicyDrift`)**:
   * 照会されたアクションやプロバイダーが、エンベロープに署名された値と異なる場合、`POLICY_DRIFT` を返すこと。
8. **NF08 外部送信の白名单誤解防止 (`testNf08Unapproved_RejectsExternalDispatchEvenIfInAllowlist`)**:
   * `POLICY_UNKNOWN` の時、`externalDispatchPermitted` が `false` であることを確認し、いかなる理由でも外部送信を認可しないこと。
9. **本番プロファイルでの実行拒絶 (`testProdProfile_ThrowsSecurityException`)**:
   * `Environment` に `prod` が含まれる場合、サービスの初期化または呼び出しが `SecurityException` で fail-closed すること。

---

## 8. 受入条件（Acceptance Criteria）

1. [ ] `PolicyReadonlyQueryPort` および関連型が定義され、外部 asOf 指定や boolean 注入が構造上排除されていること。
2. [ ] `ApprovalEnvelopeVerifier` と連携し、Ed25519 署名、Schema v1、Trust Anchor、SoD、期限、失効を厳密に検証できること。
3. [ ] 判定が `APPROVED_ACTIVE` 以外の場合、すべての処分・外送フラグが確実に `false` となること。
4. [ ] NF03 の未承認ポリシーが通常業務全停止へ誤拡大されないこと、および NF08 の白名单が未承認外送の口実にされないことが設計およびテストで保証されていること。
5. [ ] 新規 DDL、実外部通信、実 IdP 連携、処分 Executor、復旧放行コードが一切含まれていないこと。
