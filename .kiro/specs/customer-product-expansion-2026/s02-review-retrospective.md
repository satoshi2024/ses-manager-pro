# S02 Review反復の振り返りと再発防止

## 1. 対象と結論

対象は `organization-management-accounting` のT008〜T013、およびS03開始前にS02前提から追加検出された
第十五〜十七次相当の指摘である。根拠はS02/S03のreview-ledger、中央ledger、関連commitとする。

反復の主因は実装者個人だけではない。初期specと対話は機能一覧を示したが、次を実装前に固定していなかった。

- current/history/snapshot/明示NULLの時間model。
- DataScopeとorganization scopeのロール別結合、全consumer inventory。
- 組織統合・異動の期間代数。
- transaction commit/rollbackとcache generationの順序。
- fresh/legacy/partial DBのMigration fixture。
- 第一次Reviewと再Reviewの範囲、issue ID、close条件。

個別endpointを直すたびに、別consumer、過去時点、旧DB、並行transactionから新しい欠陥が現れた。

## 2. 原因分類

| 分類 | S02の例 | 本来必要だった成果物 | v2.0の予防策 |
|---|---|---|---|
| scope inventory不足 | 待機原価、dashboard、forecast、aging、renewal、PDF、通知で母集団不一致 | 全read/write/export/job表 | handbook §6.2 |
| role意味論不足 | 営業/HRへ組織scopeを積集合し担当dataが0件 | role×scope結合表 | 和/積/bypassを固定 |
| 時間model不足 | 過去月が現在の親組織、原価部門、単価で変化 | current/history/snapshot/asOf表 | handbook §6.1 |
| NULL意味論不足 | 履歴行ありNULLを履歴なしと誤認 | explicit NULL/missing row契約 | 存在判定と別test |
| 期間代数不足 | 統合で未来/有限/部分重複所属を欠落 | 区間case表、不変条件 | 全境界fixture |
| 競合不足 | version未使用、所属closeのCAS失敗を無視 | CAS/UNIQUE/rollback表 | handbook §6.3 |
| cache不足 | principal衝突、権限変更後残存、rollback競合 | key/失効event表 | afterCommit、双transaction test |
| request境界依存 | RequestScope serviceがworkerで例外 | HTTP外caller inventory | scheduler/async test |
| Migration fixture不足 | backfill前の列参照、STORED生成列ALTER失敗、index形状差 | fresh/legacy/partial/repair | handbook §6.5 |
| OS差不足 | CRLF固定checksumがLinuxで失敗 | 正規化とCI環境表 | portable checksum |
| CSV契約不足 | 負の予算差を文字列化 | numeric/formula分類、往復fixture | handbook §6.4 |
| UI/API不一致 | APIは6次元、UIは3次元、ID直表示 | API↔UI field matrix | browser Demo |
| test oracle不足 | mock成功でも実SQL/時間/旧DB未検証 | AC別test matrix | 実状態assert |
| Review範囲不固定 | 変動working treeを繰返し全面確認 | Base/Head、Issue Register | handbook §9〜11 |

## 3. Round別に起きたこと

### 3.1 初期〜第七次

公開migrationの不変性、V60 DDL順序、組織scope漏れ、月次確定越権、notification、aging/renewal/export/downloadの
consumer漏れが順次発見された。個別修正以前に、全consumer inventoryが無かったことが原因である。

### 3.2 第八〜十一次

待機原価漏洩、予実key不一致、要員帰属のaccount link依存、組織更新/統合/退職、DB一意制約、cache key、
確定勤怠dimension freezeが発見された。「組織管理」と「会計」を別に考え、履歴・認可・集計を結ぶ不変条件が不足した。

### 3.3 第十二次

Docker実行でRequestScopeのworker障害、MySQL STORED生成列+FK ALTER制約、Linux checksum、CSV負数、CAS失敗の
握り潰しが顕在化した。H2・Mockito・Windowsの全緑が実環境保証にならないことを示した。

### 3.4 第十三〜十四次

過去時点の組織関係・要員会計属性を現在値から読む根本問題が発覚し、V61/V62の履歴modelが必要になった。
explicit NULL、期間限定所属、scope変更consumer、autocomplete契約も補強された。初期designにtemporal modelが
無かったため、後半で構造変更になった。

### 3.5 第十五〜十七次相当

S03開始前Reviewで、組織統合の区間分割、未来・同日・有限期間、会計履歴整合、設定cacheのcommit/rollback順が
追加検出された。S02最終Reviewがmerge済みHeadではなく変動差分を対象にしたこと、期間caseと双transaction testが
網羅されていなかったことが再発要因である。

## 4. 旧手冊の不足

1. 「確認する」と書いたが、必須成果物とSTOP条件が無かった。
2. acceptanceごとのtest matrixと証拠形式が無かった。
3. current/history/snapshot/NULL/fallbackの定型が無かった。
4. legacy/partial/repair Migration fixtureをDoDへ強制していなかった。
5. Base/Head未確定でもReviewを開始できた。
6. issue IDとclose状態がなく、ledgerが時系列文章中心になった。
7. 再Review範囲が無く、毎回全面監査に近くなった。
8. P2、未実施環境、本番gate、次spec blockerが混同された。
9. Round増加時に止めて根本設計を直す規則が無かった。

## 5. 強制改善

- 全specへ `execution-review-handbook.md` v2.0を適用。
- task開始前にREADINESSとTASK CONTRACT。
- temporal/scope/migration/cache該当taskは専用matrixなしに開始しない。
- Review Packetをcommit固定し、最終Reviewはmerge済みHead。
- review-ledger先頭へ現行判定とOPEN issue表。過去roundは履歴。
- 再Reviewはissue ID単位のdelta review。
- Round 4で個別修正を止め、spec/test matrixを先に修正。
- S03〜S17はS02予防項目が非該当でも理由を記録。

## 6. S02の現行取り扱い

v2.0適用だけを理由にS02を全面再実装・全面再Reviewしない。中央ledgerのOPEN P1、修正diff、直接回帰、
merge後最終Reviewだけを扱う。独立Reviewでclose済みの指摘は新しい再現証拠なしに再開しない。

desktop/390px Demoはcode不備と混同せず、owner/期限を持つ本番前hard gateとして追跡する。S03解放は中央ledgerの
最新判定を唯一の正とし、古い第十四次PASSだけで判断しない。
