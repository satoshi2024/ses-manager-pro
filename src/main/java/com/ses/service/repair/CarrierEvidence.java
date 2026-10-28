package com.ses.service.repair;

/**
 * 代表載体完了証跡レコード (SES_NF09_COMPL)
 */
public record CarrierEvidence(
    int v,
    String ticket,
    int round,
    long invId,
    String tenant,
    long legalEntity,
    String pCls,
    long vBf,
    long vAf,
    int qc,
    String qh,
    long by,
    String at
) {
    public CarrierEvidence {
        if (v != 1) {
            throw new CorruptedEvidenceException("未対応のプロトコルバージョンです: " + v);
        }
        if (ticket == null || ticket.isBlank()) {
            throw new CorruptedEvidenceException("決裁チケット番号が空です");
        }
        if (round <= 0) {
            throw new CorruptedEvidenceException("決裁輪次が正の整数ではありません: " + round);
        }
        if (invId <= 0) {
            throw new CorruptedEvidenceException("インボイスIDが正の整数ではありません: " + invId);
        }
        if (tenant == null || tenant.isBlank()) {
            throw new CorruptedEvidenceException("テナントIDが空です");
        }
        if (legalEntity <= 0) {
            throw new CorruptedEvidenceException("法人IDが正の整数ではありません: " + legalEntity);
        }
        if (pCls == null || (!"P1".equals(pCls) && !"P2".equals(pCls))) {
            throw new CorruptedEvidenceException("不正な修復分類です: " + pCls);
        }
        if (vBf < 0) {
            throw new CorruptedEvidenceException("修復前バージョンが負数です: " + vBf);
        }
        if (vAf < 0) {
            throw new CorruptedEvidenceException("修復後バージョンが負数です: " + vAf);
        }
        if (qc <= 0) {
            throw new CorruptedEvidenceException("参加キュー件数が正の整数ではありません: " + qc);
        }
        if (qh == null || !qh.matches("^[0-9a-f]{64}$")) {
            throw new CorruptedEvidenceException("参加キューハッシュが厳密な64文字小文字16進数ではありません: " + qh);
        }
        if (by <= 0) {
            throw new CorruptedEvidenceException("実行操作者IDが正の整数ではありません: " + by);
        }
        if (at == null || at.isBlank()) {
            throw new CorruptedEvidenceException("結案日時が空です");
        }
    }
}
