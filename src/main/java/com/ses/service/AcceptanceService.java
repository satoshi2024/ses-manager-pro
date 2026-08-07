package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.acceptance.AcceptanceGridDto;
import com.ses.entity.Acceptance;

/**
 * 月次検収サービス（order-acceptance-workflow / B1）。
 * submit/accept/reject/cancelとwork recordのsnapshotを担当する。
 */
public interface AcceptanceService extends IService<Acceptance> {

    /** 検収グリッド（scope適用）。work record確定・検収要契約を出発点に、未提出行も含む。 */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<AcceptanceGridDto> pageGrid(
            long current, long size, String workMonth, String status, Long customerId, Long engineerId, Long acceptanceId);

    /**
     * 提出（未提出→提出済 / 差戻し→再提出）。提出時点のwork record工数・金額・更新日時をsnapshotする。
     * 契約×月はUNIQUEで1件。
     */
    Acceptance submit(Long contractId, String workMonth);

    /** 検収（提出済→検収済）。顧客確認者を記録。状態CAS＋version。 */
    Acceptance accept(Long acceptanceId, Long customerContactId);

    /** 差戻し（提出済→差戻し）。理由必須。 */
    Acceptance reject(Long acceptanceId, String comment);

    /** 再提出（差戻し→提出済）。提出時点のsnapshotを取り直す。 */
    Acceptance resubmit(Long acceptanceId);

    /** 検収取消の承認適用（検収済→差戻し）。R3.4: 検収取消は承認必須。 */
    void applyCancellation(Long acceptanceId);

    /**
     * 検収書原本（ACCEPTANCE）を文書台帳へ登録し、acceptance.document_id に設定する（R3.1）。
     * 冪等: 同一acceptanceからは1文書。scopeは検収一覧と同じ契約DataScope。
     */
    com.ses.entity.Acceptance uploadDocument(Long acceptanceId, org.springframework.web.multipart.MultipartFile file);

    /** 検収書原本をscopeチェック付きで開く（download）。 */
    java.io.InputStream downloadDocument(Long acceptanceId);

    /** 検収が現在のscopeで参照可能か検証する（404秘匿）。 */
    void assertAllowedAcceptance(Long acceptanceId);
}
