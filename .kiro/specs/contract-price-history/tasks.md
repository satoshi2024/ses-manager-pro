# Implementation Plan — 契約単価の改定履歴（contract-price-history）

逐次実施（1→2→3→4→M）。**前提: engineer-self-service-timesheet(P1) 完了後に着手**
（`WorkRecordServiceImpl.saveHours` 交差）。詳細は design.md 参照。

- [ ] 0. spec ドキュメント
  - **Objective**: 本ディレクトリ3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [ ] 1. スパイク: 単価読み取り箇所の棚卸し
  - **Objective**: `sellingPrice/costPrice` の全参照を分類し、design.md 0章の表を確定させる。
  - **実装ガイダンス**: design.md 0章。想定外の参照が見つかったら表へ追記し扱いを決定
    （必要なら design を更新）してから次へ。コード変更なし・表の確定版をコミット。
  - **テスト要件**: なし（調査タスク）。
  - **Demo**: design.md 0章の表が「想定どおり/差分あり（内容）」の結論付きで更新されている。

- [ ] 2. 履歴テーブル・リゾルバ・改定登録サービス
  - **Objective**: `t_contract_price_history`・`ContractPriceResolver`（単発+batch）・
    `revisePrice/priceHistory/deleteFuturePriceRevision`・API。
  - **実装ガイダンス**: design.md 1〜3章。初回改定の初期履歴自動補完・将来予約・
    過去遡及警告。H2 同期3点。i18n ×4言語。
  - **テスト要件**: design.md 7章の リゾルバ6＋revisePrice 6ケース。smoke test へ
    テーブル assert 追加。
  - **Demo**: curl で改定登録→履歴一覧→将来予約の削除。当月適用で契約の現在単価が変わる。

- [ ] 3. 精算・集計へのリゾルバ適用
  - **Objective**: `saveHours` と `resolveContractAmount`（+呼び出し元3箇所）を対象月単価に切り替える。
  - **実装ガイダンス**: design.md 2章。`resolveContractAmount` のシグネチャ拡張は
    Dashboard/Export/SalesPerformance の追随込み。batch 版で月ループの N+1 を作らない。
    実績あり月は実績優先（リゾルバはフォールバック経路のみ）を厳守。
  - **テスト要件**: design.md 7章の saveHours 2＋集計4ケース＋既存の集計・精算テスト全緑維持。
  - **Demo**: 改定（来月から+5万）を登録→今月の精算は旧単価・ダッシュボード来月見込みは
    新単価。過去月を reopen→再入力しても当時の単価で精算される。

- [ ] 4. 改定UI
  - **Objective**: 契約編集モーダルの単価 readonly 化（履歴あり時）＋単価改定ダイアログ＋
    改定予約バッジ。
  - **実装ガイダンス**: design.md 4章。履歴なし契約は従来どおり直接編集可（訂正と改定の区別）。
  - **テスト要件**: 既存 contract 系テストのグリーン維持（UI は Demo 検証）。
  - **Demo**: 改定ダイアログから登録→履歴表示→過去遡及時に警告が出る→
    確定済み実績の金額は変わっていない。

- [ ] M. 統合回帰
  - **Objective**: 回帰確認と文書更新。
  - **テスト要件**: `mvn test` 全緑（Docker あり環境で smoke 含む）。
  - **Demo**: 改定を跨ぐ2ヶ月の 勤怠→確定→請求 を通し、各月の請求額が当時単価であること、
    営業成績・Excel帳票も同額であることを確認。`.kiro/specs/README.md` の状態列を更新。
