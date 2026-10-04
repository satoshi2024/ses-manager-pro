# S-NF01〜10 Production Readiness 実行台帳

## 実行権限と判定境界

- 依頼者は2026-10-01の対話で、総括agentによる実行統括、実装に`gpt-6-luna`、独立Reviewに`gpt-6-astra`の使用を指示した。
- 依頼者は技術方式・優先順位の選定を総括agentへ委任した。これは法的判断、外部契約、実在する責任者の署名、production接続・credential使用・deployの代理承認ではない。
- 開発・テスト用の実装と修復、分離branch、既存worktreeの安全な再利用、ローカルcommitを進める。外部公開・push/PR/merge・production操作は別途実行権限を確認する。
- 各featureの既存承認記録は履歴として保持する。新しい技術実行の委任を過去のDG/Privacy/Legal承認へ読み替えない。
- `IMPLEMENTATION_PASS`、`REVIEW_PASS`、`PRODUCTION_APPROVED`、`ENABLED`を独立した状態として管理する。NF06/NF07をOFFのまま全機能完了にしない。

## 固定された事実

| 項目 | 証跡 |
|---|---|
| 再fetchした初期base | `1443fb68943849a3260d7ebe005259f3eb609d60` |
| PR101 Head | `8ad0c34eacbb04a031667934f313a2b6965583f1`。baseとのtree差分なし、baseに全commit包含 |
| PR101 CI | run `36857893770`、7 jobs SUCCESS |
| main CI | run `36858538460`、6 SUCCESS / Browser FAILURE |
| Browser失敗 | `AccountingIntegrationBrowserDemoTest:349`、account linkのengineer_id unique競合。14/0/1/0。seed `101246425020` |
| migration | **181 versioned scripts + 1 repeatable**、最大V182、正規化version重複0。旧157件は24件の小数相当version（`V102_1`等）を除外した誤集計 |
| 主checkout | `C:\work\ses-manager-pro`。`application-dev.yml`、`CustomUserDetailsService.java`、`LoginUser.java`の利用者変更あり（後2件は実行中に追加を観測）。変更・reset・cleanしない |
| 最終R3証跡 | `C:\work\ses-snf-r3-remediation-b2-b`を保存。試験結果の混合集計を新runの証拠にしない |

## 分離と現在の担当

全初期branchのbaseは上記SHA。依存変更を取り込む際はactual base/headを追記する。

| Batch | 担当model | branch / worktree | 状態 |
|---|---|---|---|
| F0 統括・決定包 | root | `codex/snf-prod-control` / `C:\work\ses-snf-status-audit` | IN_PROGRESS |
| C0 Browser fixture | gpt-6-luna | `codex/snf-prod-browser-isolation` / `C:\work\ses-nf02-remediation` | `0d78cc13` Astra CODE PASS、control `778e372d`へ統合。main CI再実行なし |
| Q0 Fast fixture | gpt-6-luna / browser_fix | `codex/snf-prod-fast-isolation` / `C:\work\ses-nf02-remediation` | `b1775812` Astra限定CODE PASS、control `198ee8e5`へ統合。2 classの両実行順序・最終態で各12/0/0/0。第3回全fastで旧5 errorは非再現。全体は別Portal failureでFAIL |
| R09 provider境界 | gpt-6-luna | `codex/snf-prod-nf09-provider-boundary` / `C:\work\ses-nf09-system-actor-attribution` | 最終`da850b97`、profile4＋実service反復2=6/0/0/0、Astra CODE PASS、control `036b3eed`へ統合 |
| R03 training修復 | gpt-6-luna | `codex/snf-prod-nf03-training` / `C:\work\ses-nf03-remediation` | 最終`89384928` Astra CODE PASS、MySQL4/0/0/0、control `626aa967`へ統合 |
| R10 reauth | gpt-6-luna / nf09_boundary | `codex/snf-prod-nf10-reauth` / `C:\work\ses-nf10-remediation` | 最終`20cce0f7` Astra CODE PASS、単一targeted 46/0/0/0、control `cc6510d5`へ統合。全NF10/stagingは未完 |
| R02/R03/R10 baseline Review | gpt-6-astra | R3最終証跡worktreeをread-only参照 | 静的Review完了、P1計4件。専用report参照 |
| R04 baseline Review | gpt-6-astra | R3最終証跡worktreeをread-only参照 | 静的Review完了、P1×3/P2×1。専用report参照 |
| N06/N07技術Plan Review | gpt-6-astra | NF06 `c2a95cd5` / NF07 `f007ef9a` | 両方synthetic技術PLAN PASS。実装・実環境・人の承認とは別。専用report参照 |
| R02 scope | gpt-6-luna / nf09_boundary | `codex/snf-prod-nf02-scope` / `C:\work\ses-manager-pro-nf02-nf03-review-v2` | `36dcfdfc` Astra限定CODE PASS、両P1閉鎖。4 class 31件成功と最終integration 1件成功は別run。controlへ統合、全NF02/production承認ではない |
| R04 offline | gpt-6-luna / browser_fix | `codex/snf-prod-nf04-offline` / `C:\work\ses-review-snf-r3-a` | 7 source WIPは保存・一時停止。Astra CODE FAIL（撤回/DOM/時計）。追加G1/G2技術Plan d9d53112限定PASS、Owner/Securityの要件差分承認は未取得 |
| N06 contracts | gpt-6-luna / nf06_contracts | `codex/snf-prod-nf06-contracts` / `C:\work\ses-review-snf-r3-b` | F2最終`1e8aa651` foundation限定CODE PASS、17/0/0/0、control `5b2e5db3`へ統合。実resolver/mapping/apply/UI/DDL未実装 |
| N07 foundation | gpt-6-luna / browser_fix | `codex/snf-prod-nf07-foundation-plan` / `C:\work\ses-review-snf-r3-c` | SF-W1 `6ec4ca28`限定CODE PASS、18/0/0/0。SF-W2 Plan `e5530c01`追加PLAN PASS、control `55fa2b5b`へ統合。W2a JDBC synthetic journal実装中、pure Boundary/Inventory 3/0/0/0。実MySQLとCODE Review未完。hold release/dispatch proof・W2b以降・人工gate未完 |
| E05/E08 baseline Review | gpt-6-astra | R3固定Head | 新規P2計4件、専用report参照。production BLOCKED |
| E05 deadline | gpt-6-luna / nf09_boundary | `codex/snf-prod-nf05-deadline` / `C:\work\ses-nf05-remediation` | 2187b5f9 Astra限定CODE PASS、最終targeted 26/0/0/0。control 540b27d9/acb8bf66へ統合。実transport/production gateは未完 |
| R01a lifecycle境界 | gpt-6-luna | `codex/snf-prod-nf01-lifecycle` / `C:\work\ses-engineer-lifecycle-workflow` | `ced64304` CODE PASS、H2 26/0/0/0、MySQL3/0/0/0。追加fixture `e3944774` CODE PASS、5/0/0/0、control `e90daaf1`へ統合。DUAL等は未完 |

再利用したworktreeは開始時clean、origin/main未包含commit 0、当該path依存processなしを確認した。
既存の試験用`target`は、同一worktree内`target-preserved-20261001-prod-start`へ移動して保存した。
これらをstage、削除、上書きしない。`git add .`は禁止し、変更pathを列挙する。

## 残作業キュー

| 順序 | Batch | 開始・完了条件 |
|---|---|---|
| 1 | C0 | 原seed再現→原因修正→全Browser原seed/別seed→Astra固定Head Review |
| 1 | R09 | prod/mock混在のfail-closed、実provider未設定時の誤確認防止→Astra Review。実provider完了とは別 |
| 2 | R02/R03/R04/R10 | 現行scopeの独立Review→finding修復→専用Demo→再Review |
| 2 | N06 Plan delta | 委任範囲で技術scopeを具体化し、Astra Plan Review。実source/production移行承認を分離 |
| 2 | P07 Policy/Plan | 現行coverage再生成、未確定policy・署名欄を明示。法的期間を推測しない |
| 3 | E05/E08/R09 real adapter | 既存統合と互換の技術案、prod設定契約、sandboxテスト。実credential/外部送信は別権限 |
| 3 | N06 implementation | approved technical planの全波をsynthetic環境で実装・検証し、実source未検証を残す |
| 3 | N07 implementation | policyを入力として受ける安全な基盤の技術Planを独立Review。未承認policyで実データを処分しない |
| 4 | M5/O6 | fresh/upgrade/V182 repair、staging、代表性restore、環境adapterとrunbook |
| 5 | Q6 | 固定RCでFast/MySQL/Performance/Browser/backup全gate、zero skipped、独立Review |
| 6 | G7 | 有効な人の署名・実環境証拠・停止/rollback条件が揃って初めてGo/No-Go提出 |

## 共通Gate

- local preflight: Java 21.0.12.1 / Maven 3.9.6 / Docker daemon / Node 24.19.0 / Chrome / Git Bashを確認済み。これは全suiteやproduction環境のPASSではない。
- NF07 mechanical scanのB0/V182時点: `BLOCKED_COVERAGE_INCOMPLETE` / exit 2。253 table、244 entity table、699 provider候補、未分類73 table、欠落column1077、欠落provider276。V183と後続実装統合後は再生成が必要。旧coverage PASSは現行Headに適用不可。
- 試験は一run一suiteでSHA、command、開始/終了、環境、test/failure/error/skipped、log/XML hashを保存する。
- Maven重負荷suiteは総括が直列化し、異なるagentのDocker/browser試験の干渉を避ける。
- migration番号、H2 schema/init、shared security/document/config、中央traceabilityは総括が調整する。
- 2026-10-01再fetchでorigin/mainが`1443fb68943849a3260d7ebe005259f3eb609d60`のまま、V183以降なしを再確認。
  **V183をNF01 R01aのtenant/月次案件採番tableに予約**した。担当browser_fixのみ追加可。
  現在の初期base inventory 181 versioned + 1 repeatableと、実装中の追加scriptを混同しない。
  必要なH2 schema/initとMySQL shardだけ同批で同期する。適用済みV1/V182等は変更しない。
- checksum改変、skip、固定順序による問題隠し、tenant default推測、mock成功へのproduction fallbackは禁止。
- push前に`scripts/verify-like-ci.ps1`。最終RCではFast/MySQL/Performance/real Chrome/backup、fresh/upgrade、PITR、安全・運用演習を要求する。
- `PENDING=0`だけでV182完了にしない。4表のscope未確定0、親子scope一致、callback一意性、実際の業務帰属・actorを確認する。
- 完了のための人工gate: Production Owner、Security、Privacy/Legal、Operations、provider契約/credential、production topology/representative profile、最終deploy権限。

### 現在の統合検証

- 再fetch後origin/mainは引き続き`1443fb68943849a3260d7ebe005259f3eb609d60`。
  主checkoutの利用者変更3件を保存し、開発は隔離worktreeのみ。
- 現統合migrationは182 versioned + 1 repeatable = 183 scripts、最大V183。
  V184以降は未割当。上記B0 inventoryと区別する。
- 第1回統合fastは4021/0/12/0でFAIL。旧fixture5 errorとsocket環境7 errorを分類した。
  修復後fixture5/0/0/0、loopback4 class12/0/0/0は限定再検証の成功であり全fast PASSではない。
  raw証拠をrun別に保存した。詳細は`2026-10-01-snf-production-integrated-fast.md`。
- このhostのMaven/Java実行は当該processの`JAVA_TOOL_OPTIONS`に
  `-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`を指定する。既存の必要optionを維持し、global設定は変更しない。
- 第2回全fast（2026-10-02 JST）は固定`deb43f3c`で**4069/0/5/0、FAIL**。
  旧12 errorは再現せず、全5 errorはOvertimeComplianceWiringTestのaccount link unique衝突。
  保存済み665 class XMLをrun別集計した。Q0修復・Astra・全再実行を要求する。

## 現在の総合判定

**NO-GO / ACTIVE_REMEDIATION**。独立Review、NF06/NF07実装、実provider、policy、production環境の証拠は未完了。
この台帳の作成はproduction approvalではない。

## 2026-10-02 01:40 JST 再統括

- 第3回全fast fixed `c9bd265555f2197784f2728a50ed7468bab1f853` は **4075/1/0/0、FAIL**。
  667 class XML、seed `48992185070100`。証拠は `verification-evidence/20261002-control-fast-03/`。
  Q0の5 errorは消失した。残るPortalBpApiTestの403は、Astra静的診断では共有H2に
  ExpenseRequestFlowIntegrationTestが残す法人70001が強い候補。OneOnOneの70002も候補である。
  元例外と順序を伴う動的再現前に根因確定とはしない。製品の法人一意性guardは変更しない。
- Q1作業先は `codex/snf-prod-portal-isolation` / `C:\work\ses-nf02-remediation`。
  fresh origin/main B0から審査済みcontrol `e8c685a6c523384711803d7ae273c57455c257aa`までfast-forward。
  Q0 branchと旧targetは保持し、今回の開始前targetを `target-preserved-20261002-portal-start`へ保存した。
- NF02両P1はsource `36dcfdfc`でAstra限定CODE PASS、control `1c0df340`へ統合済み。
  全NF02のDG/Owner/release承認へ格上げしない。
- NF04追加技術Plan `d9d53112`はAstra限定PLAN PASS。後続説明 `e754d26d`もcontrolへ統合した。
  source WIPはCODE FAILのままである。fresh password reauth＋明示restoreをG1/G2で実装する。
  全永続媒体故障時の即時物理削除や通知不能tabの即時撤回は保証不能であり、人の残余risk判断が必要。
- nf06_contracts agentはthread上限により再開できなかった。既存Luna browser_fixをNF07 W2aへ再配分した。
  NF04 sourceを上書きせず保存し、重いMaven/MySQL/Browserは引き続き総括が直列化する。
- production approval、remote push/PR/merge、実credential、deployは実施していない。
## 2026-10-02 午前の再開とNF06採番

- 再fetch後origin/mainは`1443fb68943849a3260d7ebe005259f3eb609d60`。主checkoutの利用者変更3件を保持。
  停止済みagentを再起動せず、Luna `portal_isolation_r2` / `privacy_journal_r2` とAstra `astra_gate_r2`へ
  現存worktree・未commit差分・失敗証拠を引き継いだ。再開時に残存Java processはなかった。
- NF06 F1aは既存clean worktree `C:\work\ses-review-snf-r3-b`を再利用し、
  `codex/snf-prod-nf06-metadata`をfresh B0から作成、審査済みF2依存 `936890a7`へfast-forwardした。
  旧`codex/snf-prod-nf06-contracts`とtarget証拠は保存。追加計画取り込みHead `fc52da55`。
- F1a追加計画は独立PLAN CONDITIONAL。validationからregistry書込を分離し、exact source再parseによる
  集計再検算、空行gap、prefix/duplicate集合再構築、権限再確認の条件を追記した。
  次担当はDDL/constraint/transaction draftを先に作り、Astra確認後に実装する。
- 2026-10-02 09:54 JST時点の全refのpath履歴と全登録worktreeのmigrationディレクトリを確認し、
  `V184*`は0件だった。**V184をNF06 F1a metadata/identity基盤へ専有予約する。**
  作成予定は`V184__create_import_metadata_foundation.sql`。他batchは使用しない。
  V183はNF01専有のまま、V185以降は未割当。reservationはproduction migration承認ではない。
- feature単体のB0→V184検証と、V183も含むcontrol統合後のfresh/upgrade gateを区別する。
  新DDLは適用済みV1〜V183を変更せず、H2専用schema/shard/必要なinit参照を同期する。
### Q1 統合と午前CI再確認

Q1 source `32dc10ce`はAstra限定CODE PASS、control `98ac6172`へ統合した。
原403の根因は未確定。両逆順で23/0/0/0のfixture隔離証拠と全Fastの判定を分ける。
2026-10-02午前にGitHubを再照会し、PR101 run36857893770の7 jobs SUCCESS、
origin/main固定SHAの最新run36858538460はCOMPLETED/FAILUREのままであることを確認した。
ローカル修復をremote CI成功として扱わない。remote操作は行っていない。

### NF07 synthetic journalの限定統合と第4回Fastの準備

- Luna source `da44b267` / `504349ea7e5bd8a85f8313b53c13044d662d317e`を
  control `7c66eb11` / `dddf622a`へ統合した。Astra固定Head限定CODE PASS、未解消P0/P1/P2=0/0/0。
- run13 MySQLは25/0/0/0、run14 boundary/inventoryは4/0/0/0。P1両順序の実writer PRIMARY待機と、
  takeover後の旧generation/owner拒否を確認した。後者は新revision=2と未失効の新leaseを使い、CAS/expiry先行拒否と区別する。
- 原raw証拠74 fileをsourceとcontrol双方の`verification-evidence/nf07-w2a/`へbyte同一保存し、全SHA-256一致を確認。
  tracked manifestは74 entryとheader 1行。原logの空白を変更せず、rawはignored、manifest/packetはtrackedとした。
  run07の古いXMLコピーはstaleとして保存し、新runへ混算しない。整branch diff checkはPASS。
- これはsynthetic journalの限定合格である。hold解除authority/SoD、実dispatch proof、W2b/W3、production接続/DDL、
  NF07全体、Privacy/Legal承認は未完。実MySQL version/image digestは今回未収集で、最終RC rehearsalの追加条件とする。
- 第4回FastはQ1、NF05 deadline、上記NF07を含む固定control Headで原seed `48992185070100`を再実行する。
  開始/終了SHA、clean状態、run別XML/log/hashとzero skippedを確認し、終了までcontrolのtracked変更を停止する。
  同時にLunaはNF06 schema修訂とNF04 G1 server codeを別worktreeで進めるが、Maven/Chromeは起動しない。

## 2026-10-04 再開・保存証拠の確定

- 完全なAGENTS.md再読とfetchを実施。origin/mainはB0 `1443fb68943849a3260d7ebe005259f3eb609d60`のまま。
  mainの利用者変更3件（CustomUserDetailsService/LoginUser/application-dev.yml）を保持する。
- fixed control `3d9e3a8790cca94409719ca0a44a303809cf8efe`の第4回Fastは4088/0/0/0、PASS。
  全MySQL第1回は190/7/0/0、FAIL。開始/終了Head一致・clean、保存manifest全件一致を再確認。
  詳細とSHA-256はintegrated-fast記録へ追記した。中断前のRUNNINGを成功扱いせず最終結果を取得した。
- Q2/Q3は既存clean worktree `C:\work\ses-nf02-remediation`を再利用。
  branch `codex/snf-prod-migration-latest-q2`、baseは上記審査済みcontrol固定Head。
  `910a4c78`（latest183）、`ecfc99b9`（子JVM protocol）、`557b74a4`（起動失敗cleanup）。
  Astra静的待修findingなし。5 class再検証第1回はDocker不在で20件全skip、Gate FAIL。
- NF04 G1 `c6feef5f`に対し、test method名のcompile阻害、confirmの最終期限/nonce generation不足を確認。
  Luna修復`15252684`を独立Reviewへ渡した。旧run03の18/0/0/0は最新修復の証拠ではない。
  7つのG2 WIPと旧exitcode証拠を保持し、G1以外をstageしない。
- NF06 V184 schema `3b6f1ae`は静的限定PASS。slice `7a4cdb1`は@Transactional final classのP1を修復中。
  candidate exact source membership追加案`08baeda7`は審査待ち。任意caller keyを信頼して登録しない。
- 前回Astraはusage limitで中断したため未了ReviewをPASSにせず、新しいAstraで固定SHA審査を再開した。
  Lunaは実装、Astraは独立Reviewを担当し、重い実行窓は総括が割り当てる。
- 総合判定はNO-GO。remote push/PR/merge、実credential、production deploy/代理署名は未実施。

### Q2/Q3統合・G1のclean検証

- Q2/Q3 fixed557b74a4は5 class 20/0/0/0、Astra限定CODE PASSとなり、control `b978fbac`まで統合した。
  全MySQLの再実行完了とは区別する。2026-10-04の再照会でもPR101は7 SUCCESS、mainの最新run36858538460はFAILURE。
- NF04 G1はnonce index無界増加とbarrier誤合格を`9af23bbf`で修復し、Astra静的待修findingなし。
  cleanな既存`C:\work\ses-review-snf-r3-c`を再利用し、fresh B0から`codex/snf-prod-nf04-g1-verify`を作り9af23bbfへfast-forward。
  旧NF07 branchは保持し、targetは同worktreeの`verification-evidence/nf04-g1-fixed/prior-nf07-target`へ原状保存。
  G2 WIPを含む元r3-aは変更しない。
- G1 clean 5 class run01はfixed9af23bbf、UTC07:54:23.8338725〜07:56:10.4788195（2026-10-04）、clean start/end。
  **25/0/1/0、FAIL**。H2の`TIMESTAMPADD(MINUTE, ?, ?)`型推論でREQUIRES_NEW failure-counter testが失敗。
  原log SHA-256 `8000018E85A795BC6CDB21459422EA39B096C6979E4732C45AC07651548025AE`。
  証拠はr3-cの`verification-evidence/nf04-g1-fixed/20261004-run-01/`。
- failure-counterは既存共用SQLのMySQL更新順序も含めて調査し、実MySQLで第1〜4回/第5回lock/追加失敗/期限切れ/並行加算を確認する。
  H2の並行性をMySQL証拠へ転用せず、H2では実transaction/rollback境界を検証する。現在G1 CODE PASSは未授与。
- NF06 proxy/constructor修復`ed14ba95`、candidate Plan条件修復`f9fbddeb`は静的指摘を閉鎖した。
  限定H2/cryptoは6/0/0/0だがWIPを含み原log/source manifest不足、固定commit合格として扱わない。
  generic file権限のIMPORT_JOB重複引用P2を追加修復中。candidate implementationとvalidation lifecycleは未完。

### 2026-10-04 G1限定統合・NF06 reader固定検証

- G1 source fixed `776dc5b03361431098875fd1ee81abe791dc0386`をAstraが限定CODE PASSと判定した。
  run03は7 class **34/0/0/0**、UTC08:14:08.1303747〜08:15:52.5360711、exit0、起止Head一致・clean。
  log SHA-256 `4D9467EDEB73D9735BF71B9634ECFC47757FFC7B7E2DB3B4E7FCA517A0E1F833`。
  mysql01はAccountLockServiceMySqlTest **4/0/0/0**、UTC08:23:33.3429714〜08:25:21.0485684、exit0、同じHead・clean。
  log SHA-256 `94DE7A39131364A12B13C03C03F5A8A4EC59D59788A5C97B5EB802EC7E2F4C61`。
  実containerのMySQL8.0.46、Docker29.7.2、image IDはruntime-observation.jsonへ現場取得した。
- G1原run01のH2 error、run02のcompile failureも保存した。run02はテスト未実行であり0件PASSではない。
  source/controlの `verification-evidence/nf04-g1-fixed/` に4 runの79 fileをbyte同一保存した。
  control統合commitは `f8012ac6 / 6e41b071 / 3cb8f092 / 347ee003 / 72dfb1bc / 6874511b`。
  proof/scope/CSRF MVC、独立failure transaction、account lockの直列・実MySQL並行意味に限定する。
  実servlet listener/logout race、実principal endpoint、Browser、分散session、実DB engine commit障害は未検証。
  既存onLoginSuccessの認証途中lock-reset競合は本変更由来ではなく、今回の合格範囲にも含めない。
- NF06固定 `1f3beb750db994abae92c180c0ac7acdd596f989` のrun02は3 class25/0/0/0。
  generic fileのIMPORT_JOB guardを全business shortcutの前へ移し、重複引用の迂回P2を閉鎖した。
  続く固定 `103c75876004dd0b072a39f1fff4aad00305b3cb` のrun03は7 class53/0/0/0、exit0、起止Head一致・clean。
  parser旧entry互換、canonical mapping、exact EOF/source hash、正規化key、redacted record、fingerprint固定vectorを含む。
  rawは `C:\work\ses-nf05-remediation\verification-evidence\nf06-fixed\`。candidate `0258d9fd` は独立Review/実行中。
  metadata lifecycle、全Spring wiring、V184実MySQLとV183併合rehearsal、apply/UIは未完。
- NF08 Plan `a91b5ee0`はAstra限定技術PLAN PASS。numeric `95bfb674` の静的findingは閉鎖したが実行証拠は未取得。
  snapshot/別attempt `0b0b10de`は独立Review中。bounded transportは小batchへ分けて実装する。
  OFFやunknown-cost拒否だけで実transportの実装完了にしない。
- 全体は引き続きNO-GO。上記限定統合後の全Fast/MySQL/Performance/Browser/backup Gateは未実行。
