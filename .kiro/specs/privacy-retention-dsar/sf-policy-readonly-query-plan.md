# SF-Policy-Readonly-Query-Plan: NF03/NF08向け読み取り専用Policy照会・評価契約

状態: **PLAN REVISION / 総括および Astra 独立レビュー待ち。未実装。**
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
* 処分や外送の実行チケット、Capability、または認可 Receipt の発行

---

## 2. 可信呼び出し元コンテキスト（Trusted Caller Context）

### 2.1 合成環境における可信コンテキストの境界
呼び出し元アクターの属性（特に権限ロール）を、照会 DTO の文字列として自報させ、それを無検証で信頼することを厳格に禁止する。
本スライスでは実 IdP 連携を要求しないが、照会サービスへの入力は「検証済みの不可変な呼び出し元コンテキスト（`TrustedCallerContext`）」と「照会要求パラメーター（`PolicyEvaluationQuery`）」を明確に分離する。

```java
package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

/**
 * 合成環境・セキュリティ境界で認証済みの呼び出し元コンテキスト。
 * DTO による自報ロールは受け付けず、信頼されたファクトリまたはテストフィクスチャからのみ生成される。
 */
public record TrustedCallerContext(
    String tenantId,
    String actorId,
    TrustedActorRole actorRole,
    Instant authenticatedAt
) {
    public TrustedCallerContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (actorId.isBlank()) throw new IllegalArgumentException("actorId must not be blank");
    }
}
```

```java
package com.ses.privacy.foundation;

/**
 * ポリシー照会を許可された厳格なアクターロール。
 */
public enum TrustedActorRole {
    ROLE_SYSTEM_SERVICE,   // システム内部連携（NF03/NF08 バックエンドサービス）
    ROLE_PRIVACY_AUDITOR,  // プライバシー・監査担当ロール
    ROLE_ADMIN             // システム管理者ロール
}
```

### 2.2 呼び出し元検証と拒絶規約
照会サービス実行時、以下の順序で呼び出し元を検証し、違反時は後続のポリシー検証を行わずに直ちに `ACCESS_DENIED` を返却する：
1. `caller == null`、または `caller.actorId()` が空白・欠落している場合：
   * 判定: `ACCESS_DENIED`（理由: `MISSING_CALLER_IDENTITY`）
2. 照会要求の `query.tenantId()` と `caller.tenantId()` が一致しない場合（跨テナント照会の試行）：
   * 判定: `ACCESS_DENIED`（理由: `CALLER_TENANT_MISMATCH`）
3. 照会要求の `query.actorId()` と `caller.actorId()` が一致しない場合（なりすまし照会の試行）：
   * 判定: `ACCESS_DENIED`（理由: `CALLER_ACTOR_MISMATCH`）
4. `caller.actorRole()` がポリシー照会権限を持つロール（`ROLE_SYSTEM_SERVICE`, `ROLE_PRIVACY_AUDITOR`, `ROLE_ADMIN`）以外である場合（偽装または権限不足）：
   * 判定: `ACCESS_DENIED`（理由: `UNAUTHORIZED_ACTOR_ROLE`）

---

## 3. Policy 単独検証経路（Policy-only Verification Path）

### 3.1 既存検証器の制約と新設メソッド
既存の `ApprovalEnvelopeVerifier.verify(...)` は、`PolicyApprovalEnvelope` と `CaseApprovalEnvelope` の双方、および実際のターゲットバージョンとハッシュを要求する。
本照会は政策単独の評価であるため、**架空の Case エンベロープを捏造して既存メソッドを呼び出す行為は厳格に禁止する**。
既存の Policy+Case 完全検証契約は変更せず維持した上で、`ApprovalEnvelopeVerifier` にポリシー単独検証専用の入口 `verifyPolicyOnly` を追加する。

```java
package com.ses.privacy.foundation;

import java.time.Instant;

public final class ApprovalEnvelopeVerifier {
    // ... 既存のフィールドおよびコンストラクタは維持 ...

    /**
     * 既存の Policy+Case 認可検証（変更なし、実行認可 Capability を返却）
     */
    public VerifiedApproval verify(PolicyApprovalEnvelope policy, CaseApprovalEnvelope approval,
                                   String currentTenant, String actualTargetVersion, String actualTargetHash,
                                   String expectedAction, String expectedProvider) {
        // 既存実装そのまま維持
    }

    /**
     * 新設: Policy 単独の読み取り専用メタデータ検証。
     * 内部の verifyEnvelope("POLICY", ...) を直接再利用する。
     * 実行認可（VerifiedApproval）は生成せず、純粋な検証済みメタデータのみを返却する。
     */
    VerifiedPolicyMetadata verifyPolicyOnly(PolicyApprovalEnvelope policy,
                                            String expectedTenant,
                                            String expectedScope,
                                            String expectedDataElementId,
                                            String expectedAction,
                                            String expectedProvider,
                                            Instant now) {
        Objects.requireNonNull(policy, "policy envelope must not be null");
        environmentGuard.requireSyntheticAllowed(policy.metadata().audience());

        // 既存の private verifyEnvelope メソッドを完全に再利用
        TrustedAuthority authority = verifyEnvelope("POLICY", policy.dataElementId(), policy.metadata(),
                policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);

        // 職務分離（SoD）の検証
        if (policy.metadata().requesterId().equals(policy.metadata().approverId())) {
            throw new SecurityException("SoD_VIOLATION: policy requester and approver must differ");
        }

        // 期待されるバインディングとの照合
        if (!policy.metadata().tenantId().equals(expectedTenant)) {
            throw new SecurityException("TENANT_MISMATCH: expected=" + expectedTenant + ", actual=" + policy.metadata().tenantId());
        }
        if (!policy.metadata().scope().equals(expectedScope)) {
            throw new SecurityException("SCOPE_MISMATCH: expected=" + expectedScope + ", actual=" + policy.metadata().scope());
        }
        if (!policy.dataElementId().equals(expectedDataElementId)) {
            throw new SecurityException("DATA_ELEMENT_MISMATCH: expected=" + expectedDataElementId + ", actual=" + policy.dataElementId());
        }
        if (!policy.action().equals(expectedAction)) {
            throw new SecurityException("ACTION_MISMATCH: expected=" + expectedAction + ", actual=" + policy.action());
        }
        if (!policy.providerId().equals(expectedProvider)) {
            throw new SecurityException("PROVIDER_MISMATCH: expected=" + expectedProvider + ", actual=" + policy.providerId());
        }

        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        Instant effectiveExpiry = min(policy.metadata().expiresAt(), authority.validUntil());

        return new VerifiedPolicyMetadata(policy.metadata().tenantId(), policy.policyId(),
                policy.policyVersion(), policyDigest, policy.dataElementId(), policy.action(),
                policy.providerId(), policy.metadata().scope(), policy.metadata().effectiveAt(),
                effectiveExpiry, policy.retentionRule(), policy.disposition());
    }
}
```

```java
package com.ses.privacy.foundation;

import java.time.Instant;

/**
 * ポリシー単独検証済みの不変レコード。
 * 実行 Capability は一切持たず、パッケージプライベートとして閉じる。
 */
record VerifiedPolicyMetadata(
    String tenantId,
    String policyId,
    String policyVersion,
    String policyDigest,
    String dataElementId,
    String action,
    String providerId,
    String scope,
    Instant effectiveAt,
    Instant expiresAt,
    String retentionRule,
    String disposition
) {}
```

### 3.2 可信 Policy Source と Revision 選択規約
* `PolicyEnvelopeSource` インターフェースを通じて、指定された `(tenantId, dataElementId)` に登録されている候補ポリシーエンベロープを取得する。
* **Revision 選択アルゴリズム**:
  1. 候補が 0 件の場合：直ちに `POLICY_UNKNOWN`（理由: `POLICY_NOT_FOUND`）。
  2. 候補が複数存在する場合：エンベロープの `metadata.revision()`（単調増加数値）を降順ソートし、最新の revision を選択する。
  3. 最大 revision を持つエンベロープが複数存在し、そのダイジェストが一致しない場合：バージョン同定に曖昧性があるため `POLICY_DRIFT`（理由: `AMBIGUOUS_POLICY_REVISION`）として安全に拒絶する。
  4. 選択された最新エンベロープを `verifyPolicyOnly` に引き渡す。

### 3.3 パッケージ構成と可視性の保証
Java のパッケージアクセス境界により、子パッケージ（例: `com.ses.privacy.foundation.policy`）からは親パッケージの package-private クラス（`ApprovalEnvelopeCanonicalizer` 等）にアクセスできない。
不要な `public` 露出を防止しモジュール内のカプセル化を保つため、**本照会契約の全クラス・インターフェース・レコードは同一パッケージ `com.ses.privacy.foundation` に直接配置する**。

---

## 4. Explanation-only（説明専用）出力モデル

照会結果 DTO から、`externalDispatchPermitted` および `dispositionPermitted` などの**いかなる「許可（Permitted）」を示すブール値フィールドも完全に削除する**。
照会結果は「ポリシーの現状説明メタデータ」のみを保持し、いかなる実行権限の根拠にも転用できない構造とする。

### 4.1 照会入出力モデル

```java
package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * 照会要求 DTO。
 * actorRole は外部指定させず、過去 asOf 日時の指定も排除する。
 */
public record PolicyEvaluationQuery(
    String tenantId,
    String actorId,
    String moduleCode,       // "NF03_CERTIFICATION" または "NF08_AI_SUMMARY"
    String dataElementId,    // 例: "CERTIFICATION_PII", "AI_PROMPT_LOG"
    String targetAction,     // 例: "DISPOSITION_PURGE", "EXTERNAL_DISPATCH"
    String targetProvider    // 例: "local-db", "gemini-adapter"
) {
    public PolicyEvaluationQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(dataElementId, "dataElementId must not be null");
        Objects.requireNonNull(targetAction, "targetAction must not be null");
        Objects.requireNonNull(targetProvider, "targetProvider must not be null");
    }
}
```

```java
package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Optional;

/**
 * 照会結果 DTO。
 * 処分や外送の実行認可フラグは一切含まない（Explanation-only）。
 */
public record PolicyExplanationResult(
    PolicyDecisionStatus status,
    PolicyFailureReason failureReason, // status == APPROVED_ACTIVE の場合は NONE
    String policyVersion,
    String policyDigest,
    String dataElementId,
    String targetAction,
    String targetProvider,
    String scope,
    Optional<Instant> effectiveFrom,
    Optional<Instant> expiresAt,
    String explanationReason
) {}
```

### 4.2 照会ポートインターフェース

```java
package com.ses.privacy.foundation;

public interface PolicyReadonlyQueryPort {
    /**
     * 信頼される呼び出し元コンテキストに基づきポリシーの現在状態を評価・説明する。
     *
     * @param caller 認証済み呼び出し元コンテキスト（偽装不可）
     * @param query 照会要求パラメータ
     * @return 政策説明メタデータ（実行権限は含まない）
     */
    PolicyExplanationResult evaluate(TrustedCallerContext caller, PolicyEvaluationQuery query);
}
```

---

## 5. 確定的な状態と失敗原因のマッピング

例外のメッセージ文字列（`e.getMessage()` 等）を照合して判定することを厳格に禁止する。
すべての判定結果は、型付けされた列挙型 `PolicyDecisionStatus` および `PolicyFailureReason` に一意にマッピングする。

### 5.1 判定ステータスと型付け失敗理由の定義

```java
package com.ses.privacy.foundation;

public enum PolicyDecisionStatus {
    APPROVED_ACTIVE,   // 検証合格（ただし本批合成評価の結果であり、実行権限は付与しない）
    POLICY_UNKNOWN,    // 未登録、未承認、失効状態不明、ストア障害
    POLICY_EXPIRED,    // 期限切れ、または未発効
    POLICY_REVOKED,    // キーまたはエンベロープが明示的に失効
    POLICY_DRIFT,      // 署名改竄、スキーマ不正、Trust Anchor 未知、SoD 違反、バインディング不一致
    ACCESS_DENIED      // 呼び出し元の認証・権限・テナント不一致
}
```

```java
package com.ses.privacy.foundation;

public enum PolicyFailureReason {
    NONE,

    // ACCESS_DENIED
    MISSING_CALLER_IDENTITY,
    CALLER_TENANT_MISMATCH,
    CALLER_ACTOR_MISMATCH,
    UNAUTHORIZED_ACTOR_ROLE,

    // POLICY_UNKNOWN
    POLICY_NOT_FOUND,
    POLICY_UNAPPROVED,
    REVOCATION_STATUS_UNKNOWN,
    STORE_UNAVAILABLE,

    // POLICY_EXPIRED
    NOT_YET_EFFECTIVE,
    EXPIRED_AT_OR_AFTER_DEADLINE,
    AUTHORITY_VALIDITY_EXPIRED,

    // POLICY_REVOKED
    KEY_REVOKED,
    ENVELOPE_REVOKED,

    // POLICY_DRIFT
    UNSUPPORTED_SCHEMA_VERSION,
    UNKNOWN_TRUST_ANCHOR,
    SIGNATURE_INVALID,
    SOD_VIOLATION,
    TENANT_MISMATCH,
    SCOPE_MISMATCH,
    DATA_ELEMENT_MISMATCH,
    ACTION_MISMATCH,
    PROVIDER_MISMATCH,
    AMBIGUOUS_POLICY_REVISION
}
```

### 5.2 厳密な条件分岐と判定マッピング表

判定時刻はサービス内部の `clock.instant()` による `now` を使用する。

| 検査対象 | 条件 | ステータス (`PolicyDecisionStatus`) | 型付け失敗理由 (`PolicyFailureReason`) |
|---|---|---|---|
| 呼び出し元検証 | `caller == null` または `caller.actorId()` 空白 | `ACCESS_DENIED` | `MISSING_CALLER_IDENTITY` |
| 呼び出し元検証 | `!caller.tenantId().equals(query.tenantId())` | `ACCESS_DENIED` | `CALLER_TENANT_MISMATCH` |
| 呼び出し元検証 | `!caller.actorId().equals(query.actorId())` | `ACCESS_DENIED` | `CALLER_ACTOR_MISMATCH` |
| 呼び出し元検証 | `caller.actorRole()` が許可ロール外 | `ACCESS_DENIED` | `UNAUTHORIZED_ACTOR_ROLE` |
| プロファイル検証 | `Environment` に `prod` 混入 | 例外送出 | `SecurityException` (fail-closed) |
| ストア取得 | ストアから例外発生 | `POLICY_UNKNOWN` | `STORE_UNAVAILABLE` |
| ストア取得 | 候補 0 件 | `POLICY_UNKNOWN` | `POLICY_NOT_FOUND` |
| ストア取得 | 同一最新 revision で digest 不一致 | `POLICY_DRIFT` | `AMBIGUOUS_POLICY_REVISION` |
| スキーマ検証 | `!schemaVersion.equals("1")` | `POLICY_DRIFT` | `UNSUPPORTED_SCHEMA_VERSION` |
| 署名機関 | 未知の `authorityId:keyId` | `POLICY_DRIFT` | `UNKNOWN_TRUST_ANCHOR` |
| 発効日時 | `now.isBefore(effectiveAt)` | `POLICY_EXPIRED` | `NOT_YET_EFFECTIVE` |
| 満了日時 | `!now.isBefore(expiresAt)` (即ち `now >= expiresAt`) | `POLICY_EXPIRED` | `EXPIRED_AT_OR_AFTER_DEADLINE` |
| 機関有効期限 | `!now.isBefore(authority.validUntil())` | `POLICY_EXPIRED` | `AUTHORITY_VALIDITY_EXPIRED` |
| 失効状態 | `keyStatus == REVOKED` | `POLICY_REVOKED` | `KEY_REVOKED` |
| 失効状態 | `envelopeStatus == REVOKED` | `POLICY_REVOKED` | `ENVELOPE_REVOKED` |
| 失効状態 | `keyStatus == UNKNOWN` または `envelopeStatus == UNKNOWN` | `POLICY_UNKNOWN` | `REVOCATION_STATUS_UNKNOWN` |
| 署名検証 | Ed25519 署名不一致 | `POLICY_DRIFT` | `SIGNATURE_INVALID` |
| 職務分離 (SoD) | `requesterId.equals(approverId)` | `POLICY_DRIFT` | `SOD_VIOLATION` |
| バインディング | `tenantId`, `scope`, `dataElement`, `action`, `provider` 不一致 | `POLICY_DRIFT` | `*_MISMATCH` 各種 |
| 全検証合格 | 上記すべての不合格事由が存在しない | `APPROVED_ACTIVE` | `NONE` |

> **注記（RevocationStatus）**: 既存コードベース（`RevocationStatus.java`）に定義されているステータスは `ACTIVE`, `REVOKED`, `UNKNOWN` の 3 種のみである（`SUSPENDED` は存在しない）。本表はその実態に厳密に準拠している。

---

## 6. NF03 / NF08 固有の運用解釈規則

### 6.1 NF03（スキル資格管理）における業務継続と処分境界の分離
* **背景**: 現時点で NF03 の `CERTIFICATION_PII` に対する DG-07 保持ポリシーは未承認（`POLICY_UNKNOWN`）である。
* **解釈規約**:
  1. `POLICY_UNKNOWN` は「保持期限到達に伴う廃棄・自動消去等の処分ポリシーが未承認である」ことを意味する。
  2. これを**「NF03 の通常の資格登録・閲覧・更新・編集業務をすべて停止し、全画面を只読（Read-only）に封鎖しなければならない」と過剰拡大して解釈してはならない**。
  3. 封鎖されるのは「retention 処分（廃棄・パージ）」の実行パスのみであり、エンジニアの資格管理という日常の通常業務機能は妨げられない。

### 6.2 NF08（AI 要約・ログ連携）における外部送信の厳格解釈
* **解釈規約**:
  1. 照会結果が `APPROVED_ACTIVE` であっても、それ自体は**外部 AI への送信実行許可を一切与えない**。
  2. 送信の実行には、別途独立した承認エンベロープとディスパッチ認可が必要である。
  3. **許可リスト（ホワイトリスト）は送信可能な技術的上限を規定するものに過ぎず、送信を認可するライセンスではない**。「許可リスト（ホワイトリスト）に含まれる項目だからポリシー未承認でも送信してよい」、あるいは「APPROVED_ACTIVE なら許可リストの範囲で自動送信してよい」という曲解を厳格に禁止する。

---

## 7. クラス設計と配置計画

すべての型を同一パッケージ `com.ses.privacy.foundation` に集約し、パッケージプライベートな既存暗号・正規化コンポーネントを安全に利用する。

```text
src/main/java/com/ses/privacy/foundation/
├── TrustedCallerContext.java          // 可信呼び出し元コンテキスト（不可変）
├── TrustedActorRole.java              // 呼び出し元権限ロール Enum
├── PolicyReadonlyQueryPort.java       // 照会インターフェース（public）
├── PolicyEvaluationQuery.java         // 照会要求 DTO
├── PolicyExplanationResult.java       // 照会結果 DTO（Explanation-only）
├── PolicyDecisionStatus.java          // 判定ステータス Enum
├── PolicyFailureReason.java           // 型付け失敗原因 Enum
├── PolicyEnvelopeSource.java          // ポリシー候補取得インターフェース（package-private）
├── VerifiedPolicyMetadata.java        // ポリシー検証済み内部レコード（package-private）
├── ApprovalEnvelopeVerifier.java      // 既存検証器（verifyPolicyOnly メソッドを追加）
└── PolicyReadonlyQueryService.java    // 照会サービス実装クラス（package-private）

src/test/java/com/ses/privacy/foundation/
└── PolicyReadonlyQueryServiceTest.java // 単体・負向・正向テスト
```

---

## 8. 負向および正向テスト設計（Test Cases）

本番 DB・実外部通信を使用せず、メモリ内の合成テスト環境（`SyntheticEnvironmentGuard` + 署名 Fixture）で完全に独立検証する。

### 8.1 呼び出し元コンテキスト検証テスト
1. **偽装管理者の拒絶 (`testCallerContext_SpoofedRole_ReturnsAccessDenied`)**:
   * 未認可ロールを持つ `TrustedCallerContext` で照会した場合、`ACCESS_DENIED`（理由: `UNAUTHORIZED_ACTOR_ROLE`）となること。
2. **跨テナント照会の拒絶 (`testCallerContext_CrossTenantMismatch_ReturnsAccessDenied`)**:
   * `caller.tenantId` と `query.tenantId` が不一致の場合、`ACCESS_DENIED`（理由: `CALLER_TENANT_MISMATCH`）となること。
3. **欠落・空白アクターの拒絶 (`testCallerContext_BlankActorId_ReturnsAccessDenied`)**:
   * `actorId` が空白のコンテキストを拒絶し、`ACCESS_DENIED`（理由: `MISSING_CALLER_IDENTITY`）となること。

### 8.2 ポリシー検証テスト
4. **未登録要素の照会 (`testUnregisteredDataElement_ReturnsPolicyUnknown`)**:
   * ストアに候補が存在しない場合、`POLICY_UNKNOWN`（理由: `POLICY_NOT_FOUND`）を返すこと。
5. **未承認ポリシーの照会 (`testUnapprovedPolicy_ReturnsPolicyUnknown`)**:
   * NF03 の `CERTIFICATION_PII` のように未承認の場合、`POLICY_UNKNOWN`（理由: `POLICY_UNAPPROVED`）を返すこと。
6. **未発効ポリシーの照会 (`testNotYetEffectivePolicy_ReturnsPolicyExpired`)**:
   * `now.isBefore(effectiveAt)` の場合、`POLICY_EXPIRED`（理由: `NOT_YET_EFFECTIVE`）を返すこと。
7. **満了時刻到達ポリシーの照会 (`testExpiredPolicy_AtExactDeadline_ReturnsPolicyExpired`)**:
   * `now.equals(expiresAt)`（半開区間の境界）の場合、`POLICY_EXPIRED`（理由: `EXPIRED_AT_OR_AFTER_DEADLINE`）を返すこと。
8. **失効ポリシーの照会 (`testRevokedPolicy_ReturnsPolicyRevoked`)**:
   * `revocations.statusOf` が `REVOKED` の場合、`POLICY_REVOKED`（理由: `ENVELOPE_REVOKED` または `KEY_REVOKED`）を返すこと。
9. **失効状態不明ポリシーの照会 (`testRevocationStatusUnknown_ReturnsPolicyUnknown`)**:
   * `revocations.statusOf` が `UNKNOWN` の場合、`POLICY_UNKNOWN`（理由: `REVOCATION_STATUS_UNKNOWN`）を返すこと。
10. **署名改竄の検知 (`testTamperedSignature_ReturnsPolicyDrift`)**:
    * ペイロードが 1 バイトでも改竄されていた場合、`POLICY_DRIFT`（理由: `SIGNATURE_INVALID`）を返すこと。
11. **職務分離（SoD）違反の拒絶 (`testSodViolation_ReturnsPolicyDrift`)**:
    * `requesterId.equals(approverId)` のポリシーは、`POLICY_DRIFT`（理由: `SOD_VIOLATION`）となること。
12. **バインディング不一致の拒絶 (`testMismatchedBinding_ReturnsPolicyDrift`)**:
    * 要求されたアクションやプロバイダーがポリシー署名値と異なる場合、`POLICY_DRIFT`（理由: `ACTION_MISMATCH` 等）を返すこと。
13. **同一 Revision 衝突の検知 (`testAmbiguousRevision_ReturnsPolicyDrift`)**:
    * 同一 revision で異なる digest を持つポリシーが競合した場合、`POLICY_DRIFT`（理由: `AMBIGUOUS_POLICY_REVISION`）を返すこと。
14. **本番プロファイルでの実行拒絶 (`testProdProfile_ThrowsSecurityException`)**:
    * `Environment` に `prod` が含まれる場合、初期化または呼び出しが `SecurityException` で fail-closed すること。

### 8.3 正向テスト（成功しても実行権限を与えないことの証明）
15. **有効な合成ポリシーの評価 (`testValidSyntheticPolicy_ReturnsApprovedActiveWithoutExecutionCapability`)**:
    * 有効な Ed25519 署名、現在有効期間内、SoD 適合の合成ポリシーを評価した場合：
      * `status` が `APPROVED_ACTIVE`、`failureReason` が `NONE` となること。
      * 返却された DTO に `Permitted` などの権限フラグが存在しないこと。
      * いかなる実行 Capability も返却されず、下流の処分や送信操作が本結果のみでは一切行えないこと。

---

## 9. 受入条件（Acceptance Criteria）

1. [ ] `TrustedCallerContext` により呼び出し元コンテキストが厳密に型付けされ、自報ロールや跨テナント照会が排除されていること。
2. [ ] `ApprovalEnvelopeVerifier` に `verifyPolicyOnly` が追加され、Case エンベロープの捏造なしにポリシー単独検証が既存ロジックを再利用して行われること。
3. [ ] 返却 DTO `PolicyExplanationResult` から許可ブール値が完全に削除され、純粋な説明メタデータのみとなっていること。
4. [ ] 失敗理由が文字列照合ではなく型付けされた `PolicyFailureReason` に一意にマッピングされ、未発効・満了境界（`now == expiresAt`）・RevocationStatus（ACTIVE/REVOKED/UNKNOWN）が厳密に処理されていること。
5. [ ] 全クラスが `com.ses.privacy.foundation` に配置され、パッケージアクセス違反や不要な public 露出がないこと。
6. [ ] NF03 の通常業務が不当に停止されないこと、および NF08 において許可リスト（ホワイトリスト）が未承認送信の口実にならないことが明文化されていること。
7. [ ] 新規 DDL、実外部通信、実 IdP 連携、処分 Executor、復旧放行コードが一切含まれていないこと。
