package com.ses.importcenter.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NF-06本番admissionの承認設定（{@code app.import-center.admission.*}、Q-5）。
 *
 * <p>値は署名済みData Owner決定書の転記で、秘密値を置かない。既定は全て未設定であり、その場合は全sourceの取込を拒否する。
 * 評価は{@link ImportAdmissionApproval}が行う（I/O・状態を持たない）。値は起動時に一度だけbindされ、実行中に変わらない。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.import-center.admission")
public class ImportAdmissionApprovalProperties {
    /** 署名済みData Owner決定ID（例: {@code DO-NF06-2026-001}）。 */
    private String decisionId;
    /** 承認対象のtenant ID。現在の認可contextのtenantと完全一致しなければ拒否する。 */
    private String tenantId;
    /** 署名日（ISO-8601、JST業務日）。 */
    private String signedOn;
    /** 失効日（ISO-8601、JST業務日。この日以降は拒否）。 */
    private String expiresOn;
    /** 承認済みsource code。比較は小文字化した値で行う。 */
    private List<String> sourceCodes = new ArrayList<>();
    /** 決定書に記載された承認内容のSHA-256（小文字hex 64桁）。 */
    private String approvalDigest;
}
