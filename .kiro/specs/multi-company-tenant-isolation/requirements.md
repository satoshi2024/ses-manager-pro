# Requirements — 複数法人・テナント分離

## 前提と境界

- G0で「顧客ごと単独DB」か「共有DB SaaS」を確定するまで実装を開始しない。
- テナントは契約顧客企業、法人は同一顧客内の請求/会計主体と定義する。
- `管理者`はテナント内管理者であり、全テナントを見られるplatform-adminではない。
- 本specは課金、契約プラン、セルフサインアップを対象外とする。

## G0決定後の現行適用範囲

- 2026-07-26の発注者決定により、現在の正式な配備方式は顧客ごとの独立DBである。
- 本specの共有DB向けR1〜R5は将来互換性と再開条件を定義するものであり、今回のT001完了をもって実装済みとは扱わない。
- 今回はT001の読み取り専用inventoryだけを完了し、現在のtenant実装taskはない。T002/F1相当、DDL、V59作成、既存行backfill、H2 schema変更、MySQL smoke assertを行わない。
- 全表`tenant_id`、`TenantContext`、tenant interceptor、tenant単位backup/restoreは共有DB方式の正式決定まで延期する。延期中も独立DBという現在のデータ境界、既存認証、データスコープ、ファイル参照検証を削除しない。
- V59は作成せず、従来の予約を取消して永久欠番とする。共有DBのSaaS販売方式が正式決定され、契約・法務・セキュリティ・移行・運用条件が承認された場合も、V59を補完・再利用せず、当時のFlyway最新番号`latest + 1`から実装計画を新規作成する。発注者の再開指示なしに実装を開始しない。
- 今後追加するテーブルは、tenant_idを持つ対象、global共有対象、tenant override対象を設計時に明示し、将来の共有DB互換性を確認する。

## R1. テナントと法人

1. THE システム SHALL `m_tenant` と `m_legal_entity` を管理し、1テナントに1件以上の法人を持てる。
2. THE 法人 SHALL 名称、法人番号、適格請求書番号、住所、代表者、銀行口座表示情報、会計連携ID、状態を持つ。
3. THE 業務データ SHALL tenantに所属し、請求/見積/契約/発注等はlegal entityを明示できる。
4. WHEN 既存DBを移行する時、THE migration SHALL `default` tenantと既存会社設定から1法人を作り、全既存行を欠落なくbackfillする。

## R2. 強制分離

1. THE システム SHALL 認証後の各requestにtenant contextを設定し、tenant未解決なら業務APIを拒否する。
2. THE ORM SHALL MyBatis-Plus tenant interceptorを第一防線として全対象SQLへtenant条件を注入する。
3. THE カスタム`@Select`、native SQL、集計、scheduler、async、export、notification、file SHALL tenant条件を別途棚卸しし自動テストする。
4. THE UNIQUE/FK SHALL tenantを含む複合制約とし、別tenantのID参照をDBまたはservice検証で拒否する。
5. THE キャッシュキー、ShedLock名、ファイルパス、外部連携キー SHALL tenantを含む。
6. WHEN tenant Aの認証者がtenant BのID/ファイル/URLを指定した時、THE システム SHALL 404または403を返し存在件数を漏らさない。

## R3. ログインと運用

1. THE ログイン SHALL hostname/subdomainまたはtenant codeでtenantを先に解決し、同名usernameの別tenant共存を許可する。
2. THE platform-admin SHALL 通常の顧客データ閲覧権を持たず、tenant作成/停止/health確認だけを専用経路で行う。
3. WHEN tenantを停止した時、THE システム SHALL 新規ログインと更新を拒否し、既存sessionを失効できる。
4. THE backup/restore SHALL tenant単位exportと全体PITRの両方の運用手順を持つ。

## R4. 後方互換と段階導入

1. G0が単独DBの場合、将来互換実装を導入する際も既存URL/ログイン/ジョブを不変に保つ。
2. 共有DB方式を開始する場合、tenant機能 SHALL feature flagで有効化し、無効時もtenant contextを`default`へ安全に固定する。
3. 共有DB方式を開始する場合、THE migration SHALL 大表を一括長時間lockしない段階backfill手順とrollback条件を文書化する。

## R5. 受入条件

1. tenant A/Bに同じusername、顧客名、契約番号を登録でき、相互検索・件数・export・通知・fileが漏れない。
2. tenant context欠落のscheduler/async処理がfail-closedになる。
3. 既存DBの件数・金額合計が移行前後で一致する。
4. 単独DBモードで既存全テストがグリーンのまま動く。
