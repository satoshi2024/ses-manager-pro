# SES Manager Pro モンキーテスト（ランダム探索・フズィング）実行仕様

設計済みケースは「考えられた入力」しか通らない。本レイヤーは、想定外の操作・入力・状態遷移の組合せを seed 付きランダムで大量に実行し、500/未捕捉例外、白画面、無限スピナー、想定外 4xx、DB invariant 破壊、権限突破、UI 破壊を検出する。**モンキーテストは合格率の対象にせず、検出数・異常率・探索カバレッジで報告**し、検出した欠陥は通常の defect 台帳で severity/triage する。

## 1. 位置づけと実行規約

- 全実行は `E2E-BASE-300` の case clone 上で行い、baseline と既存 evidence を汚染しない。外部 adapter は固定 mock、失敗注入は QA profile の failpoint / network interception のみで行う。
- **seed は `RUN_ID=MT-YYYYMMDD-NNN` とモジュール名から導出**し、build SHA と組で完全再現可能にする。同じ seed から同じ操作列が再生される。
- 時間予算はモジュール単位で固定し、予算内に停止条件（P0 検出、invariant 違反）が発火したら即時停止して defect 化する。予算を消化せず停止した場合、そのモジュールは `BLOCKED_EVIDENCE` として報告する。
- ブラウザは desktop（Chromium）+ mobile（mobile emulation）の 2 viewport。API fuzz は未認証/認証済みの両方で行い、CSRF header は正しい値を付与した上で **payload のみ**を fuzz する（CSRF 自体は Security gate の担当）。
- 破壊的操作（DELETE、締め、承認、変換、role-menu 変更）も case clone 上では乱択対象にする。ただし `admin` 自身、baseline 参照データ、他ケースが共用する fixture は乱択対象から除外し、除外リストを manifest に記録する。
- 出力は `evidence/{BUILD_SHA}/{RUN_ID}/{MT_ID}/` に、seed、操作ログ（JSONL）、console/pageerror、network 異常、DB invariant 検査結果、video/trace（UI のみ）を保存する。

## 2. 3 種のモンキー

### 2.1 UI モンキー `MT-<MOD>-UI`

- 操作空間: 可視要素への click/tap/dblclick、hover、scroll、back/forward、再読込、キーボード入力（日本語・特殊文字・巨大文字列）、select/checkbox、フォームのランダム fill、modal 開閉、ファイル drop（dummy）。非表示・無効要素は操作対象にしない。
- 1 ステップごとに console/pageerror と network 4xx/5xx を検査し、10 ステップごとに DB invariant 検査を実行する。白画面・無限スピナーは描画 assertion で検出する。
- 予算: **30 分 × 2 seed / モジュール**（desktop / mobile を seed ごとに分離）。モジュール単位で `MT-01-UI` のような ID を振る（ID 一覧は §4）。

### 2.2 API フズィング `MT-<MOD>-API`

- frozen API inventory（method + path）へ、契約 schema から正値を生成した後、次のミューテーション corpus を投入する。
  - 欠落フィールド、`null`、型混在（string↔number↔array↔object）、空文字、1 文字、上限、上限+1、Unicode/emoji/surrogate pair、制御文字、負数、巨大数、指数表記、不正日付/時刻、パスパラメータの記号・traversal・Unicode、余剰フィールド、形式不正 JSON/XML、content-type 誤り、multipart 異常、ファイル名/サイズ異常。
- 結果を 4 分類する: `200`（許可された正値のみ期待）、`4xx`（業務拒否 = 期待）、`500`（欠陥）、想定外 `200`（妥当性/権限突破の候補）。分類と期待の不一致は全て defect 候補として記録する。
- DB は書き込み試行 10 回ごとに invariant 検査。大量 INSERT が発生する場合は事前に fixture 量を絞り、予算内に収める。
- 予算: **モジュールの frozen API 数 × 0.5 分（最小 10 分）を 2 seed**。`MT-<MOD>-API` の ID をモジュールごとに振る。

### 2.3 状態機械・並行モンキー `MT-<MOD>-ST`

- frozen transition matrix の全状態からランダムな `from → to` 要求を送り、許可/禁止判定を matrix と機械照合する。不一致は「matrix の欠落」か「実装欠陥」のどちらかとして triage する（matrix 更新が必要な場合は設計変更として記録し、黙って実施しない）。
- ランダムな actor × object（data scope 境界を跨ぐ組合せ）で detail/update/delete を送り、cross-owner 更新と存在秘匿 404 を検査する。
- 同一リソースへ 2 セッションのランダム同時更新ペアを 10 回実行し、version CAS / 一意制約の結果を検査する。
- 予算: **15 分 / モジュール**。`MT-<MOD>-ST` の ID をモジュールごとに振る。

## 3. DB invariant 検査

全モジュール共通の標準 suite を機械実行する。

- 業務キー重複 0（invoice/payment/proposal/contract/approval/notification 等）
- 残高・合計一致（invoice payment、BP 支払 layer 合計、歩合計算の再計算）
- `t_engineer_sales` の active primary が最大 1 件
- 負値・上限超過 0（金額、時間、FTE）
- 締め済み月 JSON（`closing.confirmed-months`）の一貫性、締め済み後の更新 0
- cross-owner 更新 0（data scope）
- 更新系成功に audit row が存在（`ApiAuditFilter` の frozen 対象 method のみ。PATCH 等は対象外を正とする）
- 参照整合性（orphan FK 0、soft-delete 行の復活・再表示 0）

モジュール別 invariant は `module-test-matrix.md` の DB 断言から機械抽出して suite に追加する。**invariant 違反 1 件 = P1 相当で即時停止・defect 化**する。

## 4. モジュール別 ID 一覧

`MT-<MOD>-UI` / `MT-<MOD>-API` / `MT-<MOD>-ST` を 17 モジュールすべてに定義する（計 51 ID）。current scope の実行対象は現行実装分のみであり、M-PASS 前の機能（MOD-09 の S16 部分、MOD-11 の S15 部分、MOD-12、MOD-13、MOD-10 の S13 部分）は `BLOCKED(M-PASS)` として ID を維持し、実行しない。S12 の Position/Allocation/Staffing は実装済み route が存在するため現行実装分は実行対象とし、S12 spec の受入判定は中央 ledger に従う。実行対象の判定は `module-test-matrix.md` の `BLOCKED` 表記と一致させる。

| モジュール | UI 予算 | API 予算 | ST 予算 |
|---|---|---|---|
| MOD-01 認証/権限/監査 | 30 分×2 seed | API 数×0.5 分 | 15 分 |
| MOD-02 候補者 | 同上 | 同上 | 同上 |
| MOD-03 エンジニア/担当営業 | 同上 | 同上 | 同上 |
| MOD-04 顧客/CRM | 同上 | 同上 | 同上 |
| MOD-05 案件/AI | 同上 | 同上 | 同上 |
| MOD-06 提案/メール | 同上 | 同上 | 同上 |
| MOD-07 契約/署名 | 同上 | 同上 | 同上 |
| MOD-08 勤怠/締め | 同上 | 同上 | 同上 |
| MOD-09 請求/消込 | 同上 | 同上 | 同上 |
| MOD-10 BP/在庫 | 同上 | 同上 | 同上 |
| MOD-11 BP支払 | 同上 | 同上 | 同上 |
| MOD-12 S14（BLOCKED） | — | — | — |
| MOD-13 S17（BLOCKED） | — | — | — |
| MOD-14 組織/会計/歩合/KPI | 同上 | 同上 | 同上 |
| MOD-15 承認/帳票/文書 | 同上 | 同上 | 同上 |
| MOD-16 給与/freee | 同上 | 同上 | 同上 |
| MOD-17 タスク/通知/検索/共通基盤 | 同上 | 同上 | 同上 |

## 5. gate と報告

時間予算内で次を満たす。

- 想定外 `500` / 未捕捉例外 0（UI・API とも）
- 白画面・無限スピナー 0、console error 0（UI モンキー）
- DB invariant 違反 0
- 想定外 `200`（権限突破・妥当性突破）0
- API 応答のエラー分類不一致 0（許可された 200 は schema 契約と一致）

報告には次を必ず含める。

- 試行回数（操作ステップ数 / payload 数）、時間予算の消化状況（skip 0）
- 探索カバレッジ（到達 route / API / 要素数 ÷ frozen 分母）。**参考値であり合格率に換算しない**（設計 coverage は ITa が持つ）
- 異常率（500・例外・白画面・console error ÷ 試行回数）
- 検出 defect（severity 別）、最小再現（seed + step / payload index）
- 環境起因 failure は直近 20 instance の 5% ルール（`schedule-and-resources.md` §9.2）で分離

## 6. 再現と回帰

- 検出欠陥は seed + step で最小再現し、defect 台帳へ登録する。再現できない場合は video/trace/HAR を併せて保留し、環境起因と混ぜない。
- 修正後は再現ケースと同一 seed のリプレイ、影響 API の deterministic ケースを再実行する。`schedule-and-resources.md` §9.3 の回帰ルールに従う。
- モンキーで発見された欠陥は、修正後に UI 実操作レイヤー（`ui-real-user-simulation.md`）の該当 ID へ回帰ケースを追加するか否かを triage で決定する（追加した場合は ID を更新して二重計上しない）。

## 7. 完了条件

1. current scope の全モジュールで 3 種モンキーが予算を消化し skip 0、証跡が揃う。
2. 検出 P0/P1 0。security・金額・法令の P2 0、その他 P2/P3 は承認 waiver 付き。
3. invariant 違反 0、想定外 500/例外/白画面 0。
4. 探索カバレッジと異常率、検出一覧を報告し、all-clear または waiver の承認を得る。
5. M-PASS 前の BLOCKED ID を件数・理由付きで報告し、M-PASS 後に全 ID を消化する。
