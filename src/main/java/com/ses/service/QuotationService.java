package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Contract;
import com.ses.entity.Quotation;

import java.time.LocalDate;

/**
 * 見積サービス。
 */
public interface QuotationService extends IService<Quotation> {

    /** 見積番号採番（Q-YYYYMM-NNNN）。 */
    String generateQuotationNo(LocalDate baseDate);

    /** 採番リトライ＋検証つき保存（R1）。 */
    void saveWithBusinessRules(Quotation q);

    /** 受注/失注後は編集拒否（備考のみ許可）つき更新。 */
    void updateWithBusinessRules(Quotation q);

    /** 状態機械にもとづくステータス遷移。 */
    void changeStatus(Long id, String newStatus);

    /** 受注した見積から契約ドラフトを生成する（冪等・要員必須）。 */
    Contract createDraftFromQuotation(Long quotationId);
}
