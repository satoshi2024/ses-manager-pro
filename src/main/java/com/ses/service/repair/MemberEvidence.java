package com.ses.service.repair;

/**
 * 参加部材参照レコード (SES_NF09_MEMBER)
 */
public record MemberEvidence(
    int v,
    long carrierId,
    String ticket,
    int round
) {
    public MemberEvidence {
        if (v != 1) {
            throw new CorruptedEvidenceException("未対応のプロトコルバージョンです: " + v);
        }
        if (carrierId <= 0) {
            throw new CorruptedEvidenceException("載体キューIDが正の整数ではありません: " + carrierId);
        }
        if (ticket == null || ticket.isBlank()) {
            throw new CorruptedEvidenceException("決裁チケット番号が空です");
        }
        if (round <= 0) {
            throw new CorruptedEvidenceException("決裁輪次が正の整数ではありません: " + round);
        }
    }
}
