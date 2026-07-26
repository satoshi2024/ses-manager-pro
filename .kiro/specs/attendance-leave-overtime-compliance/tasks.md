# Implementation Plan — 雇用勤怠・休暇・時間外労働

- [ ] 0. G6/36協定/就業規則確認
  - **Objective**: 0. G6/36協定/就業規則確認 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: source of truth、勤務区分、丸め、カレンダー、休暇、協定期間/上限。
  - **Demo**: 本システムを正とするsource matrixのHR確認。法人別36協定/就業規則と外部社労士確認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. calendar/attendance/month/leave/agreement DDL
  - **Objective**: F1. calendar/attendance/month/leave/agreement DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V69/V1/H2/smoke、minute model、scope。
  - **テスト要件**: period/unique/closing/leave。
  - **Demo**: F1. calendar/attendance/month/leave/agreement DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. 集計/時間外calculator
  - **Objective**: F2. 集計/時間外calculator を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: official boundary fixtures、rolling平均、warning。
  - **テスト要件**: 全境界、跨夜/休日/休憩。
  - **Demo**: fixture結果をHRへ提示。

- [ ] A1. 本人/管理画面と月次状態
  - **Objective**: A1. 本人/管理画面と月次状態 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: calendar、入力/提出/差戻し/承認/締め。
  - **テスト要件**: 本人/上長/HR scope、CAS、mobile。
  - **Demo**: 本人提出→上長差戻し→再提出。

- [ ] A2. 休暇/approval統合
  - **Objective**: A2. 休暇/approval統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 申請、残数/外部参照、営業通知。
  - **テスト要件**: 半休/時間休/不足/重複/代理。
  - **Demo**: 休暇申請→承認→calendar反映。

- [ ] B1. freee/provider sync
  - **Objective**: B1. freee/provider sync を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 本システムの承認/締め済みdataをfreeeへ冪等送信またはCSV出力し、外部dataはread-only照合。cursor/冪等/error UI。
  - **テスト要件**: 401/429/timeout/重複/部分失敗。
  - **Demo**: sandbox syncと再実行。

- [ ] B2. 客先工数差異/通知
  - **Objective**: B2. 客先工数差異/通知 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 月次比較、理由確認、warning/escalation。
  - **テスト要件**: 金額非変更、scope、通知冪等。
  - **Demo**: 8h差異を確認して理由保存。

- [ ] M. 回帰/法務受入
  - **Objective**: M. 回帰/法務受入 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/給与・work record回帰。
  - **Demo**: 6か月rolling fixtureと月次全通し。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
