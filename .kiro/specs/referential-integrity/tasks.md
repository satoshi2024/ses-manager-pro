# Implementation Plan — 参照整合性ガード + 契約番号採番修正(referential-integrity)

タスクは独立しており順不同でよいが、1→2→3→4 の順を推奨(パターン確立→横展開)。
**コントローラーは編集禁止**(WS-D と並行のため)。ガードはすべて `*ServiceImpl.removeById` オーバーライドで実装する。

- [x] 1. 要員削除ガード
  - **Objective**: R1。稼動中契約・進行中提案を持つ要員の削除をサーバー側で拒否する。
  - **実装ガイダンス**: design.md 1章のコード例ほぼそのまま。`EngineerServiceImpl` に `ContractMapper` / `ProposalMapper` を注入し `removeById` をオーバーライド。
  - **テスト要件**: 新規 `ReferentialIntegrityGuardTest` — (a) 稼動中契約ありで削除 → BusinessException かつ deleted_flag=0 のまま、(b) 終了契約のみ → 削除成功、(c) 書類選考中の提案あり → 拒否、(d) 見送り済み提案のみ → 成功。既存 `EngineerDeleteIntegrationTest` がグリーンのまま。
  - **Demo**: 稼動中要員を要員一覧から削除 → エラーToast「稼動中の契約があるため削除できません」(フロント改修なしで表示されること)。

- [x] 2. 顧客削除ガード
  - **Objective**: R2。案件・契約・請求書が紐づく顧客の削除拒否。
  - **実装ガイダンス**: design.md 1章の表。3つのチェックを案件→契約→請求書の順で実施し、件数入りメッセージ(例「案件が3件紐づいているため削除できません」)。
  - **テスト要件**: (a) 案件あり顧客 → 件数入りメッセージで拒否、(b) 取消済み請求書(deleted_flag=1)のみ → 削除成功(論理削除の自動フィルタ確認)、(c) 何も紐づかない顧客 → 成功。
  - **Demo**: 案件を持つ顧客を削除 → 件数入りエラーToast。

- [x] 3. 案件・契約削除ガード
  - **Objective**: R3, R4。案件(契約/進行中提案)、契約(実績あり/稼動中)の削除拒否。
  - **実装ガイダンス**: design.md 1章の表と `ContractServiceImpl` の稼動中チェック例。契約の実績チェックは `WorkRecordMapper.selectCount`。削除済みIDへの `removeById` は例外ではなく false を返す従来挙動を維持する。
  - **テスト要件**: (a) 契約が紐づく案件 → 拒否、(b) 実績1件登録済みの契約 → 「実績が登録されているため削除できません」、(c) 稼動中契約 → 拒否、(d) 終了済み・実績なし契約 → 成功。
  - **Demo**: 実績入力済みの契約を契約一覧から削除 → エラーToast。

- [x] 4. 契約番号採番の最大番号方式化
  - **Objective**: R5。論理削除後の番号衝突による新規契約作成不能を解消する。
  - **実装ガイダンス**: design.md 2章。`ContractMapper.selectMaxContractNoIncludingDeleted`(@Select 追加のみ — このファイルは WS-D もメソッド追加するためマージ時は両方残す)→ `generateContractNo` と `saveWithBusinessRules` のリトライを最大番号+1 方式へ。
  - **テスト要件**: `ContractServiceImplTest` に追加 — (a) C-YYYYMM-0002 を論理削除後の新規作成が一発成功し 0003 が振られる、(b) 同月4件削除後でも新規作成が成功する(旧実装ではリトライ上限超えで失敗するケース)、(c) 当月契約ゼロなら 0001。
  - **Demo**: 契約を2件作成→2件削除→新規作成が成功し、番号が重複しない。`mvn test` 全件グリーン。
