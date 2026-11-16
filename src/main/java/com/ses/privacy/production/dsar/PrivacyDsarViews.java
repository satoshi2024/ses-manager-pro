package com.ses.privacy.production.dsar;

import java.util.List;

/**
 * DSAR 受付〜SCOPED（NF07-A2a）の応答。privacy-admin だけが受け取る。
 *
 * <p>候補の表示は subject 参照・種別・link の状態と、F2 provider が返した行の件数（provider・data element ごと）だけで、
 * 行の値（氏名・連絡先・本文等）は一切含めない。第三者情報が混在し得る element（{@code MIXED_THIRD_PARTY}）も件数だけを出す
 * （交付の可否は A2b の redaction が決める。F2 review の DsarDeliveryClass）。日時は JST の表示文字列で返す。
 */
public final class PrivacyDsarViews {

    private PrivacyDsarViews() {
    }

    /**
     * 本人確認・範囲確定の前提。
     *
     * @param identityReady     D-01・D-06 の決定が有効で、本人確認以降へ進める
     * @param missing           欠けている決定（{@code d01} / {@code d06}）
     * @param dueRuleConfigured D-06 の期限規則（{@code privacy.dsar.due-days}）が設定され、受付で期限日を計算する
     * @param dueDays           期限規則の日数（未設定は null）
     */
    public record Readiness(boolean identityReady, List<String> missing, boolean dueRuleConfigured, Integer dueDays) {
        public Readiness {
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /** 一覧。 */
    public record Overview(Readiness readiness, List<Summary> requests, long total, long page, long size) {
        public Overview {
            requests = requests == null ? List.of() : List.copyOf(requests);
        }
    }

    /** 一覧の1件（subject 参照を含めない）。 */
    public record Summary(long id, String requestNo, String requestType, String status, String channel,
                         String receivedAt, String dueDate, boolean overdue, boolean dueSoon, long rowVersion) {
    }

    /** 詳細。 */
    public record Detail(long id, String requestNo, String requestType, String status, String channel,
                         String receivedAt, String dueDate, boolean overdue, String intakeTicketRef,
                         String identityState, String identityEvidenceRef, String identityRuleRef,
                         String identityVerifiedAt, Long reopenOfRequestId, long rowVersion,
                         List<Candidate> candidates, int selectedCount, ScopeRecord scope, ClosureRecord closure,
                         List<String> nextActions, Readiness readiness) {
        public Detail {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        }
    }

    /**
     * 名寄せの候補。
     *
     * @param footprintAvailable provider の照会に成功したか（失敗した場合は件数を出さない）
     * @param footprint          provider・data element ごとの件数（行の値は含めない）
     */
    public record Candidate(long linkId, String subjectRef, String subjectType, String linkState,
                            String resolutionCode, String resolvedAt, boolean footprintAvailable,
                            List<FootprintEntry> footprint) {
        public Candidate {
            footprint = footprint == null ? List.of() : List.copyOf(footprint);
        }
    }

    /** 候補の記録の所在（件数だけ）。 */
    public record FootprintEntry(String providerId, String dataElementId, int rowCount, String deliveryClass) {
    }

    /** 範囲確定の記録（snapshot の as-of と data element の数。hash は画面にも出さない）。 */
    public record ScopeRecord(String asOf, Integer elementCount) {
    }

    /** 終了の記録。 */
    public record ClosureRecord(String state, String reasonCode, String evidenceRef, String closedAt) {
    }

    /** 状態遷移の結果（画面は詳細を再読み込みする）。 */
    public record Transition(long requestId, String requestNo, String status, long rowVersion) {
    }
}
