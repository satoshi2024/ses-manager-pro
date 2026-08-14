package com.ses.service.cloudsign;

import com.ses.common.enums.CloudSignErrorCode;
import lombok.Getter;

/**
 * CloudSign API呼出しのtyped例外。
 *
 * <p>{@code uncertain=true} は「providerがmutationを処理した可能性がある」ことを示す
 * （timeout/504/connection reset）。この場合、同じmutationを自動再実行してはならない
 * （HFP-02-AC-04-03）。token/client ID/raw body/PIIはmessageに含めない。
 */
@Getter
public class CloudSignApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CloudSignErrorCode code;
    /** mutationの結果不明（providerが処理済みの可能性）。 */
    private final boolean uncertain;

    public CloudSignApiException(CloudSignErrorCode code, boolean uncertain, String safeMessage) {
        super(safeMessage == null ? code.name() : safeMessage);
        this.code = code;
        this.uncertain = uncertain;
    }

    public CloudSignApiException(CloudSignErrorCode code, String safeMessage) {
        this(code, false, safeMessage);
    }
}
