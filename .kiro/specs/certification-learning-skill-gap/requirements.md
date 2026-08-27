# 要件（NF-03 candidate）

> 本書は承認前のcandidate requirementsです。traceabilityが `CANDIDATE` の間は受入対象ではなく、production変更の根拠にはしません。

## R1 資格master・取得履歴・期限

HR/adminは資格名、issuer、外部資格コード、期限規則、active状態を管理できる。本人は自分の取得申請として取得日、期限（規則から計算する場合を含む）、資格番号、証憑を登録できる。

受入条件:

- 取得申請、証憑確認、active化、取消、訂正を別状態・別eventとして監査できる。
- 同一engineer・同一資格・同一有効取得を二重登録できない。訂正は旧eventを消さず、理由とactorを残す。
- `CORRECTED`はcurrent statusにせず、訂正eventとrevisionとして保存する。訂正後の有効状態はACTIVE/EXPIRED/CANCELLED等を独立に導出し、renewはcontinuity groupを持つ新recordとして履歴を連結する。
- `expires_on`当日はAsia/Tokyoの終日まで有効とし、expiry ruleの変更は既存recordの`expires_on`を再計算せず、取得時のrule versionを保持する。
- 期限通知は期限の90/60/30日前を境界として判定し、当日を含むか、再送抑止、設定変更時の再計算を仕様化する。
- expired、cancelled、訂正後の各effective stateがlist/detail/exportで一致し、証憑がCLEANでscope内の場合だけdownloadできる。

## R2 course・learning plan・enrollment

HRはcourse/provider、費用（JPY）、期間、capacity、対象canonical skillを管理する。本人またはmanagerはlearning planを作成し、goal skill、target level、期限、達成基準を設定する。enrollmentはplanned/started/completed/cancelledと結果、完了証憑を保持し、実費は既存経費との関連から導出する。

受入条件:

- planとenrollmentの変更履歴を保持し、cancelとcorrectを上書きで消さない。
- 費用が`m_approval_route.min_amount`以上（等値を含む）なら既存approval engineへ申請し、申請者自身は承認できない。別の費用threshold masterは作らない。
- approvalが未完了ならmasterの確定状態やcompletionを先取りしない。route/candidateが解決できない場合はfail closed。
- training history（契約・法定dispatch）とlearning enrollmentを混在させない。
- planned costは申請時にsnapshotし、actual cost・支払・会計連携は既存`t_expense_request`を正本とする。enrollmentへactual costを複製しない。
- amountは税込JPYの非NULL `BigDecimal` とし、planの0円は監査eventだけで許可、実費が必要な場合は既存ExpenseRequestの正のamountを使う。actualがapproved plan budgetを超える場合は差額を黙って足さず、追加expense approvalまたはplan amendmentを要求する。
- 締め済みwork monthのexpense金額・関連・支払状態の変更は`MonthlyClosingService.assertOpenForUpdate`で拒否し、reopenは既存月次締めworkflowだけから行う。

## R3 as-of skill gap

指定した案件期間またはas-of日について、canonical skill taxonomy、案件のrequired level、engineerのcurrent level、evidence project count、target periodを比較する。skillは同義語をcanonical IDへ解決し、未知skillは未知として説明可能に残す。

受入条件:

- 期間の両端を含めて案件を判定し、該当0件を空結果として安全に返す。
- 既存`m_skill_tag`、`t_engineer_skill`、`t_project_skill`、`t_project_position.skills_json`のsourceを表示または追跡できる。
- AI停止・timeout・provider errorでもrule-based gapが返る。
- AIはcourse/skill候補と理由を提示できるが、skill評価、昇格、配置、採否、人事上のadverse decisionを確定しない。
- supplyはeffective-dated skill event、demandはproject/position eventを使う。履歴がない過去as-ofをcurrent rowで補完せず、`historical_data_unavailable`を返す。
- `PROJECT`/`POSITION`/`COMBINED`のsource precedenceとsource IDを結果に含め、monthly close/exportはimmutable snapshotを再利用する。

## R4 本人・上長・HR・export scope

本人は自分の資格・plan・enrollment・gapだけを操作し、managerは既存org∩DataScope、HR/adminは既存scopeに従う。salesを含む他roleは既存DataScopeを縮小も拡張もせず、PIIはmaskする。

受入条件:

- list/detail/count/export/download/通知recipientの母集団が同じeffective populationを使う。
- IDをリクエストから信用せず、本人はaccount linkからengineerを解決する。
- scope外のdocument link、資格番号、plan、gapをAPI/UI/CSV/XLSX/PDFのいずれからも取得できない。
- 既存engineer CSVとExcelのscope差分を放置したまま「母集団一致」を合格にしない。
- list/detail/export/downloadの対象除外ルールを共有する。退職完了・休職中は期限通知recipientから除外し、履歴recordの閲覧可否はrole scopeで別に判定する。復職時は90/60/30を過去分再送せず、現在残日数の一回通知だけをsemantic keyで発行する。

## R5 証憑とPII

資格番号は個人情報として扱い、法務確認前は高機微のrestricted fieldとして保護する。証憑はDocumentLinkをscopeの唯一の根拠とし、raw pathをdomain tableに保存しない。

受入条件:

- 証憑の登録はDocumentService、version、scan、retention/legal holdを通る。
- scan statusがCLEANでないfile、unknown stored name、scope外linkはfail closed。
- 証憑のview/download/exportは同じscope checkを通り、一般文書の複数link unionとは分離したrestricted priority ruleを適用する。
- 資格番号は画面role別masking、list/export/AI promptのallowlistをテストで固定する。
- 資格証憑は`CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` linkだけを認可根拠とし、generic `ENGINEER` linkが同じdocumentにあってもrestricted policyを優先する。eventに紐づくexact document version、hash、CLEANをdownload/export時に再検証する。

## R6 通知・監査・再実行安全性

期限通知（90/60/30）、approval状態、plan/enrollmentの重要状態を本人・上長・HRのrecipient user IDへ通知する。scheduler再実行や同時更新で重複event、重複通知、二重completionが生じない。

受入条件:

- recipient user IDを保存し、通知生成時にorg scopeを後付けしない。
- `Clock`はAsia/Tokyo（tenant timezone設定がある場合はその設定）を注入し、通知keyはrecord revisionではなく`record_id+semantic_expiry_date+threshold+recipient`で作る。既存`t_notification.dedupe_key` uniqueをDBで競合させ、重複側はinsert結果を再読してdedupedとして扱う。
- state CAS、unique key、outbox/after-commitなど既存invariantに沿う。複数JVM scheduler、manager変更、account未link、退職/休職の母集団をテストする。
- correct/cancelは理由、actor、occurredAt、対象versionを残す。

## R7 本人評価・上長提案・HR確定とAI利用境界

本人自己評価、上長提案、HR確定値は同一fieldの上書きで表現せず、assessment type・actor・effective period・reason・versionを持つ別recordで管理する。learning planのgoal skill、deadline、attainment criteriaは本人提出と上長合意を区別し、HRが確定したassessmentだけを公式skill projectionへ反映できる。

受入条件:

- AI候補にはsource/as-of/taxonomy version、model/provider、prompt allowlist、生成日時、期限、human accept/rejectを記録する。
- AI候補は人のassessment、配置、採否、昇格、給与、人事上の不利益判断を更新できない。候補を採用してもhuman decision eventとactor/reasonが必須である。
- `t_learning_decision_event`でdecision domain、source（rule/self/manager/HR/AI）、human actor、snapshot hash、adverse-use flagを監査できる。AI候補だけをadverse decisionの根拠にしない。
- AI provider停止・timeout・低信頼ではcandidate部分だけを空またはdegraded表示にし、R3のrule-based gapは同じas-of sourceで返す。
