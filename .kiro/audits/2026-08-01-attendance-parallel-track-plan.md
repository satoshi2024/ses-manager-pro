# 2026-08-01 勤怠系並行トラック 実行計画

- 目的: S02〜S17の進行を**遅らせずに**、勤怠系の是正と雇用勤怠の前半を並行着手する
- 前提書: `parallel-execution-plan.md` §1（並行可の4条件）、`dependency-matrix.md` §2（共有ファイル）、
  `spec-execution-ledger.md`（状態の正本）、`.kiro/audits/2026-07-31-attendance-gap-analysis-and-plan.md`（背景）
- 適用: 本書のトラックA/Bだけ。S02〜S17のtaskそのものは本書の対象外

---

## 0. 本書の使い方（最重要）

**着手前に必ず §1 の検証を実行し、§2 の表を実測値で更新すること。** 台帳や本書の記述を
そのまま信じて着手してはならない。

理由は実際に起きた事故である。本計画の初版は**ローカル作業branchの `.kiro/` を読んで**
立案したため、採番も各specの状態も誤っていた。作業branchは分岐時点で凍結されるが `main` は
毎日動く。`.kiro/` は `main` 側だけが正しく更新される。

> **鉄則: `.kiro/` は必ず `git show origin/main:<path>` で読む。作業treeの同ファイルを読まない。**

---

## 1. 着手前検証手順

```bash
# (1) mainを取得し、Headを固定する
git fetch origin main
git log --oneline origin/main -1

# (2) 17specの状態（正本）
git show origin/main:.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md \
  | sed -n '/^| 1 /,/^| 17 /p' | cut -c1-200

# (3) 実適用済みmigrationの最新（採番の実測。予約表ではなく実ファイル）
git ls-tree --name-only origin/main src/main/resources/db/migration/ \
  | grep -oE 'V[0-9_]+' | sort -V | tail -15

# (4) 各specのtask消化数（台帳と食い違えばどちらかが古い）
for d in approval-workflow-internal-control crm-contact-opportunity \
         order-acceptance-workflow dispatch-outsourcing-compliance-ledger \
         attendance-leave-overtime-compliance; do
  printf "%-45s done=%s open=%s\n" "$d" \
    "$(git show origin/main:.kiro/specs/$d/tasks.md | grep -c '^- \[x\]')" \
    "$(git show origin/main:.kiro/specs/$d/tasks.md | grep -c '^- \[ \]')"
done

# (5) 採番予約の正本（README予約表）と各specヘッダの整合
git show origin/main:.kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md \
  | grep -nE '予約|欠番|V7[0-9]|V8[0-9]' | head -20
```

**検証の合否**: (3)の実ファイル最新番号と(5)の予約表が矛盾しない、かつ(2)と(4)が矛盾しない。
矛盾したら**着手せず統合担当へ報告**する。矛盾したまま進むと採番衝突で全環境が起動不能になる。

---

## 2. 検証結果（2026-08-01 実測 / `origin/main` = `f582f9e`）

| # | spec | 台帳状態 | task | migration | 本計画への影響 |
|---:|---|---|---:|---|---|
| 1 | tenant | T001 `COMPLETED` / T002〜 `DEFERRED` | 1/7 | V59 永久欠番 | なし |
| 2 | organization | `PASS` | 6/6 | 〜V62 | なし |
| 3 | identity | `PASS` | 6/7 | V63〜V66_1 | なし |
| 4 | archive | `PASS` | 7/7 | V67 | なし |
| 5 | productivity | `PASS`（2026-08-01 発注者確認） | 6/6 | V68/V69 | Wave 0 完了 |
| 6 | BP master | `PASS`（CONDITIONAL, P2=13） | 7/7 | V70/V71 **適用済** | なし |
| 7 | approval | `NOT READY` | 0/7 | **V75 予約** | **B軌の前提ではない**（§4参照） |
| 8 | CRM | **`IN PROGRESS`**（R08 Round 2, P1=1 未閉） | 6/6 | V73/V74 **適用済** | S07の解放待ち |
| 9 | order | `NOT READY` | 0/6 | V76 予約 | **A3/C軌の前提** |
| 10 | dispatch | `NOT READY` | 0/7 | V77 予約 | なし |
| 11 | **attendance** | `NOT READY` | 0/8 | **V78 予約** | **B軌の出所** |
| 12 | staffing | `NOT READY` | 0/6 | V79 予約 | なし |
| 13〜17 | portal〜AI | `NOT READY` | 0 | V80〜V84 予約 | S14のみA1と論点重複 |

**実適用済みの最新migrationは `V74`。V72は `V59` と同じ永久欠番。**
CRMがDDL(V73)に加え権限seed(V74)を消費したため、approval以降はV75〜V84へ繰り上げ済みである。

### 2.1 現在のクリティカルパス

```
CRM(S08, IN PROGRESS) → approval(S07,V75) → order(S09,V76) → dispatch(S10,V77) ∥ attendance(S11,V78) → staffing(S12,V79)
```

**勤怠(S11)は最短でもCRM→approval→orderの3spec先。** 本書のトラックA/Bは、この待ち時間を
遊ばせないためのものであり、S11そのものを前倒しするものではない。

### 2.2 初版の誤りの訂正

初版で「`parallel-execution-plan.md` の採番が古い」「attendance tasks.md の本文と予約番号が矛盾」
「台帳のS06が `NOT READY` のまま」と記述したが、**いずれも誤りである**。`origin/main` 側の
これらの文書は全て更新済みで、相互に整合している。誤りの原因は §0 に記した作業branch参照であり、
文書側に修正すべき点はない。**訂正作業は不要。**

---

## 3. 並行可否の判定原則

`parallel-execution-plan.md` §1 は「人員より共有ファイル競合の方が先に上限になる」と定めている。
したがって**タスクは機能単位ではなく、§1.3 の禁止6類に触れるか否かで切る**。

禁止6類: migration / `SecurityConfig.java` / 共通entity・service / `m_menu` / 4言語bundle /
各spec `tasks.md` のcheckbox。

このうち **migrationだけはrebaseで解消できない**。番号は先着順に確定し、後から下位番号を
足すと `FlywayValidateException` で全環境が起動不能になる（V72が永久欠番になった理由）。

> **本計画の一丁目一番地: トラックA/Bは `V*.sql` を1本も新規作成しない。**
> DDLが必要になった時点で、その作業はトラックCへ移す。

---

## 4. トラック定義

### トラックA — 零競合・即時着手可

いずれもmigration・`m_menu`・共通serviceに触れない。

| ID | 内容 | 触ってよいファイル | 競合面 |
|---|---|---|---|
| **A1** | マイ勤怠UIの情報補完（提出期限・月合計・承認状況） | `templates/my-timesheet/index.html`、`static/js/modules/my-timesheet.js`、messages×4 | S14(Wave 3)と論点重複のみ。先行すればS14の土台になる |
| **A2** | 勤怠未提出リマインド | `service/NotificationGenerateService.java`（generator 1本追加）、messages×4、対応test | S06〜S17に所有者宣言なし |
| **A3** | 承認滞留の可視化（**読み取り専用**） | `static/js/modules/work-record.js`、必要なら読み取り専用APIの新規メソッド | 零（下記の設計判断による） |

**A2の設計判断 — migrationを作らない。**
既存の通知生成は `systemConfigService.getInt("notice.contract-end-days", 30)` の形で、
**設定行が無ければコード既定値で動く**（`cashflow.alert-months` は実際にseed行を持たない）。
したがって閾値はコード既定値で実装し、`m_system_config` へのseedは将来いずれかのmigrationに
同梱させる。これでA2は採番を一切消費しない。

**A3は意図的な機能縮小。**
当初案の「一括承認」は `WorkRecordApiController`／`WorkRecordServiceImpl` を変更する。
`dependency-matrix.md` §2 は同ファイルの争用者を order(S09) と attendance(S11) と明記し
「orderを先行」と定めている。S09のtaskには「勤怠確定→検収差戻し→再提出→検収済」があり、
勤怠状態機械の境界に触れる。よって**書き込み操作を一切含まない可視化に縮小**し、
真の一括承認は order(S09) PASS後（＝トラックC）へ送る。

### トラックB — S11の非DDL部分の先行

**B軌は「無料の並行」ではない。目録内taskの前倒し実行であり、中央台帳への登録が必須。**
登録せずに進めると影子実装になり、S11担当が同じものを再実装する。

| ID | 内容 | 出所 | 前提 |
|---|---|---|---|
| **B1** | source matrix と法人別36協定の棚卸し | S11 task 0（T067） | なし。spec本文が「production codeを変更しない」と明記 |
| **B2** | `OvertimeComplianceCalculator` + 境界fixture | S11 F2の前半 | G6確定済み（`overtime-rules.md` が確定値）。**orderのPASSを要しない** |

**B2がorderを待たなくてよい根拠と、待つべき部分の切り分け。**
design §5.2 は閾値の解決順を `m_overtime_agreement` → `m_system_config` → コード定数と定める。
このうち**第2・第3段だけを先に実装**し、第1段はinterfaceのまま残す。calculator本体と
境界fixture（特に**ルール4=月100hだけが `>=`** の3点fixture）はDDLと無関係に完成できる。

B2で**やらないこと**: DDL、画面、`WorkRecordServiceImpl` への接続、`m_overtime_agreement` の実装。
これらはF1（V78）に属し、トラックCである。

### トラックC — 待つべきもの（着手禁止）

| 内容 | 解放条件 |
|---|---|
| S11 F1 DDL（V78） | order(S09) PASS + G6確定。**採番を消費するため直列merge** |
| 休暇申請・承認画面（S11 A1/A2） | approval(S07) engine完成後 |
| 真の一括承認 | order(S09) PASS後 |

---

## 5. 開始条件（着手前チェックリスト）

各トラックは以下を**全て**満たしたときだけ着手する。1つでも満たさなければ停止し報告する。

### 全トラック共通

- [ ] §1 の検証手順を実行し、§2 の表を実測値で更新した
- [ ] 検証の(3)実ファイルと(5)予約表に矛盾がない
- [ ] `origin/main` から新規branchを切った（PR #47 のbranchから派生させない）
- [ ] 自分の担当ファイルが §4 の「触ってよいファイル」に収まっている
- [ ] `V*.sql` を新規作成しないことを確認した

### A1 / A2 / A3

- [ ] 上記共通条件を満たす
- [ ] （A2のみ）閾値をコード既定値で実装し、`m_system_config` へのseedを含めない

A1〜A3は相互に重複しないため**同時並行可**。

### B1

- [ ] 上記共通条件を満たす
- [ ] production codeを1行も変更しない（成果物は文書のみ）

### B2

- [ ] 上記共通条件を満たす
- [ ] **中央台帳(`spec-execution-ledger.md`)に本トラックの行が追加済み**、
      またはその追加を統合担当へ依頼済み
- [ ] S11のtasks.mdのcheckboxを**変更しない**ことを確認した
- [ ] `git show origin/main:.kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md` を読んだ

---

## 6. 停止条件

以下に該当したら作業を止め、統合担当へ報告する。推測で進めない。

1. DDLが必要になった（＝トラックCの作業を掴んでいる）
2. §4の「触ってよいファイル」以外の変更が必要になった
3. `origin/main` のrebaseで4言語bundle以外にconflictが出た
4. `MessageBundleConsistencyTest` がrebase後に落ちた（4bundleの同期漏れ）
5. 台帳の状態と実ファイルが矛盾した
6. S07(approval)またはS09(order)が着手された（A3/C軌の前提が動く）

---

## 7. 運用規約

1. **1トラック1branch1PR。** PRは小さく保つ。§1.4 は統合担当が1本ずつ取り込む運用であり、
   PRが小さいほど取り込みが早い。
2. **4言語bundleの衝突手順。** 新規keyは必ずファイル末尾へ追加する。conflictは常に
   「両方残す」で解決し、**解決後に必ず `mvn test -Dtest=MessageBundleConsistencyTest` を実行**する。
   S06との実衝突が既に1回発生している。
3. **毎日 `git fetch origin main` してrebase。** mainは動く（本計画立案中の1セッションで
   `ce1ccd4` → `f582f9e` まで進んだ）。
4. **S02〜S17のtasks.mdのcheckboxを変更しない。** 本トラックの進捗は本書に記録する。
5. **禁触ファイル**: `SecurityConfig.java` / `BaseEntity.java` / `ContractServiceImpl.java` /
   `InvoiceServiceImpl.java` / `WorkRecordServiceImpl.java` / `m_menu`関連migration /
   `layout/sidebar.html` / `layout/base.html`。
6. **test方針**: 通常taskはL0〜L3（定向test＋直接回帰）。全量は各トラックの最終PRで1回。
   `test-execution-policy-s03-s17.md` に準じる。

---

## 8. 開工対話

各トラックにつき1対話・1担当へ渡す。文面はそのままコピーして使用する。

### 8.1 共通ヘッダ（全トラックの先頭に付ける）

```text
あなたはSES Manager Proの並行トラック担当です。実行基線は次の文書です。
- .kiro/audits/2026-08-01-attendance-parallel-track-plan.md（本トラックの正本）
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md §1
- .kiro/specs/customer-product-expansion-2026/test-execution-policy-s03-s17.md

最初に必ず本計画書 §1 の検証手順を実行し、§2 の表を実測値と突き合わせて報告してください。
.kiro/ 配下は必ず `git show origin/main:<path>` で読み、作業treeの同ファイルを読まないでください
（作業branchの .kiro は分岐時点で凍結されており古い）。

検証で矛盾を見つけたら、着手せず矛盾内容を報告して停止してください。
本計画書 §5 の開始条件を1つでも満たさない場合も着手しないでください。
作業中に §6 の停止条件へ該当したら、その時点で停止し報告してください。

migrationファイル（src/main/resources/db/migration/V*.sql）を新規作成してはいけません。
DDLが必要だと判断した時点で、それはトラックCの作業なので停止して報告してください。
```

### 8.2 A1 — マイ勤怠UI補完

```text
【共通ヘッダを先頭に貼る】

担当: トラックA1「マイ勤怠UIの情報補完」

目的: 要員が /my/timesheet を開いたとき、提出期限・当月合計・承認状況が一目で分かるようにする。
現状 templates/my-timesheet/index.html は27行で、対象月の選択と契約一覧しか無い。

触ってよいファイル:
- src/main/resources/templates/my-timesheet/index.html
- src/main/resources/static/js/modules/my-timesheet.js
- src/main/resources/messages{,_en,_ko,_zh_CN}.properties（末尾へ追加）
- 対応するtest

触ってはいけないファイル: Javaのservice/controller全般、WorkRecordServiceImpl、
SecurityConfig、m_menu関連、layout配下、migration。

実装ガイダンス:
- 既存の描画関数 renderMy() の構造と data-* 属性方式を踏襲する（インラインhandlerへ値を埋めない）。
- 状態表示は既存の workRecord.status.* キーを再利用し、新しい状態名を発明しない。
- 4bundleへ同じkeyを揃え、他言語へ日本語をコピーしない（必ず翻訳する）。

テスト要件: L1〜L2。MessageBundleConsistencyTest、StaticAssetLocalityTest、
MobileResponsiveLayoutTest、および node --check による構文確認。390px幅の表示崩れが無いこと。

Demo: 要員アカウントでログインし、提出期限・当月合計・承認状況が表示されることを確認する。
```

### 8.3 A2 — 勤怠未提出リマインド

```text
【共通ヘッダを先頭に貼る】

担当: トラックA2「勤怠未提出リマインド」

目的: 対象月の勤怠を提出していない要員へ通知する。現在 NotificationGenerateService.generateAll()
には8つのgeneratorがあるが、勤怠系が1つも無い。

触ってよいファイル:
- src/main/java/com/ses/service/NotificationGenerateService.java（generatorメソッドを1つ追加）
- src/main/resources/messages{,_en,_ko,_zh_CN}.properties（末尾へ追加）
- 対応するtest

触ってはいけないファイル: WorkRecordServiceImpl、新しいscheduler、migration、m_menu。

重要な設計制約:
- **新しいschedulerを作らない。** 既存の NotificationScheduler が日次で generateAll() を呼ぶので、
  そこへgeneratorを1本足すだけにする。
- **migrationを作らない。** 閾値は systemConfigService.getInt("<key>", <既定値>) の形で
  コード既定値を持たせる。既存の cashflow.alert-months はseed行を持たずに動いており、
  この方式が確立済みである。m_system_config へのseedは本タスクに含めない。
- 冪等性: 既存generatorと同じく dedupe_key を必ず付ける（例: 同日二重発行を防ぐ）。
- 宛先: 締め日前は本人（NotificationLinks.MY_TIMESHEET）。要員↔アカウント紐付けが無い場合は
  全体配信へフォールバックせず、warnログに留める（他要員への漏洩防止。既存 reject 通知と同じ方針）。

テスト要件: L1〜L2。generatorの定向test（対象抽出、dedupe_keyの冪等、未紐付け時に配信しないこと）
＋ MessageBundleConsistencyTest。

Demo: 未提出の要員が居る状態で generateAll() を実行し、本人にだけ通知が出ること、
同日2回実行しても通知が増えないことを確認する。
```

### 8.4 A3 — 承認滞留の可視化（読み取り専用）

```text
【共通ヘッダを先頭に貼る】

担当: トラックA3「承認滞留の可視化」

目的: 承認側の /work-record 画面で、提出済み件数と未承認の滞留日数が見えるようにする。

**この課題は意図的に読み取り専用へ縮小されている。** 当初案の「一括承認」は
WorkRecordApiController / WorkRecordServiceImpl を変更するが、dependency-matrix.md §2 は
同ファイルの争用者を order(S09) と attendance(S11) と明記し「orderを先行」と定めている。
S09 には「勤怠確定→検収差戻し→再提出→検収済」があり勤怠状態機械の境界へ触れる。
したがって本タスクでは**書き込み操作を一切追加しない**。一括承認は order(S09) PASS後に行う。

触ってよいファイル:
- src/main/resources/static/js/modules/work-record.js
- src/main/resources/templates/work-record/list.html
- 読み取り専用の集計が必要な場合のみ、既存controllerへGETメソッドを1つ追加
- messages×4（末尾へ追加）

触ってはいけないファイル: WorkRecordServiceImpl（1行も変更しない）、状態遷移まわり、migration。

停止条件（本タスク固有）: 既存の状態遷移メソッド（submit/approve/reject/confirmMonth/reopenMonth）
に変更が必要だと判断したら、その時点で停止して報告してください。

テスト要件: L1〜L2。集計の定向test＋既存 WorkRecordServiceImplTest の回帰が無傷であること。

Demo: 提出済みの勤怠がある状態で /work-record を開き、件数と滞留日数が出ること。
承認・差戻しの既存動作が変わっていないこと。
```

### 8.5 B1 — source matrixと36協定の棚卸し

```text
【共通ヘッダを先頭に貼る】

担当: トラックB1「source matrixと法人別36協定の棚卸し」

出所: attendance-leave-overtime-compliance の task 0（T067）。
**production codeを1行も変更しないタスク**であり、成果物は文書のみです。

事前に必ず読む:
- git show origin/main:.kiro/specs/attendance-leave-overtime-compliance/requirements.md
- git show origin/main:.kiro/specs/attendance-leave-overtime-compliance/design.md
- git show origin/main:.kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md

目的: 雇用勤怠の正が本システムであることをsource matrixとして確定し、法人ごとの36協定の
実内容（特別条項の有無、上限、起算日、法定休日の曜日）と適用除外者を棚卸しする。
F1が m_overtime_agreement へ登録すべき行を、推測せずに決められる状態にする。

成果物: source of truth matrix、勤務区分、カレンダー（法定休日の曜日）、休暇種別、
法人別の36協定内容一覧、適用除外者（管理監督者）の一覧。

重要な境界:
- t_work_record_daily（客先請求工数）と雇用勤怠が**別sourceである**ことを明文化する。
  前者は請求のためのものであり、雇用上の勤怠の正ではない。
- **時間外の計算ルール自体は overtime-rules.md で確定済みなので決め直さない。**
  本タスクが集めるのは、その確定ルールへ流し込む法人別のデータである。
- 協定書が未入手の法人は「未入手」と記録する。協定行が無い法人は判定不能としてfindingになる
  （既定値で「適合」にしてはいけない）。

テスト要件: L0。法人ごとに協定の有無と入手状況が記録されていること、法定休日の曜日が
全法人分そろっているか未確認と明記されていること、source matrixが既存work recordとの境界を
含むこと、git diff --check exit 0。
```

### 8.6 B2 — 時間外calculatorと境界fixture

```text
【共通ヘッダを先頭に貼る】

担当: トラックB2「OvertimeComplianceCalculatorと境界fixture」

出所: attendance-leave-overtime-compliance の F2 前半。
**中央台帳への登録が必要なタスクです。** 未登録なら着手前に統合担当へ登録を依頼してください。

事前に必ず読む（overtime-rules.md が値の唯一の正本）:
- git show origin/main:.kiro/specs/attendance-leave-overtime-compliance/overtime-rules.md
- git show origin/main:.kiro/specs/attendance-leave-overtime-compliance/design.md（特に §5.2）

目的: overtime-rules.md §1 の6ルールを計算するcalculatorと、その境界fixtureを完成させる。

**本タスクでやること**:
- 新規クラス OvertimeComplianceCalculator を作る。**1メソッド1ルール**で書く
  （条件を複数箇所へ散らすと overtime-rules.md §4.3 の値変更が漏れる）。
- 閾値をコードへ直書きしない。解決順は m_overtime_agreement → m_system_config → コード定数。
  **本タスクでは第2段(m_system_config)と第3段(定数)だけを実装し、第1段はinterfaceのまま残す。**
  第1段はF1のDDL(V78)が入った後に接続する。
- 「休日労働を含むか」の分岐は、calculatorへ渡す入力の選択1箇所に閉じる。ルール内へ条件を持ち込まない。
- 境界fixtureを src/test/resources/fixtures/overtime/ へJSONで置く。
  fixtureはconfig値を読んで limit±1 を生成し、境界の向きだけ明示的に書く。

**本タスクでやらないこと**（すべてF1=トラックC）:
- migration、DDL、m_overtime_agreement の実装
- 画面
- WorkRecordServiceImpl への接続

最重要の境界: **ルール4（月100時間）だけが `>=` 判定**である。「100時間未満」なので100時間
ちょうどが違反、他のルールは「以内」なので上限ちょうどは適合。limit-1 / limit / limit+1 の
3点fixtureで必ず固定すること。

テスト要件: L1〜L3。overtime-rules.md §5 の推奨fixture最小セット全件。特に
ルール4がlimitちょうどで違反・他は適合、休日労働のみ超過時のルール1/2/3適合とルール4/5違反、
所定休日労働がルール1へ算入されること、n月分データ不足時のskip（0扱いにしない）、
協定年度またぎ（ルール2/3/6はリセット、ルール5はまたぐ）、適用除外者の全ルール非判定。

Demo: fixture結果を提示し、45h/360h/80h平均それぞれの境界値ちょうどで判定が変わることを確認する。
```

---

## 9. 進捗記録

本トラックの進捗は本書に記録する。S02〜S17のtasks.mdは変更しない。

| ID | 状態 | branch / PR | 備考 |
|---|---|---|---|
| 0-1 / 0-1b/c/d | **完了** | PR #47 | 紐付け導線の是正。詳細は `2026-07-31-attendance-gap-analysis-and-plan.md` |
| A1 | 未着手 | — | |
| A2 | 未着手 | — | |
| A3 | 未着手 | — | |
| B1 | 未着手 | — | 台帳登録不要（文書のみ） |
| B2 | 未着手 | — | **台帳登録が前提** |
