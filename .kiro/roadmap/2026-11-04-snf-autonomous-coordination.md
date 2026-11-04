# 2026-11-04 自律調整の引継ぎ・統合計画

## 状態と権限境界

- 状態は **PENDING / NO-GO**。この記録は作業調整と統合計画であり、実装完了・受入れ完了・本番承認を意味しない。
- ユーザーは総責任者に対し、必要作業を自律的に進める権限を付与した。総責任者は Luna に実装を直接割り当て、Astra に独立レビューを直接割り当てる。ユーザーが指示を中継する運用には戻さない。
- 重いテスト実行者は一人に限定する。複数worktreeから同時に重いテストを起動しない。現在の実行担当・プロセス・対象を総責任者が記録し、完了または停止を確認してから次を開始する。
- 自律的な実装・レビュー・テストは本番投入承認ではない。以下の人手による Privacy、Legal、Security、Operations、Production の署名・承認は必須のまま維持する。

## ブランチ基準

- `origin/main` は `1443fb68943849a3260d7ebe005259f3eb609d60`。
- 統合先controlは `codex/snf-prod-control`、HEAD `9bac40c9624e7d964bcf6e18f480b3457662206e`。mainとの差はcontrolが **336コミット先行**であり、遅れているという意味ではない。
- 統合作業はcontrol上で行い、他の機能・修復の内容を保全する。機能ブランチのmergeや、controlへ適用済みのNF06基盤を含む長いcommit列の一括cherry-pickは避ける。

## 現在の証拠と未完了項目

### NF06

- `codex/snf-prod-nf06-metadata` HEAD `b007e7a24c6e8abae030c86119ac65218661fb97` の静的レビュー状態は **CODE PASS**。
- そのブランチの実行時検証は現在進行中。完了・結果確認までは **PENDING** とする。CODE PASSを実行時PASSや統合済み状態へ読み替えない。
- 既存controlには `d8469ecb`（走査証明・実MySQL待機検証）と `f932a888`（validation append・renew修復）の統合がある。NF06の統合はW3A counterとW3B sealの最終差分に限定し、controlの他機能や基盤修復を巻き戻さない。
- V184 `src/main/resources/db/migration/V184__create_import_metadata_foundation.sql` はcontrolとNF06 headで同一blob `31f41931f45425717b02d9832e5839012d637187`。変更対象外。
- 最小差分候補（実行時検証・独立レビュー後に再確認する）:
  - W3A: `ImportAmountParser.java`、追加 `ImportValidationCodebook.java`、`ImportValidationPipeline.java`、`ImportValidationService.java`、`ValidationCounterAccumulator.java`。テストは追加 `ImportValidationPipelineTestFactory.java` と `ValidationCounterAccumulatorTest.java`。
  - W3B: `ImportValidationAppendService.java` の必要な最終差分、追加 `ImportValidationLifecycleService.java`。テストは `ImportMetadataRealMySqlTest.java` の必要差分、追加 `ImportValidationSealRealMySqlTest.java` と `ImportValidationSealServiceTest.java`。加えて `src/test/resources/sql/schema-import-center-f1a-h2.sql` の必要差分。
  - `scripts/test-suites/mysql-shard-3.txt` に `ImportValidationSealRealMySqlTest` を追加する。NF06側のshard 1/2から既存項目を削除しない。後続のmysql1024修復との統合時にshard集合全体を再照合する。
  - control版 `ImportIdentityCandidateContextTest.java` は維持する。NF06側の削除差分を取り込まない。
- 統合時の高リスク箇所は、counter契約とamount parsing、validation append／sealのtransaction・CAS・ACK-loss回復、MySQLロック競合fixture、H2 schema、shard登録である。特に `ImportValidationAppendService.java`、`ImportValidationLifecycleService.java`、`ImportMetadataRealMySqlTest.java` はcontrol側の既存実装とテストを保った上でhunk単位で統合する。
- 推奨順序: (1) NF06 runtime検証完了とAstraレビュー結果を記録、(2) controlとの差分をファイル単位・hunk単位で再確認、(3) counter実装とテスト、(4) sealサービス・append差分とH2/テスト、(5) shardを集合マージ、(6) 必要なゲートを唯一の重型テスト実行者が順番に実行、(7)結果を記録し、統合判定を更新する。手順の各段階は別々に記録し、未実施のゲートを完了扱いしない。

### NF09

- `codex/snf-prod-nf09-local-keycloak` HEAD `72cf4a59` について、全Fastの **698 classes / 4326 tests / 0 failures / 0 errors / 0 skipped** と Repair50 の結果が報告済み。
- 総責任者による独立検証stepBでは、`target/surefire-reports` の1406項目のmanifestが全hash一致と報告済み。
- これらの証拠は記録されたスコープに限る。別のgateや本番受入れを自動的にPASSにしない。

### NF07

- `codex/snf-prod-nf07-evidence-contract` HEAD `91a67611`。57件の対象検証および4307件の全Fast履歴が受理済み。
- 履歴証拠の受理範囲を越えて本番署名や全体NO-GOを解除しない。

### runner / FULL_GATE

- runner `codex/snf-final-consolidation-gate` HEAD `5d596ecb` の2つのPowerShell検査は各200 assertionsを通過し、両レポートの内容hash一致が報告されている。
- 実行起点は `84cf2fb1`。**実FULL_GATEは未実行**であり、状態はPENDING。検査スクリプトのassertion PASSをFULL_GATE PASSに読み替えない。

## 総合判定

**NO-GOを維持する。** NF06の実行時検証、実FULL_GATE、必要な独立レビュー、各人手署名が未完了または別途必要である。Privacy / Legal / Security / Operations / Production の署名が揃い、正式な受入れ判断が記録されるまで、本番準備完了やリリース承認と表現しない。

## 記録の更新規則

各項目は証拠リンクまたはcommit/hash、対象範囲、実行者、結果を添えて更新する。予定・実行中・完了を区別する。未実行はPENDING、失敗や未解決事項はOPEN/FAILと明記し、根拠なしにPASSへ昇格させない。credential、token、secretはこの記録に含めない。
