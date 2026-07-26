# Requirements — 横断検索・実ToDo・保存ビュー・一括操作

## R1. 横断検索

1. THE ユーザー SHALL 要員、顧客、担当者、案件、提案、見積、契約、請求、BP会社を1つの検索窓から検索できる。
2. THE 結果 SHALL 種別、主名称、副情報、状態、更新日、遷移先を返し、権限/data/tenant scope外を件数にも含めない。
3. THE 検索 SHALL 2文字未満を拒否し、種別ごと最大件数と全体timeoutを持つ。
4. 初期実装はDB検索とし、日本語形態素解析/外部検索基盤は実測で必要な場合だけ別spec化する。

## R2. 実ToDo

1. THE システム SHALL 通知とは別にtaskを作成し、件名、説明、担当者、依頼者、期限、優先度、状態、関連業務を持つ。
2. THE task SHALL 未着手→進行中→完了/取消の状態機械とし、期限超過を表示する。
3. THE 通知からtaskを作成できるが、既読化とtask完了を混同しない。
4. THE 担当変更/期限変更/完了 SHALL 監査し、担当者へ通知する。

## R3. 保存ビュー/列

1. THE ユーザー SHALL 主要一覧のfilter、sort、page size、表示列を個人viewとして保存できる。
2. THE 管理者 SHALL tenant共有viewを作れるが、他ユーザーの個人viewを上書きできない。
3. THE 保存JSON SHALL allowlist schema検証し、任意SQL/field名を受け付けない。

## R4. 一括操作

1. THE 主要一覧 SHALL 最大200件の担当変更、状態変更、task作成、export対象選択を行える。
2. THE 一括操作 SHALL 事前preview、対象件数、変更差分、権限不足/不正状態を表示する。
3. THE 実行 SHALL 各行結果を返し、partial successを採用する操作は成功/失敗を再実行可能にする。
4. THE 危険操作（削除、支払済、月次締め等）は一括対象外とする。

## R5. 受入

- 営業Aの横断検索に営業B専用データが0件。
- 通知既読後もtaskが残り、task完了後も通知履歴が残る。
- 不正な保存view JSON/列名を拒否。
- 201件一括を拒否し、200件の各行結果を再現できる。

