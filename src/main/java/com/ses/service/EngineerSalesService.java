package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.dto.engineersales.SalesUserOptionDto;
import com.ses.entity.EngineerSales;

import java.util.List;
import java.util.Map;

/**
 * 要員担当営業サービス
 * 割当・主担当変更・解除の業務ルールを担う（現任 = released_at IS NULL）
 */
public interface EngineerSalesService extends IService<EngineerSales> {

    /** 要員の現任担当営業一覧（主担当が先頭） */
    List<EngineerSalesDto> listActive(Long engineerId);

    /** 要員の担当営業履歴（解除済み含む） */
    List<EngineerSalesDto> listHistory(Long engineerId);

    /**
     * 担当営業を割り当てる。
     * 割当先は role=営業 かつ有効ユーザーであること。同一営業の重複現任割当は不可。
     * 要員への最初の割当は自動的に主担当となり、primaryFlag=1 指定時は既存主担当を降格する。
     */
    void assign(Long engineerId, Long salesUserId, boolean primaryFlag, String remarks);

    /** 指定割当を主担当にする（既存主担当は同一トランザクションで降格） */
    void setPrimary(Long engineerId, Long assignmentId);

    /**
     * 担当を解除する（released_at に当日を設定。物理・論理削除はしない）。
     * 他の現任担当が残っている状態での主担当の解除は不可。
     */
    void release(Long engineerId, Long assignmentId);

    /** 要員の現任主担当営業ユーザーID（未設定なら null） */
    Long findPrimarySalesUserId(Long engineerId);

    /** 複数要員の現任主担当営業を一括取得（key=要員ID） */
    Map<Long, EngineerPrimarySalesDto> mapPrimaryByEngineerIds(List<Long> engineerIds);

    /** 営業ユーザー選択肢（role=営業・有効のみ） */
    List<SalesUserOptionDto> salesUserOptions();
}
