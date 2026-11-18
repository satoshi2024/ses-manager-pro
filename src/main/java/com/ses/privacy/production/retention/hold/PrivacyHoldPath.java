package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.provider.AiLogProvider;
import com.ses.privacy.production.provider.DocumentFileProvider;
import com.ses.privacy.production.provider.IngestionTextProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * NF07 hold の gate に接続した破壊的経路（production-bridge-phased-plan.md §3.4.1 の D1〜D10。A1H-2）。
 *
 * <p>経路ごとに、接続点の source（{@code src/main/java} からの相対 path）と、その経路が判定する data element を宣言する。
 * {@code PrivacyHoldPathClassificationTest} がこの宣言から逆に、各接続点が実際に gate を呼ぶこと（source にこの定数がある
 * こと）と、宣言した data element が catalog の seed（V196・V197）で UNKNOWN でないこと（C-2-2）を固定する。
 * D8（nonce）は個人情報を含まないため接続しない（宣言も持たない）。D10 は hold の対象そのものの保存名を守る provider で、
 * 固定の data element を持たない。
 */
public enum PrivacyHoldPath {

    /** 文書の廃棄執行（{@code DocumentServiceImpl#executeDisposal}。手動、同じ tx で 409）。 */
    D1("com/ses/service/impl/DocumentServiceImpl.java", documentElements()),
    /** 文書の廃棄申請（{@code DocumentServiceImpl#requestDisposal}。手動、同じ tx で 409）。 */
    D2("com/ses/service/impl/DocumentServiceImpl.java", documentElements()),
    /** 保存期限文書の廃棄候補の抽出（{@code DocumentRetentionJob}。tenant ごとに1回 snapshot）。 */
    D3("com/ses/service/job/DocumentRetentionJob.java", documentElements()),
    /** 履歴取込の原本・抽出本文の purge（{@code ResumeRetentionCleanupServiceImpl}）。 */
    D4("com/ses/service/impl/ResumeRetentionCleanupServiceImpl.java",
            List.of(IngestionTextProvider.RESUME_ORIGINAL, IngestionTextProvider.RESUME_TEXT)),
    /** BP 空き取込の抽出本文の消去（{@code ResumeRetentionCleanupServiceImpl}。D4 と同じ snapshot）。 */
    D5("com/ses/service/impl/ResumeRetentionCleanupServiceImpl.java", List.of(IngestionTextProvider.BP_TEXT)),
    /** AI 推薦の redacted summary の purge（{@code AiRecommendationRetentionServiceImpl}。scheduler の Bean に依存しない）。 */
    D6("com/ses/service/ai/impl/AiRecommendationRetentionServiceImpl.java", List.of(AiLogProvider.REDACTED_SUMMARY)),
    /** integration hub の record purge（{@code IntegrationHubRetentionPurgeScheduler}。kind 単位で止める）。 */
    D7("com/ses/service/integrationhub/IntegrationHubRetentionPurgeScheduler.java", integrationHubElements()),
    /** 外部 API の read snapshot の purge（{@code ExternalApiReadSnapshotPurgeService}）。 */
    D9("com/ses/service/integrationhub/ExternalApiReadSnapshotPurgeService.java", List.of("EXTERNAL_API_READ_SNAPSHOT")),
    /** 孤児 file の清掃（{@code FileCleanupService}。{@link PrivacyHoldFileReferenceProvider} が hold の対象の保存名を守る）。 */
    D10("com/ses/privacy/production/retention/hold/PrivacyHoldFileReferenceProvider.java", List.of());

    /** D7 の record kind（{@code IntegrationHubRetentionPurgeScheduler} の走査対象と同じ）。 */
    public static final List<String> INTEGRATION_HUB_KINDS = IntegrationHub.KINDS;
    /** D7 の data element の接頭辞（catalog V196 の {@code INTEGRATION_HUB_<KIND>}）。 */
    public static final String INTEGRATION_HUB_PREFIX = IntegrationHub.PREFIX;

    /** enum 定数の初期化より前に使うため、定数を別の class に置く（enum の static 項目は定数の後に初期化される）。 */
    private static final class IntegrationHub {
        static final List<String> KINDS = List.of(
                "IDEMPOTENCY", "DELIVERY", "INBOUND", "INBOUND_REPLAY", "AUDIT", "EXTERNAL_AUDIT");
        static final String PREFIX = "INTEGRATION_HUB_";
    }

    private final String connectionPoint;
    private final List<String> dataElements;

    PrivacyHoldPath(String connectionPoint, List<String> dataElements) {
        this.connectionPoint = connectionPoint;
        this.dataElements = List.copyOf(dataElements);
    }

    /** 接続点の source（{@code src/main/java} からの相対 path）。 */
    public String connectionPoint() {
        return connectionPoint;
    }

    /** 経路が判定する data element（文書の経路は全ての文書種別）。 */
    public List<String> dataElements() {
        return dataElements;
    }

    /** 文書種別の data element（{@code DOCUMENT:<document_type>}）。 */
    public static String documentElement(String documentType) {
        return documentType == null || documentType.isBlank() ? DocumentFileProvider.UNRESOLVED_ELEMENT
                : DocumentFileProvider.ELEMENT_PREFIX + documentType;
    }

    /** D7 の kind の data element。 */
    public static String integrationHubElement(String recordKind) {
        return IntegrationHub.PREFIX + recordKind;
    }

    private static List<String> documentElements() {
        List<String> elements = new ArrayList<>();
        for (String type : new TreeSet<>(DocumentFileProvider.DOCUMENT_TYPE_RULES.keySet())) {
            elements.add(DocumentFileProvider.ELEMENT_PREFIX + type);
        }
        return elements;
    }

    private static List<String> integrationHubElements() {
        List<String> elements = new ArrayList<>();
        for (String kind : IntegrationHub.KINDS) {
            elements.add(IntegrationHub.PREFIX + kind);
        }
        return elements;
    }
}
