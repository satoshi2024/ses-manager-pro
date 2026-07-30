# Implementation Plan — 法定文書台帳・電子保存

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T021〜T026はL0〜L3の定向test・直接回帰、T027でL4全量を実行する。
> file/storage/schema等の共有境界変更は方針の昇格条件に従う。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> どちらにも無い論点が出たら、推測実装せずspecを具体化する。
>
> **Migration**: 本specの予約番号は **V67**。着手時にmerge済み`db/migration`の最新を再確認し、
> 衝突していれば後発（本spec）を上へ繰り上げる。前の欠番を埋めない。V59は永久欠番。

- [x] 0. G2法務確認と既存file inventory
  - **Objective**: 文書種別ごとの保存年数・起算日・法的hold可否が表として確定し、既存fileの所在・参照元・件数・容量が
    棚卸しされている。以降のtaskが「どの文書をarchiveへ移すか」を推測せずに決められる状態にする。
  - **成果物**: 文書種別、起算日、保存年、法的hold、既存path/参照元/件数/容量。
  - **Demo**: 公式URL/版/確認日付きprovisional mappingと社内コンプライアンス責任者の確認記録。外部専門家承認はM/本番gateへ記録。
  - **実装ガイダンス**: production codeを変更しない。`FileReferenceProvider`実装を全て列挙し、
    どのfileが法定/取引文書でどれが写真等かを分類する。分類不能なものは「未分類」として残し、勝手に決めない。
  - **テスト要件**: L0。inventoryの件数がFileReferenceProvider実装数と一致すること、
    保存年数の根拠URLが全種別に付いていること、`git diff --check` exit 0。

- [ ] F1. 文書DDLとDocumentService (Round 2 FIX中)
  - **Objective**: 受領したPDFを登録すると、文書種別・相手先・取引日・金額・SHA-256が台帳に記録され、
    同じ操作を再実行しても2件目が作られない。原本確定後は通常UIから上書き・物理削除ができない。
  - **実装ガイダンス**: **V67**/V1/H2(`sql/schema-document-archive-h2.sql`)/MySQL smoke、version/link/access/disposal。
    冪等キーは`(tenant_id, source_type, business_key, version_discriminator)`のUNIQUE（design §6.3）。
    `counterparty_name_snapshot`は登録時に固定し、顧客/BP名称変更で過去文書の表示を変えない。
  - **テスト要件**: L1〜L3。hash算出、version append-only、冪等（同一sourceの2回登録で1件）、
    legal hold中の廃棄拒否、`version`楽観ロック競合、`retention_until IS NULL`が廃棄候補に**入らない**こと。
  - **Demo**: 受領PDFを登録しmetadataとhash表示。同じPDFをもう一度登録して件数が増えないことを確認。

- [ ] F2. Storage adapterとstream download (Round 2 FIX中)
  - **Objective**: 業務コードがstorage pathを直接扱わずにfileを保存・取得でき、local/S3を設定だけで切り替えられる。
    scan未完了・未知のfileはdownloadできない。大きいfileでもheapが増えない。
  - **実装ガイダンス**: local/S3 interface、quarantine→scan→hash→DB tx→promoteの順（design §2）、
    orphan cleanup、fail-closed。**`FileReferenceProvider`と`FileScopeValidationService`の両方へ登録する**
    （platform-invariants §2.5。どちらも忘れた場合の既定が危険側）。
  - **テスト要件**: L1〜L3。large file固定heap、scan失敗時fail-closed、DB失敗時のstorage補償、
    組織A→B のdownload拒否、未登録storage keyのdownload拒否。
  - **Demo**: local/S3 fakeを設定切替して同じAPIで取得。scan未完了fileが403になることを確認。

- [x] A1. 台帳検索/詳細/version UI
  - **Objective**: 税務調査を想定して、日付・金額範囲・相手先の3条件で文書を絞り込み、
    その文書の旧版と関連業務画面へ辿れる。権限のない文書は件数にも現れない。
  - **実装ガイダンス**: 日付/金額/相手先/種別index、関連業務link、履歴。
    母集団は`t_document_link`先の業務entity scopeから導出する（design §6.2）。document固有ACLを作らない。
    link先が複数ある文書は**和集合**で可視。
  - **テスト要件**: L1〜L3。filter組合せ、営業A/営業Bの母集団分離（**件数も0件**）、mobile 390px、
    金額範囲の境界（min/max inclusive）。
  - **Demo**: 3条件検索→文書→旧版→業務画面。営業Bでログインし同じ文書が0件になることを確認。

- [x] B1. 既存帳票/CloudSign統合
  - **Objective**: 見積・契約・作業報告・請求のPDF生成と署名済PDF同期が、生成のたびに台帳へ版として記録される。
    再生成・再同期しても文書が重複しない。既存のPDF機能は戻り値が変わらず動き続ける。
  - **実装ガイダンス**: 既存PDF serviceの戻り値を壊さず、呼出側でdocument登録するadapterから段階移行（design §3）。
    CloudSignは署名済PDFと合意締結証明書を**別document type/version**として保存し、外部document IDを記録。
  - **テスト要件**: L2〜L3。再生成/再同期で重複0件、既存PDF機能の回帰、外部document IDのUNIQUE。
  - **Demo**: 契約生成→署名同期→2文書版を確認。再同期を2回実行して版が増えないことを確認。

- [x] B2. 税務export/retention/disposal
  - **Objective**: 検索結果をZIP+manifestでexportでき、manifestのhashで原本の同一性を再検証できる。
    保存期限切れ文書は候補→承認→廃棄の順でのみ削除でき、legal hold中は廃棄が拒否される。
  - **実装ガイダンス**: 非同期ZIP+manifest（UTF-8 CSV）、候補→承認→廃棄、legal hold。
    廃棄承認は**管理者固定**（design §6.2の逸脱）。ZIP jobの母集団はjob作成時のrequesterで固定。
  - **テスト要件**: L2〜L3。ZIP hash再計算の一致、件数上限、単独管理者の即時物理削除が不可であること、
    legal hold中の廃棄拒否、storage delete失敗時の廃棄証跡、`retention_until IS NULL`が候補に入らないこと。
  - **Demo**: 検索結果exportと廃棄訓練。legal holdを立てた文書が候補から消えることを確認。

- [x] M. 移行/回帰/復元
  - **Objective**: 既存添付ファイルが過不足なく `t_document` へ一括登録され、全機能が動作する。件数とhashがinventoryと一致する。
    DB+storageを隔離環境へ復元して文書が表示でき、backupの同一時点整合が確認できる。
  - **実装ガイダンス**: 移行はcopy→hash検証→参照切替→旧file保留の順（design §5）。**即削除しない**。
    法定/取引文書だけをarchiveへ移し、写真等は共通storage adapterのみ利用。
  - **テスト要件**: L4。inventory件数/hash一致、`mvn test`全量、fresh/legacy MySQL smoke、
    Node/JS syntax、backup整合、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: DB+storageを隔離環境へ復元し文書表示。移行前後の件数とhashが一致することを提示。
