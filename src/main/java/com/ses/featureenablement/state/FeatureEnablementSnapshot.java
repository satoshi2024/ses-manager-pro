package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 開放状態・設定・確認済みの署名・確認済みの組を memory に読み込んだもの（design §3.2）。filter の hot path はこれだけを読む。
 *
 * @param generation    世代（世代の行が無ければ 0）
 * @param loadedAt      DB から読み込んだ時刻
 * @param verifiedAt    最後に DB を読めた時刻（世代が変わらず読み直さなかった poll でも更新する。古さの判定に使う）
 * @param masterAllowed 総スイッチ（{@code FEATURE_ENABLEMENT_ALLOWED}）が許可か
 * @param units         開放単位ごとの開放状態と {@code openUntil}（行の無い開放単位は含めない）
 * @param settings      承認で変える設定（値のある key だけ。未設定は含めない）
 * @param signOffs      確認済みの署名（種別 → 内容）
 * @param bundles       確認済みの組（種類 → 全ての member の参照・日付・値と各 member の状態）
 */
public record FeatureEnablementSnapshot(long generation, Instant loadedAt, Instant verifiedAt, boolean masterAllowed,
                                        Map<Feature, UnitView> units, Map<String, String> settings,
                                        Map<String, ConfirmedSignOff> signOffs, Map<String, BundleView> bundles) {

    public FeatureEnablementSnapshot {
        units = units.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(units));
        settings = Map.copyOf(settings);
        signOffs = Map.copyOf(signOffs);
        bundles = Map.copyOf(bundles);
    }

    /** 何も読めていない snapshot（全て閉。最初の読込みに失敗した時）。 */
    public static FeatureEnablementSnapshot empty(Instant now, boolean masterAllowed) {
        return new FeatureEnablementSnapshot(-1L, now, Instant.EPOCH, masterAllowed, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** 開放単位の状態（行が無ければ CLOSED）。 */
    public UnitView unit(Feature feature) {
        UnitView view = units.get(feature);
        return view == null ? UnitView.closed() : view;
    }

    /** 設定の値（未設定は空）。 */
    public Optional<String> setting(String key) {
        return Optional.ofNullable(settings.get(key));
    }

    /** 最後に読めた時刻を更新した写し（世代が変わらなかった poll）。 */
    public FeatureEnablementSnapshot verifiedAt(Instant now) {
        return new FeatureEnablementSnapshot(generation, loadedAt, now, masterAllowed, units, settings, signOffs, bundles);
    }

    /**
     * 開放単位の開放状態の見え方。
     *
     * @param state             開放状態
     * @param version           行の版（行が無ければ -1）
     * @param openUntil         要る署名の最も早い失効日（排他。要る署名が1つでも無ければ {@link LocalDate#MIN}）
     * @param unverifiableSince 評価できない状態の始まり（非 NULL なら閉）
     * @param closedReason      閉鎖の理由の code
     */
    public record UnitView(EnablementState state, int version, LocalDate openUntil, Instant unverifiableSince,
                           String closedReason) {

        static UnitView closed() {
            return new UnitView(EnablementState.CLOSED, -1, LocalDate.MIN, null, null);
        }

        /** 再起動待ち（NF05）。 */
        public boolean restartPending() {
            return state == EnablementState.OPEN_PENDING_RESTART;
        }
    }

    /** 確認済みの署名（値は秘密を含まない。氏名は持たない）。 */
    public record ConfirmedSignOff(long id, int version, String kindCode, String reference, String signerRole,
                                   LocalDate signedOn, LocalDate expiresOn, String structuredValues, String kindClass) {
    }

    /** 確認済みの組（member の状態に依らず全ての member を持つ。design §2.6.1 の P2-N1）。 */
    public record BundleView(long id, int version, String bundleKind, Map<String, BundleMemberView> members) {

        public BundleView {
            members = Map.copyOf(members);
        }
    }

    /** 組の member（署名の行の状態を含む。PURGED の後も参照と日付は残る）。 */
    public record BundleMemberView(long signOffId, String kindCode, String reference, LocalDate signedOn,
                                   LocalDate expiresOn, String structuredValues, String status) {
    }
}
