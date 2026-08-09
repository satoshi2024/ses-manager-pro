# Implementation Plan — 派遣・準委任コンプライアンス台帳

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T060〜T065はL0〜L3の定向test・直接回帰、T066でL4全量を実行する。
> 法務受入gateと全量testの実行時点を混同しない。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの正式migrationは **V84**。order(V80/V81)のmerge後、attendance(V83)と並行可。V83実在のためV82は欠番として保持する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。
> S10の正式migrationは **V84**。V82は欠番として保持する。

- [x] 0. G2公式様式field mapping
  - **Objective**: 派遣元管理台帳・就業条件明示書・派遣先通知書・個別契約書の各法定項目が、
    DB列・画面・生成位置へ1対1で対応付けられる。以降の帳票生成が「どの項目をどこから取るか」を推測せずに済む。
  - **成果物**: 帳票ごとの法定項目→DB/画面/生成位置、保存期間、権限。
  - **Demo**: 厚生労働省公式URL/版/確認日/effective period付きmappingを独立Reviewし、mapping hashを固定して
    `PROVISIONAL_REVIEWED`にする。runtime社内責任者assignment、実actor承認event、外部社労士/法務Reviewは
    `ACTIVE`化、M PASS、本番交付のgate。
  - **実装ガイダンス**: production codeを変更しない。`field-mapping.md`として保存する（design §3）。
    **システムは法的適否を自動確定しない**（前提節）。mappingは項目の対応であって適法性の判断ではない。
    クーリング期間の日数など判断値は`m_system_config`へ置く前提で、コードへ直書きしないことを明記する。
  - **テスト要件**: L0。全帳票の法定項目が漏れなく対応付いていること、各項目に公式URL/版/確認日/effective periodが
    付いていること、mapping lifecycleとhash固定、特定自然人の事前固定なし、実actor承認event不在が開発baselineを
    blockしないこと、`git diff --check` exit 0。

- [ ] F1. workplace/profile/finding/delivery DDL（R10 Round 5 P1 docs再提出中）
  - **Objective**: 契約ごとの就業先・業務内容・就業時間・指揮命令者・責任者・2種の抵触日・待遇方式を、
    field-mappingのcanonical typed column/history形状で登録できる。mutable current profileとappend-only snapshot/historyを分離し、
    マスタ変更・profile改定・history訂正後も過去帳票の内容を再生成できる状態にする。
  - **実装ガイダンス**: **V84**/V1/H2(sql/schema-dispatch-compliance-h2.sql)/MySQL smokeを同一差分で再同期する。
    design.md §5.5のschema/history matrixを正本とし、SRC-E⑱、SRC-L④、2種抵触日、派遣料金、
    source/client別苦情、反復履歴、worker-specific snapshotを専用typed column/historyへ保持する。
    snapshotはUNIQUE(contract_id,snapshot_version)とし、content hashを一意性やretry keyに使わない。
    operation_id＋expected current versionで冪等性/CASを管理し、A(v1,hA)→B(v2,hB)→A(v3,hA)を許可する。
    current pointerはFK付きで、DB trigger/権限境界によるUPDATE/DELETE拒否、retention purgeの承認済み別経路を実装する。
    clear mechanismはFieldStrategy.ALWAYSをmutable current nullable列だけに適用し、history訂正はCORRECTED/CANCELLED新eventで行う。
  - **テスト要件**: L1〜L3。design §6.2のF1-MAP-01、F1-SNAPSHOT-01/02、F1-NULL-01、
    F1-HISTORY-CORRECTION-01、F1-PII-OWNERSHIP-01、F1-MYSQL-FRESH-01、F1-MYSQL-LEGACY-01、F1-MYSQL-PARTIAL-SCHEMA-01、
    F1-MYSQL-FAILED-HISTORY-REPAIR-01、F1-MYSQL-POST-APPLY-ROLLBACK-01、FK/期間、finding uniqueを実行する。
    field permissionの実maskはT063（detail/list/count）とT064（CSV/Excel/PDF/download）へ正式移管し、
    T061はinternal entityをportal/AI DTOへ直接渡さないprojection contractとconsumer scanだけを確認する。
  - **Demo**: profile snapshot Aを確定し、Bへ改定後に同一内容を新operationで再改定する。A(v1)・B(v2)・A(v3)を再取得し、
    2 workerのcurrent pointerが独立していること、currentだけの値→NULLとhistory訂正（旧行不変・新event）が安全側へ戻ることを確認する。

- [ ] F2. ComplianceRule分割/拡張
  - **Objective**: 既存の4つのcompliance ruleが挙動を変えずに動き続けたうえで、
    抵触日・責任者欠落・明示書未交付・保険未確認・期間外稼動・指示経路の各findingが検出される。
    rule再実行でfindingが重複せず、ack済みfindingがOPENへ戻らない。
  - **実装ガイダンス**: 既存`LaborComplianceService`を`ComplianceRule`群へ分解し、**既存4 code/挙動を維持**（design §2）。
    findingは`(contract_id, code, condition_fingerprint)`でupsert。毎回insertしない。
    抵触日は**後続契約・組織単位変更を考慮**し、契約chainを辿る（design §5.2）。
    rule実行はread-only＋upsert。契約や勤怠の業務状態を変更しない。
  - **テスト要件**: L2〜L3。**既存4 ruleの出力をgolden fixtureで固定**、
    code別境界、rule再実行でfinding重複0、ack済みfindingが再実行でOPENへ戻らないこと、
    契約chain（連続更新/クーリング/組織単位変更/並行契約）の抵触日算定。
  - **Demo**: 欠落profileを補完してfinding解消。rule を2回実行してfinding件数が増えないことを確認。

- [ ] A1. 契約compliance profile/UI
  - **Objective**: 契約詳細で派遣と準委任で異なる入力項目が出て、必須項目の不足がその場で分かる。
    待遇情報や個人情報は権限のないユーザーにはmaskされる。
  - **実装ガイダンス**: 契約形態別field、help、権限、差分。
    **待遇・個人情報はHR/法務/管理者のみ**。マネージャー/営業にはfield単位でmask（design §5.3）。
  - **テスト要件**: L1〜L3。T061から正式移管したR4.1のdetail/list/count field projection、validation、
    **field mask（画面）**、mobile 390px、
    契約形態切替時の項目切替。
  - **Demo**: 派遣/準委任で異なる入力項目。営業・マネージャーログインで待遇欄がmaskされることを画面のdetail/list/countで確認（CSV/Excel/PDF/downloadはB1のDemo）。

- [ ] B1. 法定帳票/交付/archive
  - **Objective**: 同一snapshotから派遣元管理台帳等を再生成でき、版差分が説明できる。
    生成物がarchiveへ保存され、交付日・交付方法・受領確認が追跡できる。
  - **実装ガイダンス**: generator/template version/delivery/受領。
    生成の冪等キーは`(contract_id, document_type, template_version, snapshot_hash)`（design §5.4）。
    同じsnapshotからの再生成で2件目を作らない。
    `t_document_delivery.confirmed_at IS NULL`は**受領未確認**（未交付ではない、design §5.1）。
  - **テスト要件**: L2〜L3。T061から正式移管したR4.2のexport/download/PDF field allow-listとmask、
    golden file照合、template version切替、hash、document ACL、
    同一snapshotの再生成で版が増えないこと。
  - **Demo**: 派遣元台帳等を生成し交付記録。同じsnapshotで再生成して版が増えないことを確認し、CSV/Excel/PDF/downloadの全経路でT064のfield allow-list/maskが維持されることを確認。

- [ ] B2. deadline/リスク運用
  - **Objective**: 抵触日と文書期限の90/60/30日前に通知が届き、対応担当・ack・解消・例外承認が記録できる。
    同じ期限で通知が重複しない。
  - **実装ガイダンス**: 90/60/30日、担当、ack/resolution/evidence。
    通知の宛先は担当営業/法務/HRの個人指定（design §5.3）。組織一斉にしない。
  - **テスト要件**: L2〜L3。日付境界（91/90/89日など各段階）、notification scope、
    冪等（同一期限・同一段階で1回）、例外承認の期限切れでOPENへ戻ること。
  - **Demo**: 抵触日alert→対応→解消。90日ちょうどと89日で通知段階が変わることを確認。

- [ ] M. 法務受入/回帰
  - **Objective**: 法務fixtureの3契約について台帳とfindingが期待どおりで、
    既存のcompliance機能・契約機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    既存4 ruleの回帰、法務fixture golden file、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 法務fixture3契約の台帳とfindingを照合。既存4 ruleの出力が変わっていないことを提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    runtime社内責任者assignment、対象mapping version/hashへの実actor承認event、外部社労士/弁護士Reviewを
    **本taskのPASSかつ本番releaseのgate**として確認する。いずれか未取得なら`ACTIVE`化・本番交付・M PASSを禁止する。
