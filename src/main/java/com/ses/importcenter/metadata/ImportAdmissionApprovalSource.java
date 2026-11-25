package com.ses.importcenter.metadata;

import com.ses.featureenablement.service.FeatureSignOffRegistrar;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NF-06 取込 admission の承認の値の源（feature-enablement-console design §8.3、requirements §6.10。FE-B02）。
 *
 * <ul>
 *   <li>property の源（{@link Property}）: 従来の {@code app.import-center.admission.*}（{@link ImportAdmissionApprovalProperties}）。
 *       dev・test と、DB 管理の印の無い段階の間。</li>
 *   <li>DB の源（{@link Database}）: 確認済みの署名 {@value #KIND} の構造化された値（{@code decisionId}・{@code tenantId}・
 *       {@code sourceCodes}（カンマ区切り）・{@code approvalDigest}）と署名の行の署名日・失効日から {@link ImportAdmissionApprovalProperties}
 *       を作る（D-5: 決定 ID・digest は配備の設定でなく system の中の署名に置く）。読むのは memory の snapshot だけで DB に往復しない。
 *       署名が無い・構造化された値を読めなければ未設定の値（{@link ImportAdmissionApproval#evaluate} が拒否する。fail-closed）。</li>
 * </ul>
 * {@link ApprovedSourceImportAdmissionPort} は要求ごとに {@link #current()} を読むため、署名の差替えは再起動なしに次の要求から効く。
 * 開放の条件（{@code ImportCenterEnablementConditions}）は評価に使う snapshot から {@link #fromSnapshot} で読む。
 */
public interface ImportAdmissionApprovalSource {

    /** admission の決定の署名の種別（目録の正本は {@code FeatureEnablementCatalog}）。 */
    String KIND = "NF06_ADMISSION";

    /** 今の承認の値（毎回読む）。 */
    ImportAdmissionApprovalProperties current();

    /** 評価に使う snapshot の承認の値（property の源は snapshot を見ない）。 */
    ImportAdmissionApprovalProperties fromSnapshot(FeatureEnablementSnapshot snapshot);

    /** 配備の設定の値を返す源。 */
    final class Property implements ImportAdmissionApprovalSource {

        private final ImportAdmissionApprovalProperties properties;

        public Property(ImportAdmissionApprovalProperties properties) {
            this.properties = properties == null ? new ImportAdmissionApprovalProperties() : properties;
        }

        @Override
        public ImportAdmissionApprovalProperties current() {
            return properties;
        }

        @Override
        public ImportAdmissionApprovalProperties fromSnapshot(FeatureEnablementSnapshot snapshot) {
            return properties;
        }
    }

    /** 確認済みの署名から作る源。 */
    final class Database implements ImportAdmissionApprovalSource {

        private final FeatureEnablementSnapshotHolder holder;

        public Database(FeatureEnablementSnapshotHolder holder) {
            this.holder = Objects.requireNonNull(holder, "holder");
        }

        @Override
        public ImportAdmissionApprovalProperties current() {
            return fromSnapshot(holder.get());
        }

        @Override
        public ImportAdmissionApprovalProperties fromSnapshot(FeatureEnablementSnapshot snapshot) {
            return fromSignOff(snapshot == null ? null : snapshot.signOffs().get(KIND));
        }
    }

    /** 確認済みの署名を承認の値に写す（無い・読めなければ未設定の値）。値は log に出さない。 */
    static ImportAdmissionApprovalProperties fromSignOff(FeatureEnablementSnapshot.ConfirmedSignOff signOff) {
        ImportAdmissionApprovalProperties properties = new ImportAdmissionApprovalProperties();
        if (signOff == null) {
            return properties;
        }
        Map<String, String> values;
        try {
            values = FeatureSignOffRegistrar.structuredMap(signOff.structuredValues());
        } catch (RuntimeException unreadable) {
            return properties;
        }
        properties.setDecisionId(values.get("decisionId"));
        properties.setTenantId(values.get("tenantId"));
        properties.setSignedOn(signOff.signedOn() == null ? null : signOff.signedOn().toString());
        properties.setExpiresOn(signOff.expiresOn() == null ? null : signOff.expiresOn().toString());
        properties.setSourceCodes(sourceCodes(values.get("sourceCodes")));
        properties.setApprovalDigest(values.get("approvalDigest"));
        return properties;
    }

    /** カンマ区切りの source code（空の要素は {@link ImportAdmissionApproval} が無視する）。 */
    static List<String> sourceCodes(String raw) {
        List<String> codes = new ArrayList<>();
        if (raw != null) {
            for (String code : raw.split(",", -1)) {
                codes.add(code.trim());
            }
        }
        return codes;
    }
}
