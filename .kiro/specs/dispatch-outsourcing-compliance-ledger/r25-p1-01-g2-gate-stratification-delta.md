# R25-P1-01 G2 gate stratification delta（docs-only）

- **状態**: `SUBMITTED_FOR_R10_REVIEW / DOCS_ONLY`（2026-08-14・発注者指示5・R10 CHANGES_REQUIRED対応追記済み）
- **Provenance**: 発注者指示（2026-08-14）・R23-P1-01 repair（PR #74 fixed Head `fec98526`・merge `372c1a43`）のR10 Review/merge後、別のdocs-only decisionとして提出。
- **本decision受理前はstatus変更・追加実装をしない**。

## 0. Supersession記録（R25-P1-P1-01対応・accepted v3 §7との整合）

- accepted v3 §7（人間証跡と停止条件）のPASS条件は、**本deltaによりA/Bにstratify**する:
  - **§7 item 1〜5**（実在assignment指名・実actor承認event・実在資格保有者Review・人間確認・exact CLEAN evidence）→ **B gate（G2_PRODUCTION_AUTHORIZATION）へ移管**
  - **§7 item 6〜8**（Phase A/B browser evidence・最終Head L4/CI skip 0・R10最終Review）→ **A gate（S10_TECHNICAL_ACCEPTANCE）の技術受入条件として読み替え**
- **S10 PASS条件の一意性**: S10の技術PASS（A）とproduction authorization（B）は別gate。A達成でT066 technical PASS・S10 PASS・S12 READY、B未達の間はACTIVE/formal deliveryのみfail-closed（§7 item 1-5のproduction利用禁止を維持）。
- 本記録により、accepted v3 §7の「人間証跡が揃うまでT066/S10 PASS禁止」は**B gateのproduction PASS禁止**として読み替えられ、Aの技術PASSとは矛盾しない。

## 1. Stratification（採用契約A/B）

### A. S10_TECHNICAL_ACCEPTANCE（技術受入）

- **accepted G2/R23 mechanism実装完了**（V102_1/V102_2/V102_3・§3〜§5・repair対応）
- **Phase AはTEST/DEVELOPMENT fixtureを使用**（実在actor・資格保有者でない）
- 要件:
  - desktop/390px・watermark・font・改頁・mask・console error 0
  - previewはarchive 0・delivery 0
  - L1〜L4・MySQL smoke・CI skip 0
  - R10 fixed-Head独立Review
- **達成後**: T066 technical PASS・S10 PASS・S12 READY

### B. G2_PRODUCTION_AUTHORIZATION（production gate・別契約）

- 実在assignment actor本人のapproval
- 実在資格保有者によるexternal Review
- IDENTITY/AUTHORSHIPおよびfrozen policyが要求するQUALIFICATION/ACTIVE_STATUSの人間確認
- exact CLEAN evidence
- ACTIVE・Phase B・formal delivery
- **未達の間はACTIVE/formal deliveryをfail-closed**とするが、**S10 technical PASS・S12開発開始を阻害しない**

## 2. AIの権限（発注者代理）

- AIは発注者代理として開発段階の**公式source調査・暫定mapping・fixture・Phase A browser目視・技術受入**を実施可能。
- **AI/fixtureを実在actor・社労士・弁護士・資格保有者・正式Review証拠として登録してはならない**（§7・G2-VERIFY-05のAI代替禁止を維持）。

## 3. 法務mapping暫定決定

| 論点 | 暫定決定 |
|---|---|
| 待遇差説明請求可能旨の追加明示 | **2026-10-01施行版へ分離**（現行mappingには含めない） |
| 派遣料金 | **法34条の2に基づく派遣労働者向け明示を維持** |
| FM-C-28（個別派遣契約の料金明示） | **法定必須としない**・採用する場合は**business optional**として分類 |
| 実在専門家の最終確認 | **production authorization gate（B）で取得** |

### 3.1 FM-C-28 divergence記録（R25-P2-P1-01対応）

- `mapping-amendment-proposal-fm-c-28.md`（qps.md視点）はFM-C-28を**法的確実性が高く(a)採用を推奨**。
- 本deltaの暫定決定は**「個別派遣契約の法定必須としない・business optional」**であり、qps.mdの(a)推奨と**divergence**する。
- **divergenceの扱い**: gate B（G2_PRODUCTION_AUTHORIZATION）の**実在専門家確認対象として引き継ぐ**。実在専門家（社労士・弁護士等）が最終確認を行い、(a)採用の要否を決定する。A gate（技術受入）はこの決定を待たず進行可能。

## 4. 後続フロー

1. R10が本delta受理・PR #74 merge・main CI green（**実績: merge `372c1a43`・main CI 31802622981 success・1954/0/0/0 skip 0**）
2. Phase A完了（desktop/390px・watermark・font・改頁・mask・console error 0・preview archive 0/delivery 0・L1〜L4・MySQL smoke・CI skip 0）
3. **最終Review Packet提出** → R10最終Review → T066 technical PASS・S10 PASS・S12 READY
4. G2_PRODUCTION_AUTHORIZATION（B）は別gateとして人間証跡12-stepに従う（FM-C-28 divergence含む実在専門家確認を含む）

## 5. 名称一貫記録（R25-P2-P1-02対応）

- 「T066 M PASS」→「**T066 technical PASS**」への名称変更を本deltaで確定し、ledger・中央ledgerへ一貫適用する。
- A gate達成時は「T066 technical PASS」・B gate達成時は「T066 M PASS（production）」として区別して記録する。
