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
}
