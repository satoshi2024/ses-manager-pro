# 顧客視点プロダクト拡張 2026 — 全体実装ロードマップ

> 実装・Reviewの現行基線は `execution-review-handbook.md` v2.0である。S02のReview反復から得た再発防止は
> `s02-review-retrospective.md` に記録する。2026-07-28以前のcopyable conversationも
> `shared-standards.md`を介して本基線へ更新される。
> S03〜S17のtestは `test-execution-policy-s03-s17.md` に従い、通常Taskは定向/直接回帰、M taskで全量を実行する。

## 1. 目的

2026-07-26 の顧客視点レビューで採用された全提案を、別AIがそのまま実装へ移せる粒度へ分解した
マスター計画である。本ディレクトリは各spec間の優先順位、依存、共有ファイル、Flyway採番、完了条件の
唯一の正とする。個別specと矛盾した場合は本書を優先し、実装前に両方を同一コミットで修正する。

今回の対象は**仕様整理のみ**であり、Java/HTML/JS/SQLの実装は含まない。

通常の実装・Reviewは、次の3資料を使用する。

- `gate-decisions-g1-g6.md`: G1〜G6の正式なarchitecture decisionと本番release gate
- `platform-invariants.md`: **既定解の表**（S04以降必須）。S02が21ラウンドで到達した時間モデル・認可母集団・
  cache/transaction・期間代数・Migration fixture・金額/CSVの結論を全specの既定として固定したもの
- `spec-execution-ledger.md`: 17specの現在状態、blocker、実装/Review対話、次action
- `spec-start-conversations.md`: 17個の主実装AI用コピー対話。各spec内の原子taskを全て包含
- `spec-review-conversations.md`: 17個の独立Review用コピー対話
- `copyable-conversations/COPY-INDEX.md`: S01〜S17/R01〜R17を個別 `.txt` で全選択コピーする索引

補助資料として `parallel-execution-plan.md` と `subagent-delegation-summary.md` を使う。
`task-start-conversations.md` のT001〜T115対話は、通常運用では使わず、spec対話をtask単位へ例外的に
分割または再派工するときだけ使用する。T001は完了済みで、再派工・再Reviewの対象外である。

## 2. 現状認識

既存システムは、要員・スキル・顧客・案件・提案・見積・契約・電子契約・勤怠・請求・入金消込・
BP支払・月次締め・売上/粗利/キャッシュフロー・営業成績・採用・通知・監査・freee連携まで実装済み。
次段階では機能数の追加より、次の4点を優先する。

1. 承認・職務分離・法定帳票による正式運用への移行
2. BP会社、顧客接点、組織等の主データ化による自由入力の排除
3. 文書保存、認証、テナント分離、外部ポータルによる企業利用の安全性
4. 会計・デジタルインボイス・AIフィードバックによる外部接続と継続改善

## 3. 採用spec一覧

| # | spec | 主な価値 | 規模 | migration計画 | 状態 |
|---|---|---|---|---|---|
| 1 | `multi-company-tenant-isolation` | SaaS/複数法人のデータ境界 | XXL | V59（永久欠番） | 独立DBモード完了、共有DB実装延期、現在の実装taskなし |
| 2 | `organization-management-accounting` | 部門・上長・原価部門・予算 | XL | V60 | 仕様済 |
| 3 | `enterprise-identity-security` | OIDC/MFA/権限/セッション統制 | XL | V63〜V66 | 仕様済・G1決定済 |
| 4 | `legal-document-ledger-archive` | 電帳法を意識した文書原本・版・検索 | XL | V67 | 仕様済 |
| 5 | `productivity-search-saved-view` | 全文横断検索・実ToDo・保存ビュー・一括処理 | L | V68, V69 | 仕様済 |
| 6 | `bp-company-master-procurement-compliance` | BP自由入力排除・取適法/フリーランス法対応 | XL | V70, V71 | 仕様済・G2開発方針決定済 |
| 7 | `approval-workflow-internal-control` | 見積/契約/請求/BP支払/月次締めの職務分離 | XL | V72（CRM V73が先にmerge済みならV74へ繰り上げ） | 仕様済 |
| 8 | `crm-contact-opportunity` | 複数担当者・商機・失注理由・接点履歴 | XL | **V73（2026-08-01確定）** | 仕様済・S08着手可 |
| 9 | `order-acceptance-workflow` | 見積→注文→注文請→月次検収→請求の閉ループ | XL | V74 | 仕様済 |
| 10 | `dispatch-outsourcing-compliance-ledger` | 派遣/準委任の台帳・明示書・抵触日・偽装請負予防 | XXL | V75 | 仕様済・G2開発方針決定済 |
| 11 | `attendance-leave-overtime-compliance` | 雇用勤怠・休暇・36協定警告 | XXL | V76 | 仕様済・G6決定済 |
| 12 | `staffing-capacity-planning` | 募集枠・兼務・配賦率・将来需給 | XL | V77 | 仕様済 |
| 13 | `external-customer-bp-portal` | 顧客検収・文書受渡し・BP請求/空き要員更新 | XXL | V78 | 仕様済・G3決定済 |
| 14 | `engineer-self-service-portal-v2` | 要員のプロフィール変更申請・給与・経費・1on1 | XL | V79 | 仕様済 |
| 15 | `accounting-payment-integration` | freee売上/仕入/支払の冪等連携 | XL | V80 | 仕様済・G4決定済 |
| 16 | `jp-pint-digital-invoice` | Peppol/JP PINT送受信 | XL | V81 | 仕様済・G5決定済 |
| 17 | `ai-feedback-learning` | 推薦採否・成果・モデル版の評価ループ | L | V82 | 仕様済 |

採番の最新は仕様作成時点のV58だった。その後 `organization-management-accounting` の独立Reviewで
V61（組織/要員会計属性の履歴テーブル）とV62（要員の所属組織履歴拡張）を実際に使用し、
`enterprise-identity-security` がV63（identity/MFA/session/permission/file DDL）、V64（legacy role→
permission group seed）、V65（break-glass二者承認とMFA試行制限）、V66（action permissionのbaseline付与と
拒否指定）を使用した。そのため後続spec全ての予約番号を、その時点のFlyway最新番号`latest + 1`から
振り直している（本書とこの下の全designを同一更新で反映済み）。**現在適用済みの最新はV71であり、
後続specの予約はV72から始まる。** V59は作成せず、永久欠番として保持する。
実装依存順はV70..V71 BP→V72 approval→V73 CRMとして計画していたが、実際の着手順はBP（V70/V71、merge済み）
→**CRM（V73）**→approvalである。**#8 CRMのV73は2026-08-01に確定**し、`design.md`/`tasks.md`/派工対話/
`copyable-conversations`の全てがV73を指している。approvalの予約V72だけは番号と着手順が逆転しているため、
CRM V73がmerge済みの状態でapprovalを開始する場合はV74へ繰り上げ、V72はV59と同じ永久欠番として扱う
（Flywayは`out-of-order`を有効化していないので、V73適用済みDBへ後からV72を足すと`FlywayValidateException`になる）。
実装開始までに別migrationが追加された場合、V59を補完・再利用せず、予約表全体をその時点のFlyway最新番号`latest + 1`から連番で振り直し、本書と全designを先に更新する。V60以降が適用済みの場合もV59を追加してはならず、過去migrationの編集やout-of-order適用は禁止する。

## 4. 実行Wave

### Gate 0（着手前、コード変更なし）

- G0は2026-07-26に発注者が確定し、現在の正式な配備方式は顧客ごとの独立DBとする。
- 現在は `multi-company-tenant-isolation` のT001（全SQL・ファイル・ジョブ・キャッシュ棚卸し）のみ完了とし、T002/F1以降を開始しない。
- 独立DB方式でもデータ隔離の要件、既存の認証・データスコープ・ファイル参照検証を削除しない。
- 独立DB方式ではtenant/legal entityの将来互換方針を保持するが、V59は作成せず永久欠番とする。全表tenant_id化、TenantContext、tenant interceptor、tenant単位backup/restoreは延期する。共有DBを再開する場合も、当時のFlyway最新番号`latest + 1`を新しいmigration番号に使用する。
- 共有DBマルチテナントは、SaaS販売方式が正式決定され、契約・法務・セキュリティ・移行・運用要件が承認された時に再開する。
- G1〜G6は2026-07-26に発注者の明示委任に基づいて決定済み。詳細は`gate-decisions-g1-g6.md`を正とする。
- G2は公式資料と社内コンプライアンス責任者で開発を開始し、外部専門家Reviewを法定帳票の本番交付および
  該当M taskのrelease gateとする。システムは法的結論を自動判定しない。

### Wave 0 — 横断基盤（相互並行禁止）

1. `multi-company-tenant-isolation`
2. `organization-management-accounting`
3. `enterprise-identity-security`
4. `legal-document-ledger-archive`
5. `productivity-search-saved-view`

各specがSecurityConfig、GlobalControllerAdvice、BaseEntity、m_menu、監査/ファイル経路へ触れるため逐次実施する。現行方針ではtenant T001を完了成果物として退出させるが、tenantに続くWave 0の実装を自動放行しない。共有DB再開条件が成立するまで、tenantには現在の実装taskを置かない。

### Wave 1 — 内部統制と主データ

1. `bp-company-master-procurement-compliance` と `crm-contact-opportunity` は並行可。
2. 両方完了後に `approval-workflow-internal-control`。

### Wave 2 — 契約・稼動の業務閉ループ

1. `order-acceptance-workflow`
2. `dispatch-outsourcing-compliance-ledger` と `attendance-leave-overtime-compliance` は並行可。
3. 上記完了後に `staffing-capacity-planning`。

### Wave 3 — 外部利用者と外部会計

1. `external-customer-bp-portal` と `engineer-self-service-portal-v2` は、SecurityConfigを別担当が同時編集しない
   よう、portal側のsecurity chainを先にマージしてからengineer側を実施する。
2. `accounting-payment-integration`
3. `jp-pint-digital-invoice`

### Wave 4 — 差別化

- `ai-feedback-learning`。AIが業務状態を自動変更する機能は本Waveにも含めない。

## 5. 全体成果指標

| 観点 | 目標 |
|---|---|
| 内部統制 | 対象5業務の更新が申請者単独で確定せず、承認履歴と差分が追跡できる |
| BP管理 | 現役BP契約・支払・空き要員の会社名自由入力を0件にする |
| 契約閉ループ | 見積、注文、注文請、契約、月次検収、請求、入金をIDで追跡できる |
| 法令対応支援 | 派遣/準委任/フリーランス/取適法の必要確認項目と不足を一覧化できる |
| 文書 | 日付・金額・相手先で検索でき、版・ハッシュ・訂正削除履歴を保持する |
| セキュリティ | 管理者MFA 100%、外部IdP停止時の復旧手順、未知ファイルのfail-closed |
| ポータル | 顧客検収とBP請求のメール添付往復をポータル内で完結する |
| 労務 | 月45h・年360h・複数月平均等の警告を雇用勤怠ソースから算出する |
| 会計 | 外部連携の二重登録0件、失敗は再実行可能で相関IDから追跡できる |
| AI | 推薦ごとの採用/却下/面談/成約結果をモデル・プロンプト版別に評価できる |

## 6. 完了定義

各specは次を全て満たしたときだけ完了とする。

1. requirementsの各受入条件に対応する自動テストまたは明記されたDemoがある。
2. V1統合baseline、Flyway増分、H2 replay、`engineer-schema-h2.sql`、MySQL smoke assertが同期する。
3. APIの更新系はCSRF、監査ログ、データスコープ/tenant scope、状態機械、楽観ロックを確認する。
4. 新規文言は日本語・英語・中国語・韓国語の4バンドルへ追加する。
5. ページ/API/通知リンク/エクスポート/ファイルダウンロードに同じ認可母集団を適用する。
6. 各通常Taskは定向/直接回帰を完了し、各specのM taskで`mvn test`全量、必要なMySQL smoke、主要browser Demoを行う。
7. `.kiro/specs/README.md` と本書の状態を更新する。
8. （S04以降）`design.md`の「決定表」3表（時間・asOf / 主体×操作×可見母集団 / 状態機械と競合）が埋まり、
   `platform-invariants.md` からの逸脱がある場合は「逸脱と根拠」として明記されている。
