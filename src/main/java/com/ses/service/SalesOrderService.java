package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.order.SalesOrderDetailDto;
import com.ses.dto.order.SalesOrderListDto;
import com.ses.dto.order.SalesOrderSaveRequest;
import com.ses.entity.Contract;
import com.ses.entity.SalesOrder;

import java.time.LocalDate;
import java.util.List;

/**
 * 注文サービス。
 * 注文番号・状態機械（design §5.3）・見積→注文→契約の連携を担当する。
 */
public interface SalesOrderService extends IService<SalesOrder> {

    /** 注文一覧（data scope適用）。 */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<SalesOrderListDto> pageOrders(
            long current, long size, String status, String keyword, LocalDate dateFrom, LocalDate dateTo);

    /** 注文詳細（明細・差分・文書リンク付き）。scope適用。 */
    SalesOrderDetailDto detail(Long id);

    /** 注文作成（下書き）。PO重複は警告（拒否しない）。 */
    SalesOrder createFromRequest(SalesOrderSaveRequest request);

    /** 注文更新（下書きのみ）。 */
    SalesOrder updateFromRequest(Long id, SalesOrderSaveRequest request);

    /** 注文削除（下書きのみ）。 */
    void deleteOrder(Long id);

    /**
     * 状態遷移（状態CAS＋version）。
     * 下書き→受領確認で金額・支払条件snapshotを固定する。
     * 契約化→取消は承認必須のため、本メソッドでは直接遷移させない。
     */
    SalesOrder changeStatus(Long id, String newStatus);

    /** 注文番号採番（O-yyyyMM-NNNN）。 */
    String generateOrderNo(LocalDate baseDate);

    /** 見積から注文ドラフトを生成する（R2.1）。顧客・要員・案件・単価・精算幅を引き継ぐ。 */
    SalesOrder createDraftFromQuotation(Long quotationId);

    /** 注文から契約ドラフトを生成する（R2.2）。1明細→1契約で冪等（order_line_id UNIQUE＋状態CAS）。 */
    List<Contract> createContractDrafts(Long orderId);

    /** 注文条件と見積/契約の差分を計算する（R2.3）。 */
    List<SalesOrderDetailDto.DiffItem> computeDiffs(SalesOrder order);

    /** 注文が現在のscopeで参照可能か検証する（404秘匿）。 */
    void assertAllowedOrder(Long orderId);

    /** 承認適用からの取消（契約化→取消）。承認済みであることが前提のため直接遷移を許可する。 */
    void applyCancellation(Long id);

    /** 同一顧客×同一PO番号の既存注文が存在するか（PO重複の警告用。拒否はしない）。 */
    boolean isCustomerPoDuplicate(Long customerId, String customerPoNo);

    /** 同一顧客×同一PO番号の既存注文が存在するか（自ID除外オプション付き）。 */
    boolean isCustomerPoDuplicate(Long customerId, String customerPoNo, Long excludeOrderId);

    /** PO番号を正規化して空白を除去する（重複判定・表示の共通化）。 */
    String normalizePo(String customerPoNo);

    /**
     * 受領した注文書原本を文書台帳（ORDER_RECEIVED）へ登録する（R1.4）。
     * 同じ原本hash（SHA-256）の二重登録は拒否（R2.4）。登録済みの場合は拒否。
     */
    com.ses.entity.SalesOrder uploadSourceDocument(Long orderId, org.springframework.web.multipart.MultipartFile file);

    /**
     * 注文請書PDFを生成し文書台帳（ORDER_ACKNOWLEDGEMENT）へ登録する（R1.4 / design §3）。
     * 受領確認→注文請提出へ状態遷移する（注文請の発行＝提出）。冪等: 同一注文からは1文書。
     * @return 生成したPDFバイト列
     */
    byte[] generateAcknowledgementPdf(Long orderId, java.util.Locale locale);

    /** documentId が当該注文の原本/注文請として紐づいていることを検証する（download scope）。 */
    void assertDocumentLinkedToOrder(Long orderId, Long documentId);

    /** 注文の原本/注文請文書をscopeチェック付きで開く（注文一覧と同じ母集団）。 */
    java.io.InputStream downloadDocument(Long orderId, Long documentId);
}
