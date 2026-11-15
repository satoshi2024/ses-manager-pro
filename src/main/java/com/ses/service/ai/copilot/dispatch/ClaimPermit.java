package com.ses.service.ai.copilot.dispatch;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * commit済みdurable claimに対する一回限りの送信permit（計画 §1.4）。
 * <p>{@link CopilotDispatchClaimService} が、claim transactionのcommit成功を確認した後にだけ作る。
 * commitの成否が不明・commit例外・応答喪失では作らない。constructorはpackage内専用で、{@code Serializable}・
 * {@code Cloneable}・setter・Jackson creator・public factoryを持たない。DB行・JSON・{@code CopilotInvocation}から
 * 復元できない。B2c-2のpermit専用送信入口だけが受け取る（B2c-1では送信経路へ接続しない）。</p>
 * <p>{@link #tryConsume()} はプロセス内で同じpermitの並行・順次の再利用を拒むだけで、durable claimの代替ではない。
 * 消費後にsocket前の確認が失敗しても未使用へ戻さない（claimは {@code CLAIMED} のまま、意味上UNKNOWN）。</p>
 */
public final class ClaimPermit {

    private final String claimId;
    private final Long runId;
    private final String tenantId;
    private final String traceId;
    private final byte[] bindingHash;
    private final byte[] requestSha256;
    private final byte[] approvalDigest;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final Instant claimedAt;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    ClaimPermit(DispatchTicket ticket, Instant claimedAt) {
        Objects.requireNonNull(ticket, "ticket");
        this.claimId = ticket.claimId();
        this.runId = ticket.runId();
        this.tenantId = ticket.tenantId();
        this.traceId = ticket.traceId();
        this.bindingHash = ticket.bindingHash();
        this.requestSha256 = DispatchDigests.hex(ticket.request().requestSha256());
        this.approvalDigest = DispatchDigests.hex(ticket.request().approvalDigest());
        this.issuedAt = ticket.issuedAt();
        this.expiresAt = ticket.expiresAt();
        this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    }

    public String claimId() { return claimId; }
    /** 費用予約entryの予約ID。claim IDと同じ値（計画 C-3）。 */
    public String reservationId() { return claimId; }
    public Long runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String traceId() { return traceId; }
    public byte[] bindingHash() { return bindingHash.clone(); }
    public byte[] requestSha256() { return requestSha256.clone(); }
    public byte[] approvalDigest() { return approvalDigest.clone(); }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant claimedAt() { return claimedAt; }

    /** 一度だけtrueを返す。二度目以降・並行呼出しの敗者はfalse（未使用へ戻す手段は無い）。 */
    public boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }

    public boolean isConsumed() {
        return consumed.get();
    }

    /** claim IDだけを示す。 */
    @Override
    public String toString() {
        return "ClaimPermit[claimId=" + claimId + "]";
    }
}
