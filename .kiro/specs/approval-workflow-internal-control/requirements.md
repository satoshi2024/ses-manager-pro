# Requirements — 承認ワークフロー・内部統制

## 前提

- 汎用ノーコードworkflow engineは作らず、固定対象adapterと設定可能なroute/stepに限定する。
- 初期対象: 見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopen。
- 勤怠承認は既存状態機械を維持し、本specの共通承認へ移行しない。

## R1. 申請/承認

1. THE システム SHALL 対象操作を直接確定せず、申請draftと対象差分snapshotを作る。
2. THE route SHALL 対象種別、法人/組織、金額帯、申請者roleにより決まり、順次/並列stepを持てる。
3. THE approver SHALL 特定user、permission group、申請者の上長、組織責任者、財務責任者から解決する。
4. THE 申請者 SHALL 自分の申請を承認できず、同一人物が複数stepに解決された場合も職務分離ruleを守る。
5. THE 承認 SHALL 承認/差戻し/却下/取下げを持ち、comment、時刻、代理理由を記録する。

## R2. 対象整合性

1. THE 申請時 SHALL 対象versionと差分を保存し、最終承認時に対象が変更されていれば競合として再申請を要求する。
2. THE 最終承認 SHALL 対象serviceの既存単件methodを1回だけ呼び、状態機械/金額検証/監査を再実装しない。
3. THE 外部API/メール送信 SHALL DB承認transaction内で行わず、outbox/jobでcommit後に実行する。
4. THE 再送 SHALL 同一申請から二重見積/請求/支払/外部連携を作らない。

## R3. route管理/代理

1. THE 管理者 SHALL routeをversion付きで編集し、適用開始日を指定する。
2. THE 既進行申請 SHALL 申請時route snapshotを使い、後のroute変更で承認者を変えない。
3. THE 代理承認 SHALL 期間、対象、委任者/代理者、理由、承認を持ち、監査表示で「代理」を明示する。
4. approver解決不能 SHALL 申請受付を拒否し、管理者へ設定不足を通知する。

## R4. UI/通知/SLA

1. THE ユーザー SHALL 自分の申請、承認待ち、完了一覧、差分、comment、履歴を閲覧する。
2. THE 通知 SHALL 申請、差戻し、承認、却下、期限超過を対象本人だけへ送る。
3. THE SLA SHALL stepごとの期限を持ち、期限超過を上位責任者へescalateできる。

## R5. 受入

- 申請者単独で対象5業務を確定できない。
- 承認中の対象変更を検知し、古いsnapshotを適用しない。
- 二重click/retryでも最終業務操作1回。
- route変更後も進行中申請の承認者不変。

