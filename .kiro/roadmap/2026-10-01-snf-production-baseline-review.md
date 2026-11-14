# NF02 / NF03 / NF10 本番準備 baseline 独立静的Review

- Reviewer: 独立subagent `astra_review_baseline`（gpt-6-astra）
- Review対象: `8ad0c34eacbb04a031667934f313a2b6965583f1`。`origin/main@1443fb68943849a3260d7ebe005259f3eb609d60`とtree同一。
- 方式: read-only静的コード・spec審査。テスト実行なし。下記はReviewerの報告を総括が日本語で転記したもの。
- production approval: なし。

| Feature | PLAN | IMPLEMENTATION | PRODUCTION |
|---|---|---|---|
| NF02 | HOLD（公式CANDIDATE、Owner/DG-02未完了） | FAIL（P1×2） | BLOCKED |
| NF03 | 技術設計PASS、既存承認記録を維持 | FAIL（P1×1） | BLOCKED |
| NF10 | 技術設計PASS | FAIL（P1×1） | BLOCKED |

## PRD-NF02-001 / P1 / OPEN

SLA通知がrecipientの現行customer権限を検証していない。
`ServiceSlaMonitoringServiceImpl.java:337-339`は担当者のtenant/status/deletedだけを検証し、
同serviceの通知処理はrequest番号・subjectを送る。managerの組織変更、sales担当解除、
role/menu変更後にも通知内容を取得し得る。リンク先の再認可だけでは通知済み内容を回収できない。

修正条件: 各fallback候補のeligible role/menu/current customer scopeをrecipientとして検証する。
schedulerの全件権限をrecipientに流用しない。拒否recipientのnotification/outboxが0であることを確認する。
回帰: manager組織移動、sales scope縮小、HR/要員へのrole変更、menu無効、合法admin fallback。

## PRD-NF02-002 / P1 / OPEN

`ServiceRequestServiceImpl.java:981`の担当者validationが`selectById`だけでtenantを限定しない。
create/updateから別tenantの有効userを参照でき、無効userのerrorではrealNameも返し得る。
通知のtenant条件とは別の、cross-tenant referenceと情報漏えいの問題である。

修正条件: tenant-bound lookup、存在性とtenantを先に検証、他tenant/存在しないuserに同じsafe error。
回帰: create/updateで別tenantの有効/無効/deleted担当者を拒否し、保存状態不変、氏名等の漏えい0。

## PRD-NF03-001 / P1 / OPEN

`TrainingPlanServiceImpl.java:351`は任意のenrollment完了時にplanを無条件COMPLETEDへ遷移する。
同planに複数courseを登録できる一方、後続courseのstart/completeはAPPROVED/IN_PROGRESSだけを受け付け、
最初のcourse完了で残りのcourseが進行不能になる。

修正条件: plan lock内で非取消enrollment全体の終端状態を判定し、全対象完了までIN_PROGRESSを維持する。
取消と空planの規則、CASとeventの一回性を明示する。
回帰: 二course順次完了、一方取消、並行完了、再送。最後の有効course完了でだけplan完了eventが一回。

## PRD-NF10-001 / P1 / OPEN

`ReportDeliveryServiceImpl.java:180`の再認証時刻は共有delivery行に保存され、downloadの
`:361-362`はその時刻だけを使用する。`:332`はtokenなしのログイン済みdownloadを許可する。
同recipientのsession Aで再認証すると、session Bも10分以内に再認証なしでdownloadできる。

修正条件: step-up grantをtenant/user/deliveryだけでなく現session/認証instanceへ結合する。
共有timestampは監査用途に限定する。logout、session rotation、取消、権限縮小、期限切れで拒否する。
回帰: 独立した2 sessionでA成功・B拒否、上記失効条件、通常のrecipient scope。

## findingではない事項と証拠不足

- NF10 registrarのtransaction疑義は撤回。`DocumentServiceImpl.registerGenerated`はNOT_SUPPORTEDであり、
  上流の@Transactionalだけを根拠にstorage I/O transaction違反と断定しない。
- NF02の正式Owner/DG-02、NF03のretention policy/最終再Review/download E2E、NF10の最終Reviewと
  completion-matrixのBrowser evidence同期はコードfindingと分離して未完了とする。
- fixed remediation Headの動的再現・否定回帰・必要全Gate後にAstraが再判定するまでOPENを維持する。
