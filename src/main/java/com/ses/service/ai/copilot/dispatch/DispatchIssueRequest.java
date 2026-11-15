package com.ses.service.ai.copilot.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * summary dispatch attemptをREADYにするときの入力（呼出側が持つimmutable snapshotの値）。
 * <p>これ自体はauthorityではない。READY化で発行時bindingとしてDB行と {@link DispatchTicket} に固定され、claimでは
 * DB行の各列と照合される。public recordなので値は誰でも作れるが、ここからticket・permitは作れない。
 * prompt原文・request body・API key・PIIを持つfieldを置かない（hashと識別子だけ）。</p>
 *
 * @param provider              approved provider（artifactのproviderと一致すること）
 * @param modelRevision         承認済みmodel revision
 * @param region                送信先region
 * @param tier                  service tier
 * @param configHash            artifactのconfig hash（小文字64桁）
 * @param artifactVersionId     artifact version（attemptのartifactと一致すること）
 * @param scopeHash             認可scopeのhash
 * @param inputHash             送信入力（mask済みfield等）のcanonical hash
 * @param preflightSnapshotHash preflight snapshotのhash
 * @param requestSha256         送信するrequest bytesのSHA-256
 * @param approvalDigest        W3-03の承認内容digest
 * @param decisionId            承認の決定ID
 * @param cardId                署名済みrate cardのID
 * @param cardVersion           rate cardのversion
 * @param cardDigest            rate cardのdigest
 * @param cardEffectiveFrom     rate cardの有効開始（UTC、含む）
 * @param cardEffectiveUntil    rate cardの有効終了（UTC、含まない）
 * @param policyId              usage policyのID
 * @param policyVersion         usage policyのversion
 * @param policyDigest          usage policyのdigest
 * @param destinationHost       正規化済みの宛先host（監査とbinding digestに使う）
 * @param queryId               catalog query ID（監査用）
 * @param legalEntityId         法人（監査用。nullable）
 * @param promptBytes           送信byte数（監査用）
 * @param costScope             費用上限の適用範囲（provider単位）
 * @param upperBoundYen         費用予約見積り（1円以上）
 * @param dailyLimitYen         承認済み日次上限
 * @param monthlyLimitYen       承認済み月次上限
 * @param attemptTtl            attemptの有効時間（0より大きく10分以下）
 * @param deadlineNanos         monotonic deadline（{@code System.nanoTime()} の値域）
 */
public record DispatchIssueRequest(
        String provider, String modelRevision, String region, String tier, String configHash,
        Long artifactVersionId, String scopeHash, String inputHash, String preflightSnapshotHash,
        String requestSha256, String approvalDigest, String decisionId,
        String cardId, String cardVersion, String cardDigest, Instant cardEffectiveFrom, Instant cardEffectiveUntil,
        String policyId, String policyVersion, String policyDigest,
        String destinationHost, String queryId, Long legalEntityId, int promptBytes,
        String costScope, int upperBoundYen, long dailyLimitYen, long monthlyLimitYen,
        Duration attemptTtl, long deadlineNanos) {

    static final Duration MAX_ATTEMPT_TTL = Duration.ofMinutes(10);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._:/-]+");
    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9._-]{1,64}");

    public DispatchIssueRequest {
        identifier(provider, 32, "provider");
        identifier(modelRevision, 64, "modelRevision");
        identifier(region, 32, "region");
        identifier(tier, 32, "tier");
        DispatchDigests.hex(configHash);
        if (artifactVersionId == null || artifactVersionId <= 0) {
            throw new IllegalArgumentException("artifactVersionIdが不正です");
        }
        DispatchDigests.hex(scopeHash);
        DispatchDigests.hex(inputHash);
        DispatchDigests.hex(preflightSnapshotHash);
        DispatchDigests.hex(requestSha256);
        DispatchDigests.hex(approvalDigest);
        identifier(decisionId, 64, "decisionId");
        identifier(cardId, 64, "cardId");
        identifier(cardVersion, 32, "cardVersion");
        DispatchDigests.hex(cardDigest);
        Objects.requireNonNull(cardEffectiveFrom, "cardEffectiveFrom");
        Objects.requireNonNull(cardEffectiveUntil, "cardEffectiveUntil");
        if (!cardEffectiveFrom.isBefore(cardEffectiveUntil)) {
            throw new IllegalArgumentException("rate cardの有効期間が不正です");
        }
        cardEffectiveFrom = DispatchDigests.micros(cardEffectiveFrom);
        cardEffectiveUntil = DispatchDigests.micros(cardEffectiveUntil);
        identifier(policyId, 64, "policyId");
        identifier(policyVersion, 32, "policyVersion");
        DispatchDigests.hex(policyDigest);
        if (destinationHost == null || !HOST.matcher(destinationHost).matches()) {
            throw new IllegalArgumentException("destinationHostが不正です");
        }
        identifier(queryId, 64, "queryId");
        if (legalEntityId != null && legalEntityId <= 0) {
            throw new IllegalArgumentException("legalEntityIdが不正です");
        }
        if (promptBytes < 0) {
            throw new IllegalArgumentException("promptBytesが不正です");
        }
        if (costScope == null || !SCOPE.matcher(costScope).matches()) {
            throw new IllegalArgumentException("costScopeが不正です");
        }
        if (upperBoundYen < 1) {
            throw new IllegalArgumentException("費用予約見積りは1円以上である必要があります");
        }
        if (dailyLimitYen < 0 || monthlyLimitYen < 0) {
            throw new IllegalArgumentException("費用上限が不正です");
        }
        if (attemptTtl == null || attemptTtl.isNegative() || attemptTtl.isZero()
                || attemptTtl.compareTo(MAX_ATTEMPT_TTL) > 0) {
            throw new IllegalArgumentException("attemptの有効時間が不正です");
        }
    }

    private static void identifier(String value, int maxLength, String label) {
        if (value == null || value.length() > maxLength || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "が不正です");
        }
    }
}
