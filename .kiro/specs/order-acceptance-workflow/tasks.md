# Implementation Plan — 注文・注文請・月次検収

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T054〜T058はL1〜L3の定向test・直接回帰、T059でL4全量を実行する。
> 契約/請求の共有状態機械を変更した場合は昇格条件を評価する。

- [ ] F1. 注文/明細/検収DDL
  - **Objective**: F1. 注文/明細/検収DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V67/V1/H2/smoke、entity/mapper/number/状態。
  - **テスト要件**: unique、金額、状態、複数明細。
  - **Demo**: F1. 注文/明細/検収DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. 見積→注文→契約
  - **Objective**: F2. 見積→注文→契約 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 条件差分、approval hook、draft共通化、冪等。
  - **テスト要件**: 引継ぎ、差分、再送、rollback。
  - **Demo**: 見積から注文2明細→契約2件。

- [ ] A1. 注文画面/注文請PDF/archive
  - **Objective**: A1. 注文画面/注文請PDF/archive を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: CRUD、原本upload、PDF、document links。
  - **テスト要件**: PDF/hash/ACL/PO重複。
  - **Demo**: 原本受領→注文請発行。

- [ ] B1. 月次検収service/UI
  - **Objective**: B1. 月次検収service/UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: submit/accept/reject/cancel、work record link。
  - **テスト要件**: 状態/CAS/差戻し/amount snapshot。
  - **Demo**: 勤怠確定→検収差戻し→再提出→検収済。

- [ ] B2. 請求/月次締め/通知統合
  - **Objective**: B2. 請求/月次締め/通知統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: invoice SQL guard、未検収check、deadline通知/KPI。
  - **テスト要件**: 検収要/不要、重複通知、scope。
  - **Demo**: 未検収請求拒否→検収後生成。

- [ ] M. 全通し
  - **Objective**: M. 全通し を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/document/approval回帰。
  - **Demo**: 見積→注文→契約→勤怠→検収→請求。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
