# Implementation Plan — 複数法人・テナント分離

G0は2026-07-26に発注者が顧客ごとの独立DBと決定した。T001のみ完了とし、現在のtenant実装taskはない。T002/F1〜M、DDL、V59作成、共有DB全表tenant_id化は正式なSaaS販売方式の再決定まで延期する。V59は永久欠番であり、再開時も当時のFlyway最新番号`latest + 1`から新しいtaskとmigrationを再計画する。延期内容を実装済みと記載してはならない。

- [x] 0. G0決定と全SQL棚卸し
  - **Objective**: deployment方式、tenant解決方式、対象表、annotation SQL、ジョブ、cache、fileを確定。
  - **成果物**: `tenant-inventory.md`（表/SQL/unique/FK/owner/対応方法）。
  - **Demo**: 発注者がG0を決定しdecision-logへ記録。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. tenant/legal entity DDLと既存データ移行
  - **Objective**: 将来再計画時のtenant/legal entity DDL、V1最終形、H2 2系統、smoke assert。V59は使用しない。
  - **テスト要件**: 件数/金額reconciliation、複合UNIQUE、別tenant FK拒否。
  - **Demo**: DBコピーを移行し、差分レポートが全項目0。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

- [ ] F2. TenantContextとMyBatis強制条件
  - **Objective**: filter/interceptor/単独DBfeature flag。
  - **テスト要件**: contextなし拒否、A→Bのlist/detail/count漏洩なし。
  - **Demo**: 同じbusiness keyをA/Bに作り、各tenantから自分の1件だけ表示。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

- [ ] F3. カスタムSQL・非HTTP経路
  - **Objective**: annotation SQL、scheduler、async、cache、notificationへtenant適用。
  - **テスト要件**: inventory全行にテストIDを紐付け、thread reuse混線なし。
  - **Demo**: Aの通知生成後にBへ通知/件数が出ない。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

- [ ] F4. 認証・platform管理・停止
  - **Objective**: tenant別username、host照合、platform boundary、session失効。
  - **テスト要件**: host不一致、停止tenant、platformから通常API拒否。
  - **Demo**: tenant停止直後に既存sessionの更新が拒否される。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

- [ ] F5. ファイル・export・backup
  - **Objective**: tenant prefix、未知file fail-closed、tenant export/restore手順。
  - **テスト要件**: A fileをBが取得不可、backup restore件数一致。
  - **Demo**: tenant単位exportを隔離DBへrestore。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

- [ ] M. 全回帰と容量確認
  - **Objective**: M. 全回帰と容量確認 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: `mvn test`、MySQL smoke、主要API isolation matrix、既存単独DBモード。
  - **Demo**: A/Bの提案→契約→勤怠→請求を並行実行し相互漏洩0。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
