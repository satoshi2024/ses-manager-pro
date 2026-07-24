# Tasks — 要員空き状況メール取込（FR-08）

FR-01 と実装共通部が多い。取込基盤・パーサ・レビュー画面を横展開。

- [x] F1. 基盤層（V4x・entity・mapper・DTO・IF・i18n・H2）
  - **Objective**: `t_bp_availability` ＋ 取込ジョブテーブル、エンティティ/Mapper/DTO/サービスIF、テストDB二重維持。
  - **Demo**: 空DBから適用、`bp-availability` メニュー表示。

- [x] A. 抽出＋AI解析（BpAvailabilityParseService＋mock）
  - **実装ガイダンス**: design 2章。条件式でBean一意。単価円換算。
  - **テスト要件**: MockParse テスト。
  - **Demo**: mock で要確認まで到達。

- [x] B. 取込サービス＋昇格
  - **Objective**: ジョブ・確定で在庫生成・要員化昇格。
  - **実装ガイダンス**: design 2章。`@Async` ObjectProvider、confirm Transactional+409、reject ガード、promote API。
  - **テスト要件**: `BpAvailabilityIngestionServiceImplTest`（baseMapper 注入）。
  - **Demo**: 貼付→確定で在庫化→昇格で要員化。

- [x] C. API・画面・マッチング連携
  - **Objective**: 一覧/レビュー画面、在庫一覧、マッチングに在庫を横断。
  - **実装ガイダンス**: design 3章。FR-01/FR-02 の候補取得を在庫込みに拡張。
  - **Demo**: 案件マッチ候補に外部在庫が出る。

- [x] M. 統合（清理プロバイダ、provider=gemini起動、全量緑）。

## 完了条件
- 要員メール貼付/eml→確定で外部在庫化、昇格で要員化できる。
- マッチング候補が自社Bench＋外部在庫を横断する。
- 二重確定409/却下ガード/非同期/provider=gemini起動 のテストが緑。
</content>
