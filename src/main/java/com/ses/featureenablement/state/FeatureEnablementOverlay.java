package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * この instance の memory の一時の閉鎖（design §3.5 の review P2-2）。開放単位ごとに理由の集合を持ち、1つでもあれば実効値を閉じる。
 *
 * <ul>
 *   <li>{@link #EVALUATION_UNVERIFIABLE}: {@code unverifiable_since} を DB に書けない間の評価できない状態（評価が戻れば外す）。</li>
 *   <li>{@link #AUTO_CLOSE_PENDING}: 自動閉鎖の書込み待ち（sticky。条件の成否に関係なく、DB に {@code AUTO_CLOSED} を書けるまで外さない。
 *       再起動で失われるため、書込み待ちの間は毎周期 WARN を出す。review P3-N1）。</li>
 *   <li>{@link #REFRESH_PENDING}: commit の後の snapshot の読直しに失敗した開放単位（次に読めれば外す。design §3.3）。</li>
 * </ul>
 */
@Component
public class FeatureEnablementOverlay {

    public static final String EVALUATION_UNVERIFIABLE = "EVALUATION_UNVERIFIABLE";
    public static final String AUTO_CLOSE_PENDING = "AUTO_CLOSE_PENDING";
    public static final String REFRESH_PENDING = "REFRESH_PENDING";

    private final Map<Feature, Set<String>> reasons = new ConcurrentHashMap<>();
    /** 自動閉鎖の書込み待ちの理由の code（自動閉鎖の {@code closed_reason} に書く）。 */
    private final Map<Feature, String> pendingAutoCloseReason = new ConcurrentHashMap<>();

    /** 一時の閉鎖があるか（hot path。lock を取らない）。 */
    public boolean contains(Feature feature) {
        Set<String> set = reasons.get(feature);
        return set != null && !set.isEmpty();
    }

    /** 理由を足して閉じる。 */
    public void close(Feature feature, String reason) {
        reasons.computeIfAbsent(feature, key -> ConcurrentHashMap.newKeySet()).add(reason);
    }

    /** 理由を外す（他の理由が残れば閉じたまま）。 */
    public void open(Feature feature, String reason) {
        Set<String> set = reasons.get(feature);
        if (set != null) {
            set.remove(reason);
        }
        if (AUTO_CLOSE_PENDING.equals(reason)) {
            pendingAutoCloseReason.remove(feature);
        }
    }

    /** 理由があるか。 */
    public boolean has(Feature feature, String reason) {
        Set<String> set = reasons.get(feature);
        return set != null && set.contains(reason);
    }

    /** 自動閉鎖の書込み待ちにする（理由の code を覚える。既に待ちなら最初の理由のまま）。 */
    public void markAutoClosePending(Feature feature, String closedReason) {
        pendingAutoCloseReason.putIfAbsent(feature, closedReason);
        close(feature, AUTO_CLOSE_PENDING);
    }

    /** 自動閉鎖の書込み待ちの理由の code。 */
    public Optional<String> pendingAutoCloseReason(Feature feature) {
        return Optional.ofNullable(pendingAutoCloseReason.get(feature));
    }

    /** 理由の一覧（昇順。画面の実効値の表示に使う）。 */
    public Set<String> reasons(Feature feature) {
        Set<String> set = reasons.get(feature);
        return set == null ? Set.of() : Set.copyOf(new TreeSet<>(set));
    }

    /** 全ての開放単位の理由（metric・試験用の写し）。 */
    public Map<Feature, Set<String>> all() {
        Map<Feature, Set<String>> copy = new EnumMap<>(Feature.class);
        reasons.forEach((feature, set) -> {
            if (!set.isEmpty()) {
                copy.put(feature, Set.copyOf(set));
            }
        });
        return copy;
    }

    /** 全ての開放単位から理由を外す（{@link #AUTO_CLOSE_PENDING} は外さない）。 */
    public void clearAll(String reason) {
        if (AUTO_CLOSE_PENDING.equals(reason)) {
            throw new IllegalArgumentException("自動閉鎖の書込み待ちは一括で外さない");
        }
        reasons.values().forEach(set -> set.remove(reason));
    }
}
