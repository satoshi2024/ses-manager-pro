package com.ses.service;

import com.ses.dto.bpmigration.BpMigrationExceptionDto;

import java.util.List;

public interface BpMigrationService {

    /**
     * 既存の自由入力データ (BpAvailability, BpPayment) をスキャンし、
     * 仮BPの生成・ID紐付け・スナップショット保存を行う。
     */
    void migrateLegacyBpData();

    /**
     * 正規化後の文字列を生成する。
     */
    String normalizeName(String rawName);

    /**
     * 未解決・例外移行行の一覧を取得する。
     */
    List<BpMigrationExceptionDto> getMigrationExceptions();

    /**
     * 手動で旧自由入力行を特定BP会社IDへ紐付け解決する。
     */
    void resolveMigrationException(String sourceType, Long sourceId, Long bpCompanyId);
}
