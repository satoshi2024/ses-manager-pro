# 115タスク並行実行計画

## 1. 目的と判定基準

本書は `task-start-conversations.md` のT001〜T115について、「同時に開始できるか」「何のmergeを待つか」
「誰が共有ファイルを所有するか」を定義する。ここでいう並行可は、次の条件を全て満たす場合だけを指す。

1. blocking decisionと先行taskが完了し、依存diffが実行branchへmerge済みである。
2. 別branch/worktreeを使うか、同一worktreeなら変更ファイル所有者を重複なしで宣言する。
3. migration、`SecurityConfig.java`、共通entity/service、`m_menu`、4言語message bundle、`tasks.md`の
   checkboxを同時編集しない。
4. 並行成果は統合担当が1本ずつ取り込み、各取り込み後に対象specの回帰を行う。
5. 「並行可」は同時mergeを意味しない。migrationと共有基盤は実際に確定したFlyway番号順・依存順に直列mergeする。

推奨上限は、1specにつき**統合担当1 + 子Agent最大3**である。複数specを並行する場合は、同時に実装する
大規模specを2本までとし、各specに統合担当を置く。人員より共有ファイル競合の方が先に上限になる。

## 2. Gateの並行調査

Gate決定前はコードを変更しない。調査自体は次の4レーンで並行できるが、最終決定とdecision-log更新は
発注者/統合担当が一本化する。

G0は2026-07-26に発注者が「顧客ごとの独立DB」と決定した。現在はT001の棚卸しだけを完了し、T002/F1以降、DDL、V59作成、共有DB全表tenant_id化を延期する。V59は永久欠番であり、共有DBのSaaS販売方式が正式決定された場合もV59を再利用せず、その時点のFlyway最新番号`latest + 1`から新しい実装計画とmigrationを採番する。現在のT002/F1を自動開始してはならない。

| 調査レーン | 対象 | 関連task | 成果物 | 禁止事項 |
|---|---|---|---|---|
| 配備・境界 | G0、tenant、組織境界 | T001 | table/SQL/file/cache/job inventory | G0をAIが推測決定しない |
| 認証・公開 | G1、G3、G8 | T014、T081 | IdP、domain、公開field、脅威モデル | provider契約や公開項目を推測しない |
| 法務・労務 | G2、G6、G7 | T021、T034、T041、T060、T067 | 様式、保存期間、36協定、承認閾値 | 法的結論をシステム判断へ置換しない |
| 外部接続・AI | G4、G5、G9、G10 | T094、T102、T109 | API plan、canonical mapping、DPA/PII条件 | sandbox未確認で本実装へ進まない |

G0〜G6は2026-07-26に決定済みで、詳細は`gate-decisions-g1-g6.md`を正とする。外部専門家、実provider、
法人別規程等の本番gateが未達でも明記されたmock/provisional範囲は開発できるが、該当M taskと本番releaseは停止する。
G0は独立DB方式で決定済みだが、共有DB改造を実装開始したことを意味しない。G7〜G10はblocking=noだが、該当taskの具体仕様を
変える場合は、推奨既定を採るか発注者決定を得たことを明記する。

## 3. spec間の実行波次

```text
Gate 0
  ↓
Wave 0: tenant → organization → identity → archive → productivity
  ↓
Wave 1: BP master ─┐
                   ├→ approval
        CRM ───────┘
  ↓
Wave 2: order → dispatch ─┐
                          ├→ staffing
                 attendance┘
  ↓
Wave 3: external portal ⇄ engineer portal → accounting → JP PINT
  ↓
Wave 4: AI feedback
```

| Wave | 同時実行単位 | 開始条件 | merge順 | 並行禁止理由/注意 |
|---|---|---|---|---|
| 0 | なし | G0決定済み、対象specのblocking決定済み | tenantはT001成果物で停止。共有DB再開後に当時のlatest+1から再計画 | BaseEntity、SecurityConfig、sidebar、file、監査を横断変更するため、現行独立DBでは実装しない |
| 1-A | BP master（T034〜T040）とCRM（T048〜T053） | Wave 0完了 | BP V66→CRM V67 | 実装は並行可だがDDL merge/deployは番号順。approvalは両方待つ |
| 1-B | approval（T041〜T047）単独 | BP/CRM完了 | V68 | Contract/Invoice/BP payment共通経路を変更 |
| 2-A | order（T054〜T059）単独 | approval完了 | V69 | 契約・請求状態機械の基礎 |
| 2-B | dispatch（T060〜T066）とattendance（T067〜T074） | order完了、G2/G6確定 | V70→V71 | Contract担当メソッドと雇用勤怠テーブルを分離 |
| 2-C | staffing（T075〜T080）単独 | dispatch/attendance完了 | V72 | proposal/contract/availabilityを統合参照 |
| 3-A | external portal（T081〜T087）とengineer portal（T088〜T093）は条件付き | Wave 2完了、G3/G8/G9方針確定 | V73→V74 | `SecurityConfig.java`はexternal portal統合担当のみが先に変更・merge |
| 3-B | accounting（T094〜T101）単独 | portal系、order、BP、archive完了、G4確定 | V75 | Freee adapter、invoice、BP paymentを変更 |
| 3-C | JP PINT（T102〜T108）単独 | accounting完了、G5確定 | V76 | CanonicalInvoiceと会計/請求境界を固定後に開始 |
| 4 | AI feedback（T109〜T115）単独 | CRM、proposal、staffing、outcome source完了 | V77 | 学習指標の母集団とPII境界を先に固定 |

## 4. spec内の並行グループ

表の `A || B` はAとBを同時実行可能、`A → B` はAのmerge後にBを開始、括弧内はカタログIDである。
全ての `M` taskは並行feature編集を止めた後、統合担当が単独で行う。

| spec | 直列基盤 | 並行可能な実装レーン | 合流/完了 | 推奨度 |
|---|---|---|---|---|
| tenant | T001完了。現在の実装taskなし。共有DB再開後に当時のlatest+1から再計画 | 現在はinventory・テスト設計のみ。再開後にF1相当からF3/F4/F5を原則直列 | M（将来の再計画後） | C: 現在は実装延期、子Agentは棚卸し/テストのみ |
| organization | F1（T008） | F2（T009）`||` A1（T010）。その後B1（T011）→B2（T012） | M（T013） | B: 2レーン |
| identity | 0→F1（T014→T015） | securityレーンA1→A2（T016→T017）`||` permission B1（T018）`||` file scan B2（T019） | M（T020） | B: SecurityConfigは1所有者 |
| archive | 0→F1→F2（T021→T022→T023） | 台帳UI A1（T024）`||` 既存帳票統合B1（T025）`||` export/retention B2（T026） | M（T027） | A: 3レーン |
| productivity | F1（T028） | 検索A1（T029）`||` ToDo A2（T030）`||` 保存ビューB1（T031）`||` 一括操作B2（T032） | M（T033） | A: 最大3子Agent、4本目は順送り |
| BP master | 0→F1→F2（T034→T035→T036） | 管理UI A1（T037）`||` compliance B1（T038）`||` risk/通知B2（T039） | M（T040） | A: 3レーン |
| approval | 0→F1→F2（T041→T042→T043） | inbox/diff A1（T044）`||` route/代理A2（T045）`||` SLA通知B1（T046） | M（T047） | A: engine/adapter固定後3レーン |
| CRM | F1→F2（T048→T049） | contact/timeline A1（T050）`||` lead/opportunity A2（T051）`||` KPI B1（T052） | M（T053） | A: 3レーン |
| order | F1→F2（T054→T055） | 注文/PDF A1（T056）`||` 月次検収B1（T057）。B1後に請求統合B2（T058） | M（T059） | B: 2レーン |
| dispatch | 0→F1→F2（T060→T061→T062） | profile UI A1（T063）`||` 法定帳票B1（T064）`||` deadline/risk B2（T065） | M（T066） | A: 法務field mapping固定後3レーン |
| attendance | 0→F1（T067→T068） | core F2→A1→B2（T069→T070→T073）`||` 休暇A2（T071）`||` provider B1（T072） | M（T074） | A: 3レーン、calculatorは主担当 |
| staffing | F1→F2（T075→T076） | board A1（T077）`||` heatmap B1（T078）`||` scenario B2（T079） | M（T080） | A: 3レーン |
| external portal | 0→F1→F2（T081→T082→T083） | 顧客A1（T084）`||` BP A2（T085）`||` 管理/通知B1（T086） | M（T087） | A: security/DTO boundary固定後3レーン |
| engineer portal | F1（T088） | dashboard/profile A1（T089）`||` 給与/勤怠A2（T090）`||` 経費B1（T091）`||` 1on1/privacy B2（T092） | M（T093） | A: 最大3子Agent |
| accounting | 0→F1→F2（T094→T095→T096） | UI A1（T097）`||` 売上B1（T098）`||` BP/経費B2（T099）。B1+B2後に照合B3（T100） | M（T101） | B: provider/job coreは1所有者 |
| JP PINT | 0→F1→F2（T102→T103→T104） | provider送信B1（T105）`||` UI A1（T106）。受信B2（T107）はprovider契約固定後 | M（T108） | B: canonical model/rendererは1所有者 |
| AI feedback | 0→F1→F2（T109→T110→T111） | feedback/outcome B1（T112）`||` evaluation B2（T113）。dashboard A1（T114）はB2 API固定後 | M（T115） | B: gateway/PII/versionは1所有者 |

推奨度は、A=ファイル境界を切れば積極的に並行可、B=主担当がinterfaceを固定した後のみ並行可、
C=同時編集せず、子Agentはread-only調査・テスト・レビューへ限定、を表す。

## 5. mergeチェックポイント

各並行グループは、次の順で合流する。

1. 統合担当が基盤taskを完了し、API/DTO/schema/interfaceと所有ファイル表を固定する。
2. 各レーンが最新基盤commitから開始し、担当task以外を変更しない。
3. レーンごとに自動テスト、taskのDemo、requirements traceを提出する。
4. 統合担当が1レーンずつ取り込み、競合解消後にそのレーンの回帰を再実行する。
5. 全レーンmerge後に、list/detail/count/export/download/notification/schedulerのscope母集団を横断確認する。
6. 最後に `M` taskを単独実行し、spec状態とcheckboxを更新する。

S03〜S17の各laneは定向testと直接回帰（L1/L2、共有境界はL3）を提出する。laneごとの全量testは禁止し、
全lane合流後のM taskでL4全量を1回実行する。merge競合解消または共有基盤変更がある場合だけ昇格条件を適用する。

## 6. 並行を直ちに停止する条件

- 同じFlyway番号、V1、H2 schema、`engineer-schema-h2.sql`を複数AIが変更している。
- `SecurityConfig.java`、共通entity/service、message bundle、sidebar、`tasks.md`の所有者が重複した。
- 先行branchがrebase/mergeされておらず、古いDTO/API/schemaを前提に実装している。
- tenant/data/file scope、外部公開DTO、法定項目、provider contractの解釈がレーン間で一致しない。
- 統合テストが赤いまま次のレーンまたは次Waveを開始しようとしている。
- 実装AIが担当task外の「ついで修正」を始めた。
