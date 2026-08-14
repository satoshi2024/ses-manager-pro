# R25-P1-01 G2 gate stratification delta（docs-only）

- **状態**: `SUBMITTED_FOR_R10_REVIEW / DOCS_ONLY`（2026-08-14・発注者指示5）
- **Provenance**: 発注者指示（2026-08-14）・R23-P1-01 repair（PR #74 fixed Head `fec98526`）のR10 Review/merge後、別のdocs-only decisionとして提出。
- **本decision受理前はstatus変更・追加実装をしない**。

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

## 4. 後続フロー

1. R10が本delta受理・PR #74 merge・main CI green
2. Phase A完了（desktop/390px・watermark・font・改頁・mask・console error 0・preview archive 0/delivery 0・L1〜L4・MySQL smoke・CI skip 0）
3. **最終Review Packet提出** → R10最終Review → T066 technical PASS・S10 PASS・S12 READY
4. G2_PRODUCTION_AUTHORIZATION（B）は別gateとして人間証跡12-stepに従う
