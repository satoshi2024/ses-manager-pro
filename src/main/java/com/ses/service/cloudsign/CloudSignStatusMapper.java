package com.ses.service.cloudsign;

import org.springframework.stereotype.Component;

/**
 * provider raw numeric status → 業務状態のmapping（HFP-02-AC-05-01）。
 * 0=下書き, 1=先方確認中, 2=締結済, 3=取消・却下, 4=テンプレート(送信対象として拒否),
 * 未知値は安全側の「要確認」。
 */
@Component
public class CloudSignStatusMapper {

    public String businessStatus(Integer providerStatus) {
        if (providerStatus == null) {
            return "要確認";
        }
        return switch (providerStatus) {
            case 0 -> "下書き";
            case 1 -> "先方確認中";
            case 2 -> "締結済";
            case 3 -> "取消・却下";
            case 4 -> "要確認"; // テンプレート: 送信対象の外部IDとして拒否（要確認扱い）
            default -> "要確認";
        };
    }

    /** 送信対象として許容されるstatusか（status=4テンプレートは拒否）。 */
    public boolean isSendableTarget(Integer providerStatus) {
        return providerStatus != null && providerStatus == 0;
    }
}
