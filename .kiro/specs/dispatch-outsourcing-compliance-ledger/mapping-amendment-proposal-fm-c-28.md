# FM-C-28 追加提案（個別契約書への派遣料金明示行）— 発注者版管理判断依頼

- **提出**: 2026-08-12 / 実装AI
- **根拠**: 外部専門家Review（証跡3、`external-review-20260812.md`）P1-1
- **現状**: `field-mapping.md`のSRC-C manifest（FM-C-01〜27）と§3.1表に「派遣料金」行が無い。
  就業条件明示書（SRC-E）にはFM-E-20（DISPATCH_FEE_TYPED）が存在する。
- **外部専門家の指摘**: 令和6年10月1日施行の改正派遣法・施行規則により、個別契約書への派遣料金明示は義務化済み。

## 提案内容（96行 manifest → 97行）

### manifest追記（§3.5）

```
| FM-C-28 | SRC-C | 派遣料金 | DISPATCH_FEE_TYPED |
```

### §3.1 表追記（FM-C-27の次、紹介予定派遣の予定労働条件の後）

| 帳票／公式項目名 | 根拠・条件付き必須性 | 施行開始・終了／版 | DB column（F1 canonical resolution） | 画面入力・表示位置 | 帳票出力位置 | snapshot・asOf規則 | 保存期間 | field permission | 証跡URL・版・確認日 | 未決gate |
|---|---|---|---|---|---|---|---|---|---|---|
| 派遣料金 | 月額/日額/時間額（外部専門家P1-1: 個別契約書への明示は令和6年10月施行で義務化済みの指摘。一次source確定はGATE-T060-EXTERNAL継続） | SRC-C／令和8年7月版・10月改正対応 | t_contract_compliance_snapshot.dispatch_fee_amount, dispatch_fee_basis, dispatch_fee_currency（DISPATCH_FEE_TYPED） | compliance profileの派遣料金欄（売上/粗利列とは分離） | 個別契約書 派遣料金欄 | 契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない | `ARCHIVE_PENDING` | `P0_FULL`,`P1_MASK`,`P2_LIMITED` | SRC-C／令和8年7月版・10月改正対応／2026-08-09 | 保存形状はF1確定済み。法的意味・条件付き表示はGATE-T066-FIELD-SEMANTICS、帳票経路はB1 |

## 影響（版管理判断の論点）

1. **manifest行数**: 96行 → 97行（FM-C-28追加）。mapping hashが変動する。
   - **現行mapping blob hash（field-mapping.mdのgit blob hash、2026-08-12時点・round-trip再現確認済み）**: `10a3fc78600a978aea8b17086d5ecce7b81c479b`
   - **注意（外部専門家P1-A）**: 事前計算のamendment後hashは、行末・配置バリアントで変動し再現不能のため採用しない。正しい手順は「発注者判断(a) → amendment適用・commit → **commit後の実blobから`git hash-object`で再計算** → 証跡2へ記録」である。
   - **注意（外部専門家P1-B）**: `mapping_hash`（V102 `CHAR(64)`）の正本は§6.2のcanonical mapping/source payloadのSHA-256であり、Markdown blob hash（40 hex）ではない。blob hashはprovenanceとして別欄に記録し、`mapping_hash`欄はcanonicalizer実装後にDB rowから算出する（`g2-gate-evidence-templates.md`参照）。
2. **lifecycle規則（§2.1）**: PROVISIONAL_REVIEWED以降は編集せず新versionを作る、が正本。
   選択肢:
   - (a) **MAPPING-2026-07の新version（amendment）**: 現行PROVISIONAL_REVIEWEDのmappingを
     `MAPPING-2026-07-r2` として新version化し、FM-C-28を追加。旧versionはSUPERSEDED。
     新versionのhashはcommit後の実blob/DB rowから再計算する。
   - (b) **MAPPING-2026-10へ組み込み**: 2026-10-01施行版にFM-C-28を追加し、MAPPING-2026-07期間の
     個別契約書には反映しない（外部専門家の指摘が「義務化済み」である場合、2026-07期間の欠落が残るため非推奨）。
   - (c) **判断保留**: P1-1の一次source（省令・厚労省通知）確定を待ってから版管理を決定。
3. **証跡4（PDF目視）**: SRC-C記載例PDFの派遣料金欄の有無を実ブラウザで直接確認する
   （本実装AIのwebfetchではPDFが圧縮バイナリのためテキスト抽出不可と確認済み）。

## 実装影響（承認後の作業範囲）

- field-mapping.md §3.1/§3.5へFM-C-28行追加、mapping hash再計算、lifecycle status更新
- ComplianceDocumentGeneratorの個別契約書（INDIVIDUAL_CONTRACT）構成へ派遣料金行を追加
  （sensitive行、MASKで「—」）
- 法務fixture/generator goldenの更新

## 判断依頼

発注者（証跡5相当）による (a)/(b)/(c) の版管理判断と、P1-2の一次source確認方針を依頼する。

**補強材料（第三次照合・資格保有者視点の見解、2026-08-12）**: 派遣料金明示義務は令和6年10月1日施行分で確定済みのため、**(a)（MAPPING-2026-07 amendment版）を推奨**。(b)は2026-07期間の帳票が法定事項欠落のまま残るため不支持。(c)は必須性の不確実性がないため保留理由として成立しない（詳細様式の確認は証跡4で足りる）。P1-2（待遇差説明を求める権利）も令和6年10月1日施行分で創設済みとの見解であり、**MAPPING-2026-07側への組込みを(a)のamendと同時解消することを推奨**。詳細は `external-review-round3-qualified-20260812.md` を参照（実在Reviewの補助資料として利用可）。
