package com.ses.service.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Document;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 二者確認（DUAL_CONFIRMATION）の証跡候補が文書として適格かを、操作者に依存せずに判定するreader部品
 * （NF01細粒度epoch計画 §5.1 条件3〜9、§4.3 W3-17）。
 *
 * <p>判定は提出者・確認者の認可（文書ACL、条件11）を含まない。actorごとの文書ACLの合成と、epoch・lockの取得は
 * W3-21aのCONFIRM attemptが行う。本classはattemptの中で、文書・版をS lockした後に呼ばれる前提で、
 * 子行（link・廃棄申請）は通常の読取で読む（文書行のS lock保持中は、文書行を先にX lockする子行writerが動けないため。
 * 計画 §2.5）。tenantは引数で明示し、tenant context・SecurityContextを読まない。</p>
 *
 * <p>判定の順序（最初に満たさない条件の結果を返す）:</p>
 * <ol start="0">
 *   <li>版IDの指定がある（Q-9。版の無い指定は受け付けない）。</li>
 *   <li value="3">文書が指定tenantに存在し、論理削除されていない。</li>
 *   <li>文書種別が {@code SKILL_SHEET}（それ以外は将来の種別も含めてすべて拒否）。</li>
 *   <li>状態が {DRAFT, CONFIRMED, AMENDED} のいずれか。</li>
 *   <li>法的holdが無い。</li>
 *   <li>却下（REJECTED）以外の廃棄申請が無い（PENDING・APPROVED・DISPOSED・FAILEDと未知の状態は不適格）。</li>
 *   <li>有効なlinkをtenantを問わず全件数えてちょうど1件で、そのlinkのtenantが文書と一致し、
 *       {@code ENGINEER} かつ案件の要員を指す（tenantの一致しないlinkが1件でもあれば不適格。F-3修正前のlinkを取りこぼさない）。</li>
 *   <li>指定版が {@code (id, tenant_id, document_id)} で一致し、scan状態が {@code CLEAN}。</li>
 * </ol>
 *
 * <p>条件10（storageのopenとSHA-256照合）は {@link DocumentEvidenceStorageVerifier} がtransactionの外で行う。</p>
 */
@Component
public class DocumentEvidenceEligibility {

    /** 初回sliceが受け付ける唯一の文書種別（Q-2）。 */
    public static final String ACCEPTED_DOCUMENT_TYPE = "SKILL_SHEET";
    /** 初回sliceが受け付ける唯一のlink先種別。 */
    public static final String ACCEPTED_TARGET_TYPE = "ENGINEER";
    /** 証跡として扱える文書状態（SKILL_SHEETは登録時DRAFT）。 */
    public static final Set<String> ELIGIBLE_STATUSES = Set.of("DRAFT", "CONFIRMED", "AMENDED");
    /** これだけが残る廃棄申請は適格を妨げない。 */
    static final String DISPOSAL_REJECTED = "REJECTED";
    static final String SCAN_CLEAN = "CLEAN";

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentDisposalRequestMapper disposalRequestMapper;

    public DocumentEvidenceEligibility(DocumentMapper documentMapper,
                                       DocumentVersionMapper documentVersionMapper,
                                       DocumentLinkMapper documentLinkMapper,
                                       DocumentDisposalRequestMapper disposalRequestMapper) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.disposalRequestMapper = disposalRequestMapper;
    }

    /**
     * 判定の結果。HTTP状態・message key・拒否eventのreason codeは計画 §5.8・§5.9 の対応表に従う
     * （新規keyの4言語の追加はW3-21aで行う）。
     */
    public enum Verdict {
        ELIGIBLE(200, null, null),
        VERSION_REQUIRED(400, "error.lifecycle.evidenceVersionRequired", "EVIDENCE_VERSION_REQUIRED"),
        DOCUMENT_NOT_FOUND(400, "error.document.notFound", "NOT_FOUND_OR_FOREIGN"),
        DOCUMENT_TYPE_UNSUPPORTED(400, "error.lifecycle.evidenceDocumentTypeUnsupported", "EVIDENCE_TYPE_UNSUPPORTED"),
        TARGET_UNSUPPORTED(400, "error.lifecycle.evidenceDocumentTargetUnsupported", "EVIDENCE_TARGET_UNSUPPORTED"),
        DOCUMENT_UNAVAILABLE(409, "error.lifecycle.evidenceDocumentUnavailable", "EVIDENCE_UNAVAILABLE"),
        VERSION_NOT_FOUND(400, "error.document.notFound", "NOT_FOUND_OR_FOREIGN"),
        VERSION_NOT_CLEAN(400, "error.lifecycle.evidenceVersionNotClean", "EVIDENCE_VERSION_NOT_CLEAN");

        private final int httpStatus;
        private final String messageKey;
        private final String reasonCode;

        Verdict(int httpStatus, String messageKey, String reasonCode) {
            this.httpStatus = httpStatus;
            this.messageKey = messageKey;
            this.reasonCode = reasonCode;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String messageKey() {
            return messageKey;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    /** 判定対象。{@code expectedEngineerId} は案件の要員（{@code t_lifecycle_case.engineer_id}）。 */
    public record Candidate(String tenantId, long documentId, Long documentVersionId, long expectedEngineerId) {
        public Candidate {
            Objects.requireNonNull(tenantId, "tenantId");
            if (tenantId.isBlank() || documentId <= 0 || expectedEngineerId <= 0) {
                throw new IllegalArgumentException("証跡候補のtenant・文書ID・要員IDが不正です");
            }
        }
    }

    /**
     * 適格と判定した版の不変事実。storage検証（{@link DocumentEvidenceStorageVerifier}）とsubmissionのsnapshotの入力になる。
     * storage keyは内部利用だけで、APIの応答・拒否eventには出さない（計画 §5.2、§5.8）。
     */
    public record EligibleVersion(String tenantId, long documentId, long documentVersionId, int versionNo,
                                  String storageKey, String sha256, Long sizeBytes) {
    }

    /** 判定結果。適格の場合だけ {@link #version()} を持つ。 */
    public record Result(Verdict verdict, EligibleVersion version) {
        public Result {
            Objects.requireNonNull(verdict, "verdict");
            if ((verdict == Verdict.ELIGIBLE) != (version != null)) {
                throw new IllegalArgumentException("適格の場合だけ版を持つこと");
            }
        }

        public boolean eligible() {
            return verdict == Verdict.ELIGIBLE;
        }

        static Result denied(Verdict verdict) {
            return new Result(verdict, null);
        }
    }

    /** §5.1 条件3〜9を順に評価し、最初に満たさない条件の結果を返す。 */
    public Result evaluate(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.documentVersionId() == null) {
            return Result.denied(Verdict.VERSION_REQUIRED);
        }
        // 条件3: 指定tenantの文書（論理削除済みも読み、不在と削除を分ける）
        Document document = documentMapper.selectEvidenceCandidate(candidate.documentId(), candidate.tenantId());
        if (document == null || !candidate.tenantId().equals(document.getTenantId())) {
            return Result.denied(Verdict.DOCUMENT_NOT_FOUND);
        }
        if (document.getDeletedFlag() == null || document.getDeletedFlag() != 0) {
            return Result.denied(Verdict.DOCUMENT_UNAVAILABLE);
        }
        // 条件4: SKILL_SHEETだけを許可する（許可一覧の判定。未知の種別も拒否）
        if (!ACCEPTED_DOCUMENT_TYPE.equals(document.getDocumentType())) {
            return Result.denied(Verdict.DOCUMENT_TYPE_UNSUPPORTED);
        }
        // 条件5・6: 状態と法的hold
        // Set.ofはnullの照会で例外を投げるため、状態の欠落を先に不適格として扱う
        if (document.getStatus() == null || !ELIGIBLE_STATUSES.contains(document.getStatus())
                || document.getLegalHoldFlag() == null || document.getLegalHoldFlag() != 0) {
            return Result.denied(Verdict.DOCUMENT_UNAVAILABLE);
        }
        // 条件7: 却下以外の廃棄申請が1件でもあれば不適格
        Long blockingDisposals = disposalRequestMapper.selectCount(new LambdaQueryWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getDocumentId, candidate.documentId())
                .and(status -> status.isNull(DocumentDisposalRequest::getStatus)
                        .or().ne(DocumentDisposalRequest::getStatus, DISPOSAL_REJECTED)));
        if (blockingDisposals == null || blockingDisposals != 0) {
            return Result.denied(Verdict.DOCUMENT_UNAVAILABLE);
        }
        // 条件8: 有効なlinkをtenantを問わず全件数える
        if (!singleEngineerLink(documentLinkMapper.findByDocumentId(candidate.documentId()), candidate)) {
            return Result.denied(Verdict.TARGET_UNSUPPORTED);
        }
        // 条件9: 指定版が (id, tenant_id, document_id) で一致し CLEAN
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getId, candidate.documentVersionId())
                .eq(DocumentVersion::getTenantId, candidate.tenantId())
                .eq(DocumentVersion::getDocumentId, candidate.documentId()));
        if (version == null || !candidate.tenantId().equals(version.getTenantId())
                || !Long.valueOf(candidate.documentId()).equals(version.getDocumentId())
                || version.getVersionNo() == null) {
            return Result.denied(Verdict.VERSION_NOT_FOUND);
        }
        if (!SCAN_CLEAN.equals(version.getScanStatus())) {
            return Result.denied(Verdict.VERSION_NOT_CLEAN);
        }
        return new Result(Verdict.ELIGIBLE, new EligibleVersion(candidate.tenantId(), candidate.documentId(),
                version.getId(), version.getVersionNo(), version.getStorageKey(), version.getSha256(),
                version.getSizeBytes()));
    }

    /**
     * 提出者に対して文書の存在を示してよいか（W3-21a review P3-5、計画 §5.9「存在を推測させない」）。文書が指定tenantに
     * 存在し（論理削除済みを含む）、有効なlinkのうち同じtenantで案件の要員（{@code ENGINEER}）を指すものが1件以上ある
     * 場合だけ true を返す。不在・他tenant・link無し・他の要員だけを指す文書は false で、呼出側は適格性の詳細を返さずに
     * 存在しない文書と同じ応答にする。要員を指すlinkに加えて別のlinkがある等のlink形の不適合は {@link #evaluate} の
     * 条件8が判定する。actorの文書ACL（条件11）は含まない（呼出側が先に判定する）。
     */
    public boolean linkedToEngineer(String tenantId, long documentId, long engineerId) {
        if (tenantId == null || tenantId.isBlank() || documentId <= 0 || engineerId <= 0) {
            return false;
        }
        Document document = documentMapper.selectEvidenceCandidate(documentId, tenantId);
        if (document == null || !tenantId.equals(document.getTenantId())) {
            return false;
        }
        List<DocumentLink> links = documentLinkMapper.findByDocumentId(documentId);
        if (links == null) {
            return false;
        }
        return links.stream().anyMatch(link -> link != null && tenantId.equals(link.getTenantId())
                && ACCEPTED_TARGET_TYPE.equals(link.getTargetType())
                && Long.valueOf(engineerId).equals(link.getTargetId()));
    }

    private static boolean singleEngineerLink(List<DocumentLink> links, Candidate candidate) {
        if (links == null || links.size() != 1) {
            return false;
        }
        DocumentLink link = links.get(0);
        return candidate.tenantId().equals(link.getTenantId())
                && ACCEPTED_TARGET_TYPE.equals(link.getTargetType())
                && Long.valueOf(candidate.expectedEngineerId()).equals(link.getTargetId());
    }
}
