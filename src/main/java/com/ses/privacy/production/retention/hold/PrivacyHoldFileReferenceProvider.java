package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.service.FileReferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * D10: 孤児 file の清掃（{@code FileCleanupService.cleanupOrphanFiles}。{@code FileCleanupScheduler} と
 * {@code DocumentArchiveFileCleanupScheduler} の両方が呼ぶ）から、有効な NF07 hold の対象の実体を守る
 * （production-bridge-phased-plan.md §3.4.1 D10。A1H-2）。scheduler ではなく provider の側で守るため、両 scheduler が同じ扱いになる。
 *
 * <p>既存の provider は論理削除済みの行や一部の状態の行（却下した履歴取込等）を参照に数えないため、held な本人の実体でも
 * 孤児として消え得る（D11）。本 provider は次の保存名を「参照あり」として足す（参照を減らすことはしない）。
 * <ul>
 *   <li>SUBJECT hold: 本人に帰属する保存名（F2 provider が論理削除済みの行を含めて解決。{@link HoldSubjectResolver#resolveStoredNames}）</li>
 *   <li>DATA_ELEMENT hold: その element の保存名（{@link HoldSubjectResolver#resolveElementStoredNames}）</li>
 * </ul>
 * 清掃は保存先の directory の file 名だけで照合するため、保存名に区切り（{@code /}・{@code \}）があれば末尾の名前も足す
 * （{@code DocumentArchiveFileReferenceProvider} と同じ）。
 *
 * <p>hold 機構を使った tenant の gate が UNAVAILABLE、または保存名を解決できない場合は例外を投げ、清掃のその回を中止させる
 * （{@code FileCleanupServiceImpl} は参照の集約を削除の前に行い、provider の例外をそのまま返すため、その回は1件も消さない）。
 * hold 機構を一度も使っていない環境では、照会は gate の1回（使った tenant の列挙）だけで、空を返す。
 * 清掃は tenant ごとに provider を呼ぶが、本 provider は呼出しの tenant に依らず使った全 tenant を判定する（清掃の tenant
 * 一覧に無い tenant の実体も守る。同じ upload root を共有するため）。
 */
public class PrivacyHoldFileReferenceProvider implements FileReferenceProvider {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldFileReferenceProvider.class);

    /** 清掃の中止の固定 log code。 */
    public static final String LOG_CODE_ABORT = "NF07_PRIVACY_HOLD_FILE_CLEANUP_ABORTED";

    private final PrivacyHoldGate gate;
    private final HoldSubjectResolver resolver;

    public PrivacyHoldFileReferenceProvider(PrivacyHoldGate gate, HoldSubjectResolver resolver) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Set<String> referencedFileNames() {
        Set<String> names = new LinkedHashSet<>();
        for (PrivacyHoldGate.TenantDecision entry : gate.evaluateEngagedTenants()) {
            PrivacyHoldGate.Decision decision = entry.decision();
            if (decision.state() == PrivacyHoldGate.State.NO_HOLDS) {
                continue;
            }
            if (decision.state() != PrivacyHoldGate.State.VERIFIED || entry.tenantId() == null) {
                throw abort("gate");
            }
            try {
                for (String held : decision.heldSubjects()) {
                    PrivacySubjectRef subject = PrivacySubjectRef.parse(held)
                            .orElseThrow(() -> new IllegalStateException("hold の subject 参照を解釈できません"));
                    addAll(names, resolver.resolveStoredNames(entry.tenantId(), subject));
                }
                for (String element : decision.heldDataElements()) {
                    addAll(names, resolver.resolveElementStoredNames(entry.tenantId(), element));
                }
            } catch (RuntimeException e) {
                log.warn("[privacy-hold] holdの対象の保存名を解決できません: code={} exceptionClass={}", LOG_CODE_ABORT,
                        e.getClass().getSimpleName());
                throw abort("resolve");
            }
        }
        return Collections.unmodifiableSet(names);
    }

    private static void addAll(Set<String> names, Set<String> storedNames) {
        for (String name : storedNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            names.add(trimmed);
            int separator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            if (separator >= 0 && separator < trimmed.length() - 1) {
                names.add(trimmed.substring(separator + 1));
            }
        }
    }

    private static IllegalStateException abort(String stage) {
        log.warn("[privacy-hold] holdの対象を判定できないため孤児ファイルの清掃をこの回は中止します: code={} stage={}",
                LOG_CODE_ABORT, stage);
        return new PrivacyHoldCleanupAbortedException();
    }

    /** 孤児清掃をその回だけ中止させる例外（message は固定。tenant・subject を含めない）。 */
    public static final class PrivacyHoldCleanupAbortedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        PrivacyHoldCleanupAbortedException() {
            super("NF07 hold の対象を判定できないため孤児ファイルの清掃を中止します");
        }
    }
}
