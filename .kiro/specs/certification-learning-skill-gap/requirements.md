# 要件（NF-03 candidate）

> 本書は承認前のcandidate requirementsです。traceabilityが `CANDIDATE` の間は受入対象ではなく、production変更の根拠にはしません。

## R1 資格master・取得履歴・期限

HR/adminは資格名、issuer、外部資格コード、期限規則、active状態を管理できる。本人は自分の取得申請として取得日、期限（規則から計算する場合を含む）、資格番号、証憑を登録できる。

受入条件:

- 取得申請、証憑確認、active化、取消、訂正を別状態・別eventとして監査できる。
- 同一engineer・同一資格・同一有効取得を二重登録できない。訂正は旧eventを消さず、理由とactorを残す。
- 期限通知は期限の90/60/30日前を境界として判定し、当日を含むか、再送抑止、設定変更時の再計算を仕様化する。
- expired、cancelled、correctedの各状態がlist/detail/exportで一致し、証憑がCLEANでscope内の場合だけdownloadできる。

## R2 course・learning plan・enrollment

HRはcourse/provider、費用（JPY）、期間、capacity、対象canonical skillを管理する。本人またはmanagerはlearning planを作成し、goal skill、target level、期限、達成基準を設定する。enrollmentはplanned/started/completed/cancelledと結果、完了証憑、実費を保持する。

受入条件:

- planとenrollmentの変更履歴を保持し、cancelとcorrectを上書きで消さない。
- 費用がthreshold以上（等値を含む候補）なら既存approval engineへ申請し、申請者自身は承認できない。
- approvalが未完了ならmasterの確定状態やcompletionを先取りしない。route/candidateが解決できない場合はfail closed。
- training history（契約・法定dispatch）とlearning enrollmentを混在させない。

## R3 as-of skill gap

指定した案件期間またはas-of日について、canonical skill taxonomy、案件のrequired level、engineerのcurrent level、evidence project count、target periodを比較する。skillは同義語をcanonical IDへ解決し、未知skillは未知として説明可能に残す。

受入条件:

- 期間の両端を含めて案件を判定し、該当0件を空結果として安全に返す。
- 既存`m_skill_tag`、`t_engineer_skill`、`t_project_skill`、`t_project_position.skills_json`のsourceを表示または追跡できる。
- AI停止・timeout・provider errorでもrule-based gapが返る。
- AIはcourse/skill候補と理由を提示できるが、skill評価、昇格、配置、採否、人事上のadverse decisionを確定しない。

## R4 本人・上長・HR・export scope

本人は自分の資格・plan・enrollment・gapだけを操作し、managerは既存org∩DataScope、HR/adminは既存scopeに従う。salesを含む他roleは既存DataScopeを縮小も拡張もせず、PIIはmaskする。

受入条件:

- list/detail/count/export/download/通知recipientの母集団が同じeffective populationを使う。
- IDをリクエストから信用せず、本人はaccount linkからengineerを解決する。
- scope外のdocument link、資格番号、plan、gapをAPI/UI/CSV/XLSX/PDFのいずれからも取得できない。
- 既存engineer CSVとExcelのscope差分を放置したまま「母集団一致」を合格にしない。

## R5 証憑とPII

資格番号は個人情報として扱い、法務確認前は高機微のrestricted fieldとして保護する。証憑はDocumentLinkをscopeの唯一の根拠とし、raw pathをdomain tableに保存しない。

受入条件:

- 証憑の登録はDocumentService、version、scan、retention/legal holdを通る。
- scan statusがCLEANでないfile、unknown stored name、scope外linkはfail closed。
- 証憑のview/download/exportは同じscope checkを通り、複数linkはunion ruleを明示する。
- 資格番号は画面role別masking、list/export/AI promptのallowlistをテストで固定する。

## R6 通知・監査・再実行安全性

期限通知（90/60/30）、approval状態、plan/enrollmentの重要状態を本人・上長・HRのrecipient user IDへ通知する。scheduler再実行や同時更新で重複event、重複通知、二重completionが生じない。

受入条件:

- recipient user IDを保存し、通知生成時にorg scopeを後付けしない。
- state CAS、unique key、outbox/after-commitなど既存invariantに沿う。
- correct/cancelは理由、actor、occurredAt、対象versionを残す。
