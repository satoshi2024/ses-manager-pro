# SF-Policy-Readonly-Query-Plan: NF03/NF08向け読み取り専用Policy照会・評価契約

状態: **PLAN REVISION (R3) / 総括および Astra 独立レビュー待ち。未実装。**
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

## 2. 呼び出し元入口と可信コンテキスト（Trusted Caller Boundary）

### 2.1 単一引数インターフェースとプロバイダー注入
呼び出し側が実行時に任意のアクターロールや可信コンテキストを差し替える脆弱性を構造上排除するため、照会ポート `PolicyReadonlyQueryPort.evaluate` は照会要求 `PolicyEvaluationQuery` のみを受け取る。
サービスは、コンストラクタで注入された `SyntheticCallerContextProvider` を通じて呼び出し元の身元とロールを取得する。

```java
package com.ses.privacy.foundation;

public interface PolicyReadonlyQueryPort {
    /**
     * 注入された Caller Provider と Clock に基づき、指定された照会要求の政策を評価・説明する。
     * 呼び出し元が実行時に Provider やアクターロール、asOf 日時を差し替えることは禁止される。
     *
     * @param query 照会要求パラメータ（テナント、アクターID、対象要素・操作・プロバイダー）
     * @return 政策説明メタデータ（実行権限は含まない）
     */
    PolicyExplanationResult evaluate(PolicyEvaluationQuery query);
}
```

```java
package com.ses.privacy.foundation;

import java.util.Optional;

/**
 * サービス初期化時に注入される合成呼び出し元プロバイダー。
 * 本スライスでは隔離された合成環境の固定マッピング・テストフィクスチャを用い、実 IdP には接続しない。
 */
@FunctionalInterface
interface SyntheticCallerContextProvider {
    Optional<TrustedCallerContext> getCallerContext();
}
```

### 2.2 可信呼び出し元コンテキストの構造
呼び出し元コンテキスト `TrustedCallerContext` は不可変レコードとし、未認可ロールの拒絶テストを可能にするため、一般業務ユーザーロール `ROLE_UNPRIVILEGED_USER` を型安全に定義する。

```java
package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

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

public enum TrustedActorRole {
    ROLE_SYSTEM_SERVICE,     // システム内部連携ロール（照会可）
    ROLE_PRIVACY_AUDITOR,    // プライバシー・監査ロール（照会可）
    ROLE_ADMIN,               // システム管理者ロール（照会可）
    ROLE_UNPRIVILEGED_USER   // 一般業務ユーザー（照会権限なし・ACCESS_DENIED 対象）
}
```

### 2.3 呼び出し元検証と拒絶規約
サービス実行時、以下の順序で呼び出し元を検証し、不合格時は後続のカタログ検索や署名検証を行わずに直ちに `ACCESS_DENIED` を返却する：
1. **身元欠落**:
   `callerProvider.getCallerContext()` が空、または `caller.actorId()` が空白・欠落している場合：
   * 判定: `ACCESS_DENIED`（理由: `MISSING_CALLER_IDENTITY`）
2. **跨テナント照会**:
   照会要求の `query.tenantId()` と `caller.tenantId()` が一致しない場合：
   * 判定: `ACCESS_DENIED`（理由: `CALLER_TENANT_MISMATCH`）
3. **冒名（アクター不一致）照会**:
   照会要求の `query.actorId()` と `caller.actorId()` が一致しない場合：
   * 判定: `ACCESS_DENIED`（理由: `CALLER_ACTOR_MISMATCH`）
4. **無権限アクター**:
   `caller.actorRole()` が `ROLE_UNPRIVILEGED_USER` など非認可ロールである場合：
   * 判定: `ACCESS_DENIED`（理由: `UNAUTHORIZED_ACTOR_ROLE`）

---

## 3. 固定カタログとソース契約（Catalog & Source Contract）

### 3.1 固定 Synthetic カタログ
照会パラメータ `(moduleCode, dataElementId, targetAction, targetProvider)` を、決定論的に固定の `policyId` と `expectedScope` へマッピングする。カタログ未登録の組み合わせは直ちに要素未登録（`POLICY_NOT_FOUND`）とする。

```java
package com.ses.privacy.foundation;

import java.util.Optional;

interface SyntheticPolicyCatalog {
    Optional<CatalogEntry> resolve(String moduleCode, String dataElementId, String action, String providerId);

    record CatalogEntry(String policyId, String expectedScope) {}
}
```

**本スライスの固定登録エントリー例**:
* `(NF03_CERTIFICATION, CERTIFICATION_PII, DISPOSITION_PURGE, local-db)`
  $\rightarrow$ `policyId: "policy-nf03-cert-v1"`, `expectedScope: "CERTIFICATION"`
* `(NF08_AI_SUMMARY, AI_PROMPT_LOG, DISPOSITION_PURGE, local-db)`
  $\rightarrow$ `policyId: "policy-nf08-log-purge-v1"`, `expectedScope: "AI_FEEDBACK"`
* `(NF08_AI_SUMMARY, AI_PROMPT_LOG, EXTERNAL_DISPATCH, gemini-adapter)`
  $\rightarrow$ `policyId: "policy-nf08-log-dispatch-v1"`, `expectedScope: "AI_FEEDBACK"`

### 3.2 ポリシーソース契約と不透明 Revision 規約
* **不透明（opaque）Revision**:
  既存コードベースの設計に基づき、`revision` は不透明な文字列として扱う。**Revision に対する数値的な大小比較やグローバル数値ソートは厳格に禁止する**。
* **明示的な現在版特定**:
  ソースは可信テナントおよび完全なポリシー識別子に基づき、現在指定されているエンベロープ、`currentRevision`、および `currentDigest` を返却する。
* **フォールバック禁止（Fail-Closed）**:
  現在エンベロープが欠落、未承認、不整合、または検証失敗となった場合、安全側に倒して直ちに拒絶し、**決して過去のリビジョンや古いエンベロープへフォールバックしてはならない**。

```java
package com.ses.privacy.foundation;

interface PolicyEnvelopeSource {
    PolicyLookupResult lookupCurrentPolicy(String tenantId, String policyId, String dataElementId, String action, String providerId);
}

record PolicyLookupResult(
    LookupStatus status,
    PolicyApprovalEnvelope currentEnvelope,
    String currentRevision,
    String currentDigest
) {
    enum LookupStatus {
        CURRENT_CANDIDATE,  // 承認済みエンベロープを特定
        UNAPPROVED,         // 下書きまたは未承認状態
        NOT_FOUND,          // 当該ポリシーが存在しない
        STORE_UNAVAILABLE   // ストア照会例外
    }
}
```

---

## 4. 型付け検証結果と検証経路（Policy-only Verification）

### 4.1 型付け例外 `PolicySecurityException`
例外メッセージ文字列（`e.getMessage()`）に依存した判定を排除し、検証失敗理由を型安全に伝達するため、`PolicyFailureReason` を保持する専用例外を導入する。

```java
package com.ses.privacy.foundation;

import java.util.Objects;

final class PolicySecurityException extends SecurityException {
    private final PolicyFailureReason reason;

    PolicySecurityException(PolicyFailureReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    PolicyFailureReason reason() { return reason; }
}
```

### 4.2 既存検証器の内部再利用と Policy-only 入口
既存の Policy+Case 完全検証メソッド `verify(...)` の契約・シグネチャ・拒絶セマンティクスは一切改変せず維持する。
その上で、内部共通検証ロジックを型付け例外送出方式へ統一し、`ApprovalEnvelopeVerifier` に Policy 単独検証入口 `verifyPolicyOnly` を追加する。
Case エンベロープの捏造は行わず、失効状態プロバイダーの二重呼び出しも行わない。

```java
package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

public final class ApprovalEnvelopeVerifier {
    // ... 既存フィールドおよびコンストラクタは維持 ...

    /**
     * 既存の Policy+Case 認可検証（変更なし、実行認可 Capability を返却）
     */
    public VerifiedApproval verify(PolicyApprovalEnvelope policy, CaseApprovalEnvelope approval,
                                   String currentTenant, String actualTargetVersion, String actualTargetHash,
                                   String expectedAction, String expectedProvider) {
        // 既存実装そのまま維持（Case 承認と Target の突き合わせ）
    }

    /**
     * 新設: Policy 単独の読み取り専用メタデータ検証。
     * 同一の検証ロジックを一度だけ走らせ、型付け例外または検証済みメタデータを返却する。
     * 実行認可（VerifiedApproval）は生成しない。
     */
    VerifiedPolicyMetadata verifyPolicyOnly(PolicyApprovalEnvelope policy,
                                            String expectedTenant,
                                            String expectedScope,
                                            String expectedDataElementId,
                                            String expectedAction,
                                            String expectedProvider,
                                            Instant now) throws PolicySecurityException {
        Objects.requireNonNull(policy, "policy envelope must not be null");
        // 環境ガードの拒絶はビジネス DTO に変換せず、未捕捉の SecurityException として直ちに中断する
        environmentGuard.requireSyntheticAllowed(policy.metadata().audience());

        // 内部共通検証の実行
        TrustedAuthority authority = verifyEnvelopeTyped("POLICY", policy.dataElementId(), policy.metadata(),
                policy.signature(), ApprovalEnvelopeCanonicalizer.canonicalBytes(policy),
                policy.action(), policy.providerId(), now);

        // 職務分離 (SoD) の検証
        if (policy.metadata().requesterId().equals(policy.metadata().approverId())) {
            throw new PolicySecurityException(PolicyFailureReason.SOD_VIOLATION, "SoD violation: requester and approver must differ");
        }

        // 期待されるバインディング照合
        if (!policy.metadata().tenantId().equals(expectedTenant)) {
            throw new PolicySecurityException(PolicyFailureReason.TENANT_MISMATCH, "tenant mismatch");
        }
        if (!policy.metadata().scope().equals(expectedScope)) {
            throw new PolicySecurityException(PolicyFailureReason.SCOPE_MISMATCH, "scope mismatch");
        }
        if (!policy.dataElementId().equals(expectedDataElementId)) {
            throw new PolicySecurityException(PolicyFailureReason.DATA_ELEMENT_MISMATCH, "dataElement mismatch");
        }
        if (!policy.action().equals(expectedAction)) {
            throw new PolicySecurityException(PolicyFailureReason.ACTION_MISMATCH, "action mismatch");
        }
        if (!policy.providerId().equals(expectedProvider)) {
            throw new PolicySecurityException(PolicyFailureReason.PROVIDER_MISMATCH, "provider mismatch");
        }

        String policyDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(policy));
        Instant effectiveExpiry = min(policy.metadata().expiresAt(), authority.validUntil());

        return new VerifiedPolicyMetadata(policy.metadata().tenantId(), policy.policyId(),
                policy.policyVersion(), policyDigest, policy.dataElementId(), policy.action(),
                policy.providerId(), policy.metadata().scope(), policy.metadata().effectiveAt(),
                effectiveExpiry, policy.retentionRule(), policy.disposition());
    }

    /**
     * 内部共通検証。失敗時は型付けされた PolicyFailureReason を持つ例外を送出する。
     */
    private TrustedAuthority verifyEnvelopeTyped(String envelopeKind, String dataElementId, ApprovalEnvelopeMetadata metadata,
                                                 byte[] signature, byte[] payload,
                                                 String action, String provider, Instant now) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(metadata.schemaVersion())) {
            throw new PolicySecurityException(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION, "unsupported schema version");
        }
        String anchorKey = metadata.authorityId() + ":" + metadata.keyId();
        TrustedAuthority anchor = trustAnchors.get(anchorKey);
        if (anchor == null) {
            throw new PolicySecurityException(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, "unknown trust anchor");
        }
        if (!anchor.authorityId().equals(metadata.authorityId()) || !anchor.keyId().equals(metadata.keyId())
                || !anchor.tenants().contains(metadata.tenantId()) || !anchor.scopes().contains(metadata.scope())
                || (dataElementId != null && !anchor.dataElements().contains(dataElementId))
                || !anchor.envelopeKinds().contains(envelopeKind)
                || !anchor.audiences().contains(metadata.audience()) || !anchor.actions().contains(action)
                || !anchor.providers().contains(provider)) {
            throw new PolicySecurityException(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, "authority scope mismatch");
        }
        if (now.isBefore(anchor.validFrom()) || !now.isBefore(anchor.validUntil())) {
            throw new PolicySecurityException(PolicyFailureReason.AUTHORITY_VALIDITY_EXPIRED, "authority validity expired");
        }
        if (now.isBefore(metadata.issuedAt())) {
            throw new PolicySecurityException(PolicyFailureReason.NOT_YET_ISSUED, "envelope issuedAt is in future");
        }
        if (now.isBefore(metadata.effectiveAt())) {
            throw new PolicySecurityException(PolicyFailureReason.NOT_YET_EFFECTIVE, "envelope not yet effective");
        }
        if (!now.isBefore(metadata.expiresAt())) { // 半開区間 [effectiveAt, expiresAt) の境界判定
            throw new PolicySecurityException(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, "envelope expired at or after deadline");
        }

        RevocationStatus keyStatus;
        RevocationStatus envelopeStatus;
        try {
            keyStatus = revocations.statusOf(metadata.authorityId() + ":" + metadata.keyId());
            envelopeStatus = revocations.statusOf(metadata.revocationReference());
        } catch (RuntimeException re) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, "revocation provider error", re);
        }

        if (keyStatus == RevocationStatus.REVOKED) {
            throw new PolicySecurityException(PolicyFailureReason.KEY_REVOKED, "signing key is revoked");
        }
        if (envelopeStatus == RevocationStatus.REVOKED) {
            throw new PolicySecurityException(PolicyFailureReason.ENVELOPE_REVOKED, "envelope is revoked");
        }
        if (keyStatus == RevocationStatus.UNKNOWN || envelopeStatus == RevocationStatus.UNKNOWN) {
            throw new PolicySecurityException(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, "revocation status unknown");
        }

        try {
            java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(anchor.publicKey());
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new PolicySecurityException(PolicyFailureReason.SIGNATURE_INVALID, "invalid signature");
            }
        } catch (java.security.GeneralSecurityException exception) {
            throw new PolicySecurityException(PolicyFailureReason.SIGNATURE_INVALID, "signature verification exception", exception);
        }
        return anchor;
    }
}
```

```java
package com.ses.privacy.foundation;

import java.time.Instant;

/**
 * ポリシー検証済み内部不変メタデータ（Capability は含まず、パッケージプライベートに閉じる）。
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

---

## 5. Explanation-only（説明専用）出力モデル

照会結果 DTO には、`externalDispatchPermitted` や `dispositionPermitted` などの**いかなる「許可（Permitted）」を示すブール値フィールドも含めない**。
返却されるのは「ポリシーの現状説明メタデータ」のみであり、いかなる実行権限の根拠にも転用できない。

```java
package com.ses.privacy.foundation;

import java.util.Objects;

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

---

## 6. 確定的な状態と失敗原因のマッピング

判定時刻はサービス内部の `clock.instant()` による `now` を使用し、外部からの `asOf` 指定は受け付けない。

### 6.1 判定ステータスと型付け失敗理由の定義

```java
package com.ses.privacy.foundation;

public enum PolicyDecisionStatus {
    APPROVED_ACTIVE,   // 検証合格（本批合成評価の結果であり、実行権限は付与しない）
    POLICY_UNKNOWN,    // 未登録、未承認、失効状態不明、ストア障害
    POLICY_EXPIRED,    // 期限切れ、未発効、または未発行
    POLICY_REVOKED,    // キーまたはエンベロープが明示的に失効
    POLICY_DRIFT,      // 署名改竄、スキーマ不正、Trust Anchor 未知、SoD 違反、バインディング不一致
    ACCESS_DENIED      // 呼び出し元の認証・権限・テナント不一致
}
```

```java
package com.ses.privacy.foundation;

public enum PolicyFailureReason {
    NONE,

    // ACCESS_DENIED (呼び出し元の拒絶)
    MISSING_CALLER_IDENTITY,
    CALLER_TENANT_MISMATCH,
    CALLER_ACTOR_MISMATCH,
    UNAUTHORIZED_ACTOR_ROLE,

    // POLICY_UNKNOWN (未決・不明・障害)
    POLICY_NOT_FOUND,
    POLICY_UNAPPROVED,
    REVOCATION_STATUS_UNKNOWN,
    REVOCATION_PROVIDER_ERROR,
    STORE_UNAVAILABLE,

    // POLICY_EXPIRED (時刻・期限不適合)
    NOT_YET_ISSUED,
    NOT_YET_EFFECTIVE,
    EXPIRED_AT_OR_AFTER_DEADLINE,
    AUTHORITY_VALIDITY_EXPIRED,

    // POLICY_REVOKED (明示的失効)
    KEY_REVOKED,
    ENVELOPE_REVOKED,

    // POLICY_DRIFT (ポリシー自体の改竄・不整合)
    UNSUPPORTED_SCHEMA_VERSION,
    UNKNOWN_TRUST_ANCHOR,
    AUTHORITY_SCOPE_MISMATCH,
    SIGNATURE_INVALID,
    SOD_VIOLATION,
    TENANT_MISMATCH,
    SCOPE_MISMATCH,
    DATA_ELEMENT_MISMATCH,
    ACTION_MISMATCH,
    PROVIDER_MISMATCH
}
```

### 6.2 判定順序と確定マッピング表

判定処理は以下の厳格な順序で実施する。環境ガードによる拒絶は例外として直接中断し、DTO への丸め込みを行わない。

```text
[呼び出し開始: evaluate(query)]
       │
       ▼ (Step 0: プロファイル防護)
[SyntheticEnvironmentGuard] ──▶ active/default に "prod" 混入時は即座に SecurityException (中断)
       │
       ▼ (Step 1: 呼び出し元検証)
[Caller Context 検証] ──▶ 欠落/跨テナント/冒名/非認可ロールは即座に ACCESS_DENIED
       │
       ▼ (Step 2: カタログ検索)
[SyntheticPolicyCatalog] ──▶ 未登録の組み合わせは POLICY_UNKNOWN (POLICY_NOT_FOUND)
       │
       ▼ (Step 3: ポリシーソース取得)
[PolicyEnvelopeSource] ──▶ NOT_FOUND / UNAPPROVED / STORE_UNAVAILABLE に応じて POLICY_UNKNOWN
       │
       ▼ (Step 4: ポリシー単独検証)
[ApprovalEnvelopeVerifier.verifyPolicyOnly] ──▶ PolicySecurityException 発生時は型付け理由をそのままマッピング
       │
       ▼ (Step 5: 全合格)
[PolicyExplanationResult] ──▶ APPROVED_ACTIVE / NONE (説明メタデータのみ返却)
```

| 判定ステップ | 検査条件 | ステータス (`PolicyDecisionStatus`) | 型付け失敗理由 (`PolicyFailureReason`) |
|---|---|---|---|
| Step 0: プロファイル | `Environment` に `prod` が含まれる | **未捕捉の例外送出** | `SecurityException` (fail-closed 中断) |
| Step 1: 呼び出し元 | `caller == null` または `caller.actorId()` 空白 | `ACCESS_DENIED` | `MISSING_CALLER_IDENTITY` |
| Step 1: 呼び出し元 | `!caller.tenantId().equals(query.tenantId())` | `ACCESS_DENIED` | `CALLER_TENANT_MISMATCH` |
| Step 1: 呼び出し元 | `!caller.actorId().equals(query.actorId())` | `ACCESS_DENIED` | `CALLER_ACTOR_MISMATCH` |
| Step 1: 呼び出し元 | `caller.actorRole()` が許可ロール外 | `ACCESS_DENIED` | `UNAUTHORIZED_ACTOR_ROLE` |
| Step 2: カタログ | カタログ未登録の組み合わせ | `POLICY_UNKNOWN` | `POLICY_NOT_FOUND` |
| Step 3: ソース | `LookupStatus.NOT_FOUND` | `POLICY_UNKNOWN` | `POLICY_NOT_FOUND` |
| Step 3: ソース | `LookupStatus.UNAPPROVED` | `POLICY_UNKNOWN` | `POLICY_UNAPPROVED` |
| Step 3: ソース | `LookupStatus.STORE_UNAVAILABLE` | `POLICY_UNKNOWN` | `STORE_UNAVAILABLE` |
| Step 4: 検証 | `!schemaVersion.equals("1")` | `POLICY_DRIFT` | `UNSUPPORTED_SCHEMA_VERSION` |
| Step 4: 検証 | 未知の `authorityId:keyId` | `POLICY_DRIFT` | `UNKNOWN_TRUST_ANCHOR` |
| Step 4: 検証 | 機関の管轄範囲（テナント、スコープ等）不一致 | `POLICY_DRIFT` | `AUTHORITY_SCOPE_MISMATCH` |
| Step 4: 検証 | `now.isBefore(authority.validFrom()) || now >= authority.validUntil()` | `POLICY_EXPIRED` | `AUTHORITY_VALIDITY_EXPIRED` |
| Step 4: 検証 | `now.isBefore(metadata.issuedAt())` | `POLICY_EXPIRED` | `NOT_YET_ISSUED` |
| Step 4: 検証 | `now.isBefore(metadata.effectiveAt())` | `POLICY_EXPIRED` | `NOT_YET_EFFECTIVE` |
| Step 4: 検証 | `!now.isBefore(metadata.expiresAt())` (即ち `now >= expiresAt`) | `POLICY_EXPIRED` | `EXPIRED_AT_OR_AFTER_DEADLINE` |
| Step 4: 検証 | 失効プロバイダーの例外発生 | `POLICY_UNKNOWN` | `REVOCATION_PROVIDER_ERROR` |
| Step 4: 検証 | `keyStatus == REVOKED` | `POLICY_REVOKED` | `KEY_REVOKED` |
| Step 4: 検証 | `envelopeStatus == REVOKED` | `POLICY_REVOKED` | `ENVELOPE_REVOKED` |
| Step 4: 検証 | `keyStatus == UNKNOWN || envelopeStatus == UNKNOWN` | `POLICY_UNKNOWN` | `REVOCATION_STATUS_UNKNOWN` |
| Step 4: 検証 | Ed25519 署名不一致 | `POLICY_DRIFT` | `SIGNATURE_INVALID` |
| Step 4: 検証 | 職務分離 (SoD) 違反 (`requesterId.equals(approverId)`) | `POLICY_DRIFT` | `SOD_VIOLATION` |
| Step 4: 検証 | `tenantId`, `scope`, `dataElement`, `action`, `provider` 不一致 | `POLICY_DRIFT` | `*_MISMATCH` 各種 |
| Step 5: 全合格 | 上記すべての不合格事由が存在しない | `APPROVED_ACTIVE` | `NONE` |

> **注記（RevocationStatus の実態）**: 既存コードベース（`RevocationStatus.java`）に定義されているステータスは `ACTIVE`, `REVOKED`, `UNKNOWN` の 3 種のみである（`SUSPENDED` は存在しない）。本マッピング表はその実態に厳密に準拠している。

---

## 7. NF03 / NF08 固有の運用解釈規則

### 7.1 NF03（スキル資格管理）における業務継続と処分境界の分離
* **背景**: 現時点で NF03 の `CERTIFICATION_PII` に対する DG-07 保持ポリシーは未承認（`POLICY_UNKNOWN`）である。
* **解釈規約**:
  1. `POLICY_UNKNOWN` は「保持期限到達に伴う廃棄・自動消去等の処分ポリシーが未承認である」ことを意味する。
  2. これを**「NF03 の通常の資格登録・閲覧・更新・編集業務をすべて停止し、全画面を只読（Read-only）に封鎖しなければならない」と過剰拡大して解釈してはならない**。
  3. 封鎖されるのは「retention 処分（廃棄・パージ）」の実行パスのみであり、エンジニアの資格管理という日常の通常業務機能は妨げられない。

### 7.2 NF08（AI 要約・ログ連携）における外部送信の厳格解釈
* **解釈規約**:
  1. 照会結果が `APPROVED_ACTIVE` であっても、それ自体は**外部 AI への送信実行許可を一切与えない**。
  2. 送信の実行には、別途独立した承認エンベロープとディスパッチ認可が必要である。
  3. **許可リスト（ホワイトリスト）は送信可能な技術的上限を規定するものに過ぎず、送信を認可するライセンスではない**。「許可リスト（ホワイトリスト）に含まれる項目だからポリシー未承認でも送信してよい」、あるいは「APPROVED_ACTIVE なら許可リストの範囲で自動送信してよい」という曲解を厳格に禁止する。

---

## 8. クラス設計と配置計画

すべての型を同一パッケージ `com.ses.privacy.foundation` に集約し、パッケージプライベートな既存暗号・正規化コンポーネントを安全に利用する。

```text
src/main/java/com/ses/privacy/foundation/
├── PolicyReadonlyQueryPort.java       // 照会インターフェース（evaluate(query) のみ）
├── PolicyEvaluationQuery.java         // 照会要求 DTO
├── PolicyExplanationResult.java       // 照会結果 DTO（Explanation-only）
├── PolicyDecisionStatus.java          // 判定ステータス Enum
├── PolicyFailureReason.java           // 型付け失敗原因 Enum
├── TrustedCallerContext.java          // 可信呼び出し元コンテキスト（不可変）
├── TrustedActorRole.java              // 呼び出し元権限ロール Enum
├── SyntheticCallerContextProvider.java// 呼び出し元プロバイダー（package-private）
├── SyntheticPolicyCatalog.java        // 固定カタログインターフェース（package-private）
├── PolicyEnvelopeSource.java          // ポリシーソース取得インターフェース（package-private）
├── PolicyLookupResult.java            // ポリシーソース結果（package-private）
├── PolicySecurityException.java       // 型付け例外（package-private）
├── VerifiedPolicyMetadata.java        // ポリシー検証済み内部レコード（package-private）
├── ApprovalEnvelopeVerifier.java      // 既存検証器（verifyPolicyOnly メソッドを追加）
└── PolicyReadonlyQueryService.java    // 照会サービス実装クラス（package-private）

src/test/java/com/ses/privacy/foundation/
└── PolicyReadonlyQueryServiceTest.java // 単体・負向・正向テスト
```

---

## 9. 負向および正向テスト設計（Test Cases）

本番 DB・実外部通信を使用せず、メモリ内の合成テスト環境（`SyntheticEnvironmentGuard` + 署名 Fixture）で完全に独立検証する。

### 9.1 呼び出し元コンテキスト検証テスト
1. **無権限アクターの拒絶 (`testCallerContext_UnprivilegedUser_ReturnsAccessDenied`)**:
   * プロバイダーから `ROLE_UNPRIVILEGED_USER` を返却するよう構成して照会した場合、`ACCESS_DENIED`（理由: `UNAUTHORIZED_ACTOR_ROLE`）となること。
2. **跨テナント照会の拒絶 (`testCallerContext_CrossTenantMismatch_ReturnsAccessDenied`)**:
   * `caller.tenantId` と `query.tenantId` が不一致の場合、`ACCESS_DENIED`（理由: `CALLER_TENANT_MISMATCH`）となること。
3. **冒名アクター照会の拒絶 (`testCallerContext_ActorMismatch_ReturnsAccessDenied`)**:
   * `caller.actorId` と `query.actorId` が不一致の場合、`ACCESS_DENIED`（理由: `CALLER_ACTOR_MISMATCH`）となること。
4. **身元欠落アクターの拒絶 (`testCallerContext_MissingIdentity_ReturnsAccessDenied`)**:
   * プロバイダーから空コンテキストまたは空白 `actorId` が返却された場合、`ACCESS_DENIED`（理由: `MISSING_CALLER_IDENTITY`）となること。

### 9.2 カタログ・ソース・非干渉テスト
5. **カタログ未登録要素の照会 (`testCatalog_UnregisteredEntry_ReturnsPolicyUnknown`)**:
   * カタログに定義されていない組み合わせを照会した場合、`POLICY_UNKNOWN`（理由: `POLICY_NOT_FOUND`）を返すこと。
6. **未承認ポリシーの照会 (`testSource_UnapprovedPolicy_ReturnsPolicyUnknown`)**:
   * ソースが `LookupStatus.UNAPPROVED` を返却した場合、`POLICY_UNKNOWN`（理由: `POLICY_UNAPPROVED`）を返すこと。
7. **ソースストア障害の処理 (`testSource_StoreUnavailable_ReturnsPolicyUnknown`)**:
   * ソースが `LookupStatus.STORE_UNAVAILABLE` を返却した場合、`POLICY_UNKNOWN`（理由: `STORE_UNAVAILABLE`）を返すこと。
8. **同要素・異操作/異プロバイダー非干渉テスト (`testSameDataElement_DifferentActionAndProvider_IndependentEvaluation`)**:
   * 同一要素 `AI_PROMPT_LOG` に対し、`(DISPOSITION_PURGE, local-db)` と `(EXTERNAL_DISPATCH, gemini-adapter)` の双方が登録されている場合、それぞれを照会した際に意図した個別ポリシーが干渉・上書きなく独立して正しく評価されること。

### 9.3 ポリシー型付け検証テスト
9. **未発行ポリシーの照会 (`testPolicy_NotYetIssued_ReturnsPolicyExpired`)**:
   * `now.isBefore(issuedAt)` の場合、`POLICY_EXPIRED`（理由: `NOT_YET_ISSUED`）を返すこと。
10. **未発効ポリシーの照会 (`testPolicy_NotYetEffective_ReturnsPolicyExpired`)**:
    * `now.isBefore(effectiveAt)` の場合、`POLICY_EXPIRED`（理由: `NOT_YET_EFFECTIVE`）を返すこと。
11. **満了時刻到達ポリシーの照会 (`testPolicy_ExpiredAtDeadline_ReturnsPolicyExpired`)**:
    * `now.equals(expiresAt)`（半開区間の境界）の場合、`POLICY_EXPIRED`（理由: `EXPIRED_AT_OR_AFTER_DEADLINE`）を返すこと。
12. **署名機関の有効期限切れ (`testPolicy_AuthorityExpired_ReturnsPolicyExpired`)**:
    * `now >= authority.validUntil()` の場合、`POLICY_EXPIRED`（理由: `AUTHORITY_VALIDITY_EXPIRED`）を返すこと。
13. **署名機関の管轄範囲不一致 (`testPolicy_AuthorityScopeMismatch_ReturnsPolicyDrift`)**:
    * テナントやスコープが署名機関の管轄外である場合、`POLICY_DRIFT`（理由: `AUTHORITY_SCOPE_MISMATCH`）を返すこと。
14. **失効ポリシーの照会 (`testPolicy_Revoked_ReturnsPolicyRevoked`)**:
    * キーまたはエンベロープが `REVOKED` の場合、`POLICY_REVOKED`（理由: `KEY_REVOKED` または `ENVELOPE_REVOKED`）を返すこと。
15. **失効状態不明ポリシーの照会 (`testPolicy_RevocationStatusUnknown_ReturnsPolicyUnknown`)**:
    * 失効状態が `UNKNOWN` の場合、`POLICY_UNKNOWN`（理由: `REVOCATION_STATUS_UNKNOWN`）を返すこと。
16. **失効プロバイダー例外の照会 (`testPolicy_RevocationProviderError_ReturnsPolicyUnknown`)**:
    * 失効プロバイダー呼び出し時に例外が発生した場合、`POLICY_UNKNOWN`（理由: `REVOCATION_PROVIDER_ERROR`）を返すこと。
17. **署名改竄の検知 (`testPolicy_TamperedSignature_ReturnsPolicyDrift`)**:
    * ペイロードが 1 バイトでも改竄されていた場合、`POLICY_DRIFT`（理由: `SIGNATURE_INVALID`）を返すこと。
18. **職務分離（SoD）違反の拒絶 (`testPolicy_SodViolation_ReturnsPolicyDrift`)**:
    * `requesterId.equals(approverId)` のポリシーは、`POLICY_DRIFT`（理由: `SOD_VIOLATION`）となること。
19. **バインディング不一致の拒絶 (`testPolicy_MismatchedBinding_ReturnsPolicyDrift`)**:
    * 要求されたアクションやプロバイダーがポリシー署名値と異なる場合、`POLICY_DRIFT`（理由: `ACTION_MISMATCH` 等）を返すこと。
20. **本番プロファイルでの実行拒絶 (`testEnvironment_ProdProfile_ThrowsSecurityException`)**:
    * `Environment` に `prod` が含まれる場合、初期化または呼び出しが DTO を返さず未捕捉の `SecurityException` で fail-closed すること。

### 9.4 正向テスト（成功しても実行権限を与えないことの証明）
21. **有効な合成ポリシーの評価 (`testValidSyntheticPolicy_ReturnsApprovedActiveWithoutExecutionCapability`)**:
    * 有効な Ed25519 署名、現在有効期間内、SoD 適合の合成ポリシーを評価した場合：
      * `status` が `APPROVED_ACTIVE`、`failureReason` が `NONE` となること。
      * 返却された DTO に `Permitted` などの権限フラグが一切存在しないこと。
      * いかなる実行 Capability も返却されず、下流の処分や送信操作が本結果のみでは一切行えないこと。

---

## 10. 受入条件（Acceptance Criteria）

1. [ ] `PolicyReadonlyQueryPort.evaluate` が `query` のみを受け取り、アクターロールや `asOf` の呼び出し時差し替えが構造上不可能であること。
2. [ ] サービス内部に `SyntheticCallerContextProvider` がコンストラクタ注入され、身元欠落・跨テナント・冒名・非認可ロール（`ROLE_UNPRIVILEGED_USER`）が `ACCESS_DENIED` として確実に拒絶されること。
3. [ ] 固定 `SyntheticPolicyCatalog` が要素・操作・プロバイダーを特定ポリシーおよび期待スコープへ決定論的にマッピングし、同要素・異操作が独立して評価されること。
4. [ ] `PolicyEnvelopeSource` が不透明 `revision` 文字列を保持し、数値ソートを行わず `currentRevision` / `currentDigest` に基づいて現在版を特定し、不整合時にフォールバックせず fail-closed すること。
5. [ ] `ApprovalEnvelopeVerifier` に `verifyPolicyOnly` が追加され、Case の捏造なしに既存ロジックを再利用して型付け例外 `PolicySecurityException` を送出すること。
6. [ ] 判定理由が文字列照合ではなく型付けされた `PolicyFailureReason` に一意にマッピングされ、半開区間境界（`now == expiresAt`）、`RevocationStatus`（ACTIVE/REVOKED/UNKNOWN）、およびプロバイダー例外が厳密に処理されていること。
7. [ ] プロファイル防護（`SyntheticEnvironmentGuard`）による拒絶が通常 DTO に丸め込まれず、例外として直接中断されること。
8. [ ] 返却 DTO `PolicyExplanationResult` から許可ブール値が完全に削除され、純粋な説明メタデータのみとなっていること。
9. [ ] NF03 の通常業務が不当に停止されないこと、および NF08 において許可リスト（ホワイトリスト）が未承認送信の口実にならないことが明文化されていること。
10. [ ] 新規 DDL、実外部通信、実 IdP 連携、処分 Executor、復旧放行コードが一切含まれていないこと。
