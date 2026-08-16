# T066 M 受入チェックリスト（人間/外部プロセス向け）

- **作成**: 2026-08-12 / 実装AI
- **目的**: T066 MのPASS条件（G2 gate・外部専門家指摘の受入確認）を、人間/外部プロセスが効率的に検証するための手順書。
- **前提**: 実装AI側の到達点は全て完了（T060〜T066実装・L4全量 1844/0/0/0・R22全P1 CLOSE・外部専門家Review対応）。本リストは残る人間関与項目の検証手順。

## 1. 証跡4: 帳票PDF実ブラウザ目視（レビュー担当・人間）

対象: 実ブラウザで `/contract/detail/{id}` を開き、法定帳票・交付カードから生成・ダウンロードする。

| # | 確認項目 | 手順 | 期待 |
|---|---|---|---|
| 4-1 | 日本語フォント・レイアウト | 派遣/準委任/請負の各契約で4帳票種別（就業条件明示書・派遣先通知書・派遣元管理台帳・個別契約書）を生成しPDFを目視 | 文字化け・欠落・重なりなし |
| 4-2 | SRC-C記載例の派遣料金欄（P1-1） | 個別契約書PDFに派遣料金行が出力されるか、および公式SRC-C記載例PDFの料金欄の有無を直接確認 | P1-1のFM-C-28提案の裏取り（料金欄あれば追加確定、なければ発注者判断の補強材料） |
| 4-3 | 様式項目番号の照合（P3-2） | 公式記載例（SRC-C/E/N/Lの⑱⑳等の番号）と出力PDFの項目番号・manifest行を突合 | 全項目番号がmanifest行と一致 |
| 4-4 | role別mask | 営業/マネージャーでログインしダウンロードしたPDFの待遇・保険欄が「—」 | R4.2のmaskがPDFでも維持 |
| 4-5 | 受領確認フロー | 生成→受領確認ボタン→confirmed_at記録、未確認は「受領未確認」表示 | design §5.1の意味論がUIで成立 |

## 2. P2-3: 明示書の交付対象は労働者本人（受入時確認）

| # | 確認項目 | 現状 | 判断 |
|---|---|---|---|
| 2-1 | delivery recipient=worker | 現行B1実装はrecipientをcustomer contact（任意）として記録。明示書の労働者本人交付の記録は未実装（worker recipient列なし） | 実装可否・方式の決定（例: contractのengineer_idをworker recipientとして記録するか、S14のP3_SELF閲覧を起点にするか） |
| 2-2 | `confirmed_at IS NULL = 受領未確認` | T065で実装済み・test済み | 成立 ✓（P3_SELFの閲覧経路はS14側で別途） |
| 2-3 | P3_SELF（労働者本人閲覧） | 要員ロールは `/my/**` のみ。明示書の本人閲覧はS14（engineer-self-service-portal-v2）側で提供する想定 | S14着手時の受入条件として引き継ぎ |

## 3. P3-1: 派遣料金の数値整合（運用確認）

| # | 確認項目 | 手順 | 判断 |
|---|---|---|---|
| 3-1 | dispatch_fee_* と売上/粗利の乖離検知 | 同一契約でcompliance profileの派遣料金と契約売上/原価の乖離をどう扱うか（finding化の要否・閾値） | 運用方針の決定（要実装ならrule追加） |

## 4. 証跡5: GATE-T066-HISTORY（発注者決定）

| # | 確認項目 | 現状 | 判断 |
|---|---|---|---|
| 5-1 | 履歴table書き込み経路（苦情処理状況・キャリア・教育訓練・紹介予定・紛争防止・差異通知）の実装可否 | T061でtable整備済み・書き込み経路は本specの実装範囲外（受入対象外としてdesign §3.1に記録） | 実装可否の決定（別spec/将来実装 or 本spec追補） |
| 5-2 | P1-1の版管理判断（FM-C-28） | 提案書 `mapping-amendment-proposal-fm-c-28.md`: (a)2026-07新version/(b)2026-10組込/(c)保留 | 3択の決定 |

## 5. G2 gate（M PASSの前提）

| # | 確認項目 | 取得主体 |
|---|---|---|
| 5-3 | 証跡1: `COMPLIANCE_RESPONSIBLE` runtime assignment記録 | 管理者 |
| 5-4 | 証跡2: 対象mapping version/hashへの実actor承認event記録 | 実actor |
| 5-5 | 証跡3: 外部専門家Review — **資格保有者（社労士/弁護士）による実在Reviewが別途必須**（現状はAI一次照合のみ=条件付き確認） | 外部専門家 |
| 5-6 | P1-2: 待遇差説明/待遇情報提供の一次source確定（SRC-INDEXに「待遇に関する情報提供の例」が存在することを確認済み。改正省令・厚労省通知での施行時期・MAPPING-2026-07側の要否） | 外部専門家/発注者 |

## 完了条件

上記1〜5の確認・決定が揃った後、R10がM PASS判定 → S10 PASS → S12解放。

## 6. R25契約A（S10_TECHNICAL_ACCEPTANCE）達成記録（2026-08-15・R10最終Review認定）

- **R10最終Review（Phase A・R25契約A・983b71ac→merge 9885da21）: T066 technical PASS・S10 PASS・S12 READYを認定**。
- 本認定は**production authorizationを除く技術受入**としてのS10 PASS（R25契約A）。G2_PRODUCTION_AUTHORIZATION（B gate）は未達のまま・ACTIVE/formal delivery fail-closed維持。
- 技術受入の内訳:
  - P1-6 watermark preview（"PREVIEW" watermark・archive 0/delivery 0・営業403）
  - G2GatePhaseAE2ETest完全パス（TEST/DEVELOPMENT fixture・mapping→approval→subject→review→verification×4→adoption→ACTIVE）
  - G2GateBrowserPhaseATest（実Chrome CDP・desktop/390px・9 tabs・console error 0）
  - evidence（browser-g2・SHA-256 299e7f86/17be2509）・CI 1958/0/0/0 skip 0
- **残（B gate・人間証跡12-step）**: 証跡1〜5（実在assignment・実actor承認・実在資格保有者Review・人間確認・exact CLEAN evidence）＋FM-C-28 divergence含む実在専門家確認。取得後にproduction authorization判定。
