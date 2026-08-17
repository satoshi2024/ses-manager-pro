package com.ses.common.constant;

/**
 * 通知リンク先ページルートの定数。
 *
 * 通知の遷移先 URL はここに集約し、各 {@code NotificationService.publish(...)} 呼び出しは
 * 本クラスの定数を参照する。ページルート（{@code *PageController} の実マッピング）とのずれで
 * リンク切れ（404）が起きるのを防ぐため、これらのリンクは {@code NotificationLinkRouteTest} が
 * リフレクションで全件列挙し、実在ルートへ解決されることを検証する。
 * 以後、通知リンクを追加する際は必ずここに定数として置くこと。
 *
 * 要員詳細・顧客詳細のようなパラメータ付きリンクは、解決可能な基底ルートを定数に持ち、
 * 呼び出し側で ID を連結する（{@link #engineerDetail(Long)} / {@link #customer(Long)}）。
 */
public final class NotificationLinks {

    /** 契約一覧（/contract/list） */
    public static final String CONTRACT_LIST = "/contract/list";

    /** 契約更新カレンダー（/contract/renewal-calendar） */
    public static final String CONTRACT_RENEWAL_CALENDAR = "/contract/renewal-calendar";

    /** 請求書一覧（/invoice） */
    public static final String INVOICE = "/invoice";

    /** 提案カンバン（/proposal/kanban） */
    public static final String PROPOSAL_KANBAN = "/proposal/kanban";

    /** 案件一覧（/project/list） */
    public static final String PROJECT_LIST = "/project/list";

    /** 要員詳細の基底ルート（/engineer/detail）。ID はクエリで付与する。 */
    public static final String ENGINEER_DETAIL = "/engineer/detail";

    /** 勤怠グリッド（/work-record）— 勤怠提出の承認者向けリンク。 */
    public static final String WORK_RECORD = "/work-record";

    /** 要員のマイ勤怠（/my/timesheet）— 差戻し通知の要員向けリンク。 */
    public static final String MY_TIMESHEET = "/my/timesheet";

    /** ダッシュボード（/）— 資金繰り(CF)タブを含む経営KPI画面。 */
    public static final String DASHBOARD = "/";

    /** 承認inbox（/approval/inbox）— 承認申請通知の遷移先。 */
    public static final String APPROVAL_INBOX = "/approval/inbox";

    /** 注文一覧（/sales-order）— 注文未受領・注文請未返送通知の遷移先。 */
    public static final String SALES_ORDER = "/sales-order";

    /** 月次検収（/acceptance）— 検収未提出・期限超過・差戻し通知の遷移先。 */
    public static final String ACCEPTANCE = "/acceptance";

    // ---- S14 engineer-self-service-portal-v2 の要員本人向けリンク ----
    /** マイダッシュボード（/my/dashboard） */
    public static final String MY_DASHBOARD = "/my/dashboard";
    /** プロフィール・スキル申請（/my/profile）— 変更申請反映通知の遷移先。 */
    public static final String MY_PROFILE = "/my/profile";
    /** 給与明細（/my/payroll） */
    public static final String MY_PAYROLL = "/my/payroll";
    /** 経費申請（/my/expenses）— 会計連携・支払通知の遷移先。 */
    public static final String MY_EXPENSES = "/my/expenses";
    /** 1on1（/my/one-on-ones） */
    public static final String MY_ONE_ON_ONES = "/my/one-on-ones";
    /** サーベイ（/my/surveys）— キャンペーン配信通知の遷移先。 */
    public static final String MY_SURVEYS = "/my/surveys";

    private NotificationLinks() {
    }

    /** 要員詳細への遷移リンク（/engineer/detail?id={id}） */
    public static String engineerDetail(Long engineerId) {
        return ENGINEER_DETAIL + "?id=" + engineerId;
    }

    /** 顧客詳細への遷移リンク（/customer/{id}） */
    public static String customer(Long customerId) {
        return "/customer/" + customerId;
    }

    /** 月次検収への遷移リンク（/acceptance?workMonth={workMonth}） */
    public static String acceptance(String workMonth) {
        if (workMonth == null || workMonth.isBlank()) {
            return ACCEPTANCE;
        }
        return ACCEPTANCE + "?workMonth=" + workMonth;
    }

    /** 月次検収への遷移リンク（/acceptance?workMonth={workMonth}&acceptanceId={id}） */
    public static String acceptance(String workMonth, Long id) {
        if (id == null) {
            return acceptance(workMonth);
        }
        if (workMonth == null || workMonth.isBlank()) {
            return ACCEPTANCE + "?acceptanceId=" + id;
        }
        return ACCEPTANCE + "?workMonth=" + workMonth + "&acceptanceId=" + id;
    }
}
