# S-NF01〜10 Production Readiness 実行台帳

## 実測による追加修復（2026-10-16 15:19 JST）

- **NO-GO**。control固定 `172a363d99c8920fcb55c0ca4ac7d3384759602c` の残留fast fixtureは
  **3 class、11/0/0/0**（IntegrationHub3、撤権反復2、隔離監査6）。UTC06:12:48.7870050〜06:14:49.3788411。
  log SHA-256 `519B35CE56392ABDEA96ED07F8290AFE94F5E108D6387BB9FEB0B111727131F0`、6 report hash一致。
  これは定向gateで、全fast第6回FAILをPASSへ変更しない。
- NF06 F1b-1旧固定 `b766e3d1` のMySQLは **44/0/1/0、FAIL**。
  UTC06:08:52.9888783〜06:11:37.3274265、log
  `FC6EE4989B689BC6F380FB950C6FA352F4EDCEDCE8467F9D93AF870AEB801A7F`。
  旧completed fixtureのsource snapshotが実sourceと不一致だった。実source codeを取得する `ed433641` は
  Astra限定CODE PASS。production snapshot/ACL/fingerprint検証は保持した。
- 修復固定 `ed433641048c60d66cabbd2360d9dfdc72f09191` のunit/H2は **3 class、23/0/0/0**。
  UTC06:17:13.3349644〜06:18:16.1640785、log
  `69AB93847A5085A41E0B4CF9C5E85D6E5952E4401E37828019607A8AF63ADD56`。
  同SHAのMySQL再試験を実行中。wave2のWIPを含まない。
- NF08 A2固定 `1e64cde2` は限定CODE PASS後の実測で **19 class、105/0/3/0、FAIL**。
  UTC06:15:12.6505544〜06:17:00.8004449、log
  `8070C9F42B83CA59800C40D0C98030E90886ADFB30932EC0A115572F9A17340E`。
  3 upper-bound算術fixtureが有効usageを渡してUPPERを指定し、COST_MODE_MISMATCHとなった。
  guardを緩和せずfixture契約を修復する。A1成功とA2未受理を区別し、A2はcontrol未統合。
- NF06 wave2は限定PLAN PASS後の実装WIP。NF07 W3a固定計画 `5126952b` はAstra限定PLAN PASS、
  synthetic identity/scopeのas-of preview保存のみ実装開始。NF08 C2-B1はNOT_SENT/UNKNOWNの実DB
  metadata/transaction/CAS/tenant証明のみ限定PLAN PASS、実装WIP。全て未実測であり全機能完了ではない。

## 追加統合と固定検証（2026-10-16 15:12 JST）

- **NO-GO継続**。sourceの限定成功をcontrol全体の合格として扱わない。
- NF06認可競合修復の固定 `37c4c3d8` は実MySQL **39/0/0/0**、Astra限定証拠PASS。
  UTC05:58:35.0022713〜06:01:20.9209408、log SHA-256
  `20941A6054B5117DD97313F43B035516E3B0BE9854350AD3BAFC6BB8DD380856`。
  3計画+5修復commitをcontrol `370b8b59` まで統合。auth後期限再確認・四expiry caseを含む。
  JaCoCo class mismatchのためcoverage証拠には使用しない。
- NF08 C2-A1固定 `15907043`（実装 `5585a5a1`）は **18 class、96/0/0/0**、Astra限定証拠PASS。
  UTC06:01:45.0935031〜06:03:12.9580355、log
  `0933EEAF8D4A573670681C81235EC6D8ABFB5AE760F558CD724B54A015E52F62`。
  6commitをcontrol `7ff280bc` まで統合。inner失敗時のusage/dispatch保持をloopbackで確認した。
  A2固定 `1e64cde2` は費用と予算の分離、署名card digest binding、負例前提を修復し限定CODE PASS、未実測。
  A2はsynthetic receipt算術であり、実provider usage信頼経路・production実費・C2-B DB証明はない。
- NF07 D2b2固定 `ac894ac6` は三DB MySQL **35/0/0/0**、Astra限定証拠PASS。
  UTC06:03:47.8568879〜06:07:03.3878196、log
  `290576A0F38A424D1D948147344158EC29C913189CD654533B9B6F926B508C7E`。
  3container/MySQL8.0.46、schema hash、providerCallCount=0を照合した。JaCoCo mismatchはcoverageに使わない。
  `8fd3bcb0..ac894ac6` の10commit（先頭 `52a3ac82` を含む）をcontrol `9f8568bd` へ統合。
  control組合せ再実測、一般network/driver ACK-loss/完全ACLは未完。
- NF06 F1b-1固定 `b766e3d1` のunit/H2は **3 class、23/1/4/0、FAIL**。
  UTC06:07:23.3989887〜06:08:20.0958958、log
  `A6A4A5065016E5A126B12CB83A72CEAFF1DA1B53B0807D20918E4037D6B380B6`。
  LifecycleのH2 generated keyがIDとSTARTED_ATの複数列になり、getKeyが拒否した。
  明示id列取得 `18b22524` はAstra限定CODE PASS、行末微修 `b8baee0c` 後の固定再試験を要する。
  旧b766 MySQL実行中の結果を新修復の成功に流用しない。wave2は別WIPで未統合。
- raw source証拠をcontrolの `verification-evidence/20261016-nf06-candidate-lease-auth-source-01/`、
  `20261016-nf08-c2-a1-source-01/`、`20261016-nf07-d2b2-source-01/` へ各10/44/10file、hash一致で保存した。
  最新全fastは依然として第6回FAIL、残留fixture修復後の全fastは未実施。

## 最新到達点（2026-10-16 15:00 JST）

- **NO-GO**。control固定 `e09595c883afe62518281ac9a85f2343cbf716cb` の全fast第6回は
  **692 class、4210/2/1/0、FAIL**。UTC05:38:46.2440343〜05:53:52.0586530、起止SHA一致・clean、exit1。
  `verification-evidence/20261016-control-fast-06/`、1384 report hash一致、log SHA-256
  `0C2B7A2B2FAC7178BA5D025434749A929475A030867A49F4E51ADE8E31D981E8`。
  H2再初期化の連鎖errorは本runでは再現せず、IntegrationHubのreadiness前提2件と撤権poll状態1件が残った。
  AstraはFAIL証拠として受理した。Maven集計は4209件、XML集計は4210件であり、offboardingの反復実行
  Run 2: PASSを伴う集計口径差を保持する。両方とも2 failure/1 error/0 skippedで、PASSではない。
- H2修復source `fd628f6e` は定向8 class **27/0/0/0**、Astra限定CODE/証拠PASS。
  UTC05:31:43.3195066〜05:34:40.6311273、log
  `F1F000175900B9D0D2D7E2FD01FBEE3379336635779BF92FB22316145C68B400`。
  4 commitをcontrol `e09595c8` まで統合した。途中のcompile FAILも保存した。
  fast第6回の残留fixture修復 `f1d57af2` はAstra限定CODE PASS、control `f1ddc635` へ統合。
  UUID H2・実readiness前提・自fixtureのpoll到期を明示する。業務guardを緩和せず、修復後の実測は未完。
- control `736d25ac` のperformance **1/0/0/0、p95 64ms** はAstraが限定証拠PASSとした。
  全fast・容量・production性能の代替ではない。
- NF06 candidate/lease認可修復 `37c4c3d8` は限定CODE PASS、別検証treeで実MySQLを実行中。
  F1b計画 `16e9513d` は限定PLAN PASS、V184既存schemaでwave1を実装したが、`8cd94dc8` はCODE FAIL。
  takeover返却expiryとDB実値の不一致、期限切れ同operation retry、validator fingerprint確認、abort冪等testを修復する。
  append/seal/publish・apply/UIおよび全機能承認は未完。V185は未割当。
  後続 `b766e3d1` は四指摘を修復しAstra限定CODE PASS。H2/MySQL実行証拠は未取得。
- NF07 D2b1固定 `64db441d` は三DB MySQL **32/0/0/0**、Astra限定CODE/証拠PASS、未統合。
  UTC05:34:53.8217602〜05:38:31.9762492、log
  `2BD29423A034D14DEDF302FEAB70782E1CA16183391082A4A55F826E556873AE`。
  証拠をcontrolへhash一致で保存した。5秒work/2秒cleanupは選択fixtureのみで、一般network停止や全回復時間保証ではない。
  JaCoCo class mismatch警告がありcoverage証明に使用しない。D2b2 `ac894ac6` のwitness変化・owner交代3件は
  限定CODE PASS、35件の実MySQLを待つ。実retention/DSAR/処分・policy・外部承認は未完。
- NF08 C2-A1 `5585a5a1` は限定CODE PASS、固定検証tree `15907043` の18 class実測を待つ。
  A2の署名rate-card/実行binding/保守上限計画 `15907043` はAstra限定PLAN PASS、synthetic実装中。
  C2-B実DB費用matrixは未実装。実価格、DPA、外送、credential、feature flag承認を意味しない。
  A2実装 `e9bb6be9` は軽量javacのみで独立CODE Review中。実行時費用証拠や実料金承認ではない。
- 現在の検証再利用: NF06=`C:\work\ses-nf05-remediation` / `codex/snf-prod-nf06-verify`、
  NF08=`C:\work\ses-review-snf-r3-c` / `codex/snf-prod-nf08-c2-verify`、
  fast fixture=`C:\work\ses-nf02-remediation` / `codex/snf-prod-fast06-remediation`。
  旧branch・target証拠を保持し、主checkout利用者変更を変更していない。push/PR/deployは行っていない。

## 再開時点（2026-10-16）

- **NO-GO / 修復継続**。再fetchしたorigin/mainは `1443fb68943849a3260d7ebe005259f3eb609d60`。
  PR101はMERGED、head `8ad0c34e` の祖先判定exit0、全7 checkはCOMPLETED/SUCCESS。
  main最新run `36858538460` はCOMPLETED/FAILUREのまま。利用者のmain変更3件を保持した。
- control fixed `736d25acba5fd7d22343f5eb53d3c14bfd221711` のperformance profileは **1/0/0/0、p95 64ms、heapDelta 58KB**。
  UTC05:24:45.5325469〜05:26:15.9809383、起止SHA一致・clean、exit0。
  証拠: `verification-evidence/20261016-control-performance-01/`。log SHA-256
  `356E56D42A61D242AA9532649308CBA98CB2BC478E9AC8B549F7EA2D2E5DC187`、2 report hash一致。
  この1件の性能回帰成功は全fastの失敗やproduction性能・容量試験の代替ではない。
- H2修復candidate `a8045dea` の定向試験は **8 class、27/2/1/0、FAIL**。
  UTC2026-10-13T06:13:29.0946214Z〜06:15:42.4631753Z、起止SHA一致・clean、exit1。
  `verification-evidence/20261013-fast-v184-isolation-01/` の24 fileをhash一致で保存した。
  log SHA-256 `C392122B1911C6A123C6CA51F8D52604B5DAF08B75E846451005B1AD598E4C2C`。
  文書FK復元漏れ、V1先行DROPとimport FKの競合、Batch API 500が残る。
  後続 `718d4553` はFKを復元したが、新testがH2にないMySQL metadata列を参照するP1をAstraが検出した。
  IdentityContext単独DB化だけでは共通schema-locations再投入の修復を証明しない。実装Lunaが修復中。
- NF06 `4900a621` はcandidate/lease write認可gateの候補。実MySQLの期限・resource変更両順序matrixは追加実装中で、
  認可競合P1を閉鎖しない。NF07 `b3747262` はD2b1候補だが、retry owner期待値とserver timeout分類の二P2でAstra CODE FAIL。
  commit確認済み/不明の追加経路も未実証。両treeの中断WIPを保全してLunaが接続し、重負荷試験はrootが直列化する。
- NF08 C2-A1のusage分離は限定PLAN PASS。A2/Bはtrusted cardの構築境界、tokenの包含関係と丸め、
  実Spring commit後の独立connection読込を計画に明確化するまで未承認。実料金/外送/法務承認ではない。

以下は固定時点ごとの履歴であり、成功範囲・未解決事項を現在の全体PASSへ読み替えない。

## 最新到達点（2026-10-13 15:15 JST）

- **NO-GO**。最新統合 `02f6ace46775f61accc9b38c010cd595cc9351a0` の全fast第5回は **691 class、3980/4/1498/0、FAIL**。
  UTC05:59:41.8878464〜06:06:55.0276180、起止SHA一致、clean、exit1。
  証拠: `verification-evidence/20261013-control-fast-05/`、log SHA-256 `E3B3F5D34BB2A47131352356F6B1EC705254810E3E53DF648250BA08B2014E0F`。
  H2文書schemaの再構築時にV184外部キーがDROPを阻止する問題、H2の複合ADD CONSTRAINT構文、
  新規3クラスの非transactionalテスト隔離契約を調査・修復する。context連鎖エラーを1498個の独立製品不具合とは断定しない。
  `codex/snf-prod-fast-v184-isolation` / `C:\work\ses-review-snf-r3-c` を固定base `02f6ace4` から安全に再利用した。
  旧NF08検証branch・証跡を保持し、rootの全suite終了後に開始した。実装Luna、独立Review Astra。
- NF06 F1aはsource `1daff170` の実metadata競合 **23/0/0/0** をAstraが限定受理した。
  log SHA-256 `09322C5F79B5D30D9C63E7ED23296BEFC8405652D70A35ED0C93F46AA73503BE`。
  45 commitをcontrol `338637a77207b593f0ee58c9234aba4e1eff3df7` まで統合した。
  重複決定文書 `fc52da55` はcontrolに全文と後続決定が存在することを確認してskipした。Astraも内容包含を確認した。
  同controlのjoint MySQLは **4 class、40/0/0/0**（candidate6、metadata23、lease9、migration2）。
  UTC05:55:29.9954470〜05:58:55.1378422、log SHA-256 `01B1B7E7645717ACA830B1377B210073A2B6B4A213245DBBE1740B5E577DD77B`。
  Astraが8 report hash、起止SHA/clean、H2/shard追加、V183保持/V184追加を照合した。
  空庫とB0/V182 fixtureの双方でV183・V184適用、sentinel保存、Flyway validateを実証した。
  既存V183業務データ単独のupgrade証明ではない。現在の統合migration最大はV184、V185は未割当。
  candidate/leaseに別の認可待機競合P1が残る。READ-COMMITTEDの実service接続、auth後fresh DB time、
  retry/renew期限失効と通常takeoverの区別を含む計画 `abf84e8a` はAstra限定PLAN PASS、実装中。
  tenant全体の認可ロックによる競合・deadlockリスクP2、validation lifecycle/apply/UI未実装は継続する。
- NF08 C1修復source `3d95d3f57dd9a9a5da6e458b3fd07f8bf145f070` は **18 class、91/0/0/0**。
  UTC05:48:20.7208201〜05:49:45.9877677、log SHA-256 `68778BBCEB48329F68E1B94869ADDF816BD76C8D0E917DF3CC6F74257AD35E2D`。
  Astraが36 report hashと限定CODEを受理し、A/B/C1の35 commitをcontrol `02f6ace4` まで統合した。
  migration/config追加はない。C2の信頼できるACTUAL/rate-card、outer usageとinner失敗の分離、実DB費用matrixは未完。
  C2計画 `f7d7b4ca` は作成済み・独立Review前。production adapterはdeny-all、外送/credential/DPA承認はない。
- NF07 D2aの26件成功は未統合。D2b1 `dfd1d970` はdeadlineに結合したLease、DB待機上限、
  初回隔離取得のABORTED_UNKNOWN分類と3追加MySQLテストの候補を提出した。軽量javacのみ、Astra CODE Review中。
  実MySQL未実測、stable witness競合・no-replay owner交代・connect/socket stallは未完である。
- 2026-10-13 14:51 JSTの再fetchでもorigin/mainは `1443fb68943849a3260d7ebe005259f3eb609d60`。
  PR101の全7 checkはCOMPLETED/SUCCESS、head `8ad0c34e` はorigin/mainの祖先。
  最新main run `36858538460` はCOMPLETED/FAILUREのまま。ローカル修復をremote CI成功へ読み替えない。
  主checkoutの利用者変更3件を保持し、push/PR/remote merge/deploy/実credential操作は行っていない。

以下の時点別記録は履歴であり、古い最大migrationや「未統合」記載は上記最新到達点で更新される。

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

## 2026-10-08 再開・限定検証と次工程

- 根AGENTS全文を再読しfetchしたorigin/mainはB0 `1443fb68943849a3260d7ebe005259f3eb609d60`。
  PR101の7 jobはCOMPLETED/SUCCESS、main最新run36858538460はCOMPLETED/FAILUREのまま。
  worktree/branch/未commit/未push相当の差分一覧は`verification-evidence/20261008-resume-inventory/worktrees.json`。
  main checkoutの利用者変更3件を保持し、remote push/PR/merge、credential操作、deployは行っていない。
- NF06 candidate fixed `fe54dc31e0ce376e6235a3c747f621316022b793` は実MySQLで1 class **6/0/0/0**。
  UTC00:45:07.5116950〜00:46:41.2144970、exit0、起止SHA一致・clean。
  log SHA-256 `0FE67E72E5956942C922635AEA42D1AA5F143D2245D379892CFB210664BCEABC`。
  AstraはXML/textのmanifestと実container起動記録を再確認した。実行固有のMySQL version/image IDは未取得で、旧runから転記しない。
  rawはsource/controlの`verification-evidence/nf06-fixed/20261008-mysql-01/`。
  同じ実transactionのconnection IDとPRIMARY LOCK_DATAによる待機証拠、cleanup時の原例外保持を含む限定合格。
  手組schemaとmock DocumentServiceによる試験であり、V184 Flyway、実Document ACL、全NF06の合格ではない。
  次はfresh installとB0(V182)からV184へのupgrade。V183併合はcontrol統合後に別途実行する。
- NF04実Servlet run04（fixed5813973）は**3/1/0/0**。通常confirm/CSRFとlistenerは成功したが、logout競合が期待403に対し500だった。
  safe logはIllegalStateExceptionの種類のみであり、stackによる呼出箇所断定はしない。
  source `91dbca139514951b2f58b2e6eab8c3f131a9262d`はmutex再取得をfinallyから除き、失効sessionアクセスを局所的に拒否する。
  Astra限定静的受理後、clean verify `a0969030ed98f50e3e794e0f20053656f24e47d4`のrun05で**3/0/0/0**。
  UTC00:52:23.2476986〜00:54:05.3162567、exit0、起止SHA一致・clean。
  log SHA-256 `F5D8CC84CBE2F822CD7863AF4C21A7B5CF02D3515956EB602AD437C836CA17FD`。
  rawはverify worktreeの`verification-evidence/nf04-servlet-fixed/20261008-run-05/`。runtime証拠の独立再確認・control統合は未了。
- NF04 G2a `189cb6db` + `1dda69f7`はAstra **FAIL / P1=4**。
  standalone offlineのCSRF依存欠落、permit失効後の旧送信継続、IDB await後の撤回無効化、worker v2 cache残存を修復する。
  同期DOM消去だけでは解除しない。旧synthetic Chromeの自動復元assertionと未検証の新実Spring Chrome fixtureをPASS扱いしない。
- NF08 fixed `1d3873c49f8484b278cc7842cd6175818b8d2e01`はHC5 API可視性修復後の9 class検証中。
  前run04の39/1/0/0を保持する。DNS snapshotがpool待機中に失効した場合の送信境界、slow drip/TLS/cancel cleanup、strict JSON parserは未了。
  transportの合成loopback試験は実provider、DPA、費用、production enablementの証拠にならない。
- NF07 W2bはclean既存`C:\work\ses-manager-pro-privacy-retention-dsar`を安全確認して再利用した。
  branch `codex/snf-prod-nf07-attestation`、fresh B0からcontrol `b58403409a9b0ae66d33c3003fb5a0ea4b6939e4`へfast-forward。
  旧branchとtargetを保持。計画`88f5887632bc9f4d48c350a338b7ee11fdd2a308`はAstra限定技術PLAN PASS。
  signer/秘密鍵生成はtestのみ、witness log/latest CAS分離、journal/appからwitness書込禁止を固定した。
  第三MySQLが全体rollbackを検出するとは主張せず、synthetic restoreでwitnessを除外する。本番anti-rollbackは人間のgate。
  Slice Aの三DB capability/schema/canonical/fixtureを開始。production migration、実処分/送信、法的判断は許可していない。
- 全NF完成とproduction approvalは引き続き**NO-GO**。NF03/NF08のNF07 policy依存、NF05/NF08実provider、
  NF09 V182 production照合、Security/Privacy-Legal/Operations/Production Ownerの署名は未解消。

### 10月8日 限定統合と後続Review

- AstraがNF04 run05のXML/text全2 hash、起止SHA/clean、実login/CSRF/listener/logout raceを照合し、server＋fixture限定PASS。
  controlへ`801c4e29/f1d62083/1923c3fc/8e05b816/681aa36a/d5c4fe3e`として統合した。G2aは含まない。
  fixed `d5c4fe3e5daa5aced80e6021eb2f0e572dfed5c0`の10 classは**40/0/0/0**、exit0、起止SHA一致・clean。
  UTC00:57:17.2652940〜00:59:25.6593070、log SHA-256 `DC202D8D3B8BCCDD4FC1CA2ED0DC597F77DE39DDBFCB953F3A1CB7CCA8DB71EE`。
  `verification-evidence/20261008-control-servlet-integration-01/`を参照。全Fastの再合格ではない。
- NF04 G2a追加`d7bd05f0d8e72ffd3731bfe804c2d10cff526dc7`で前4 P1をAstraが静的閉鎖した。
  Node harnessはhelper抽出・mock queue・worker模擬であり、実queue/IndexedDB待機raceの実行証拠ではない。
  完全queueと実Chromeの試験を補完してからG2a全体を判定する。旧失敗runと旧assertionを削除しない。
- NF08 fixed`1d3873c49f8484b278cc7842cd6175818b8d2e01`のrun05は9 class **42/0/0/0**、exit0、起止SHA一致・clean。
  UTC00:54:21.4522024〜00:56:19.0632496、log SHA-256 `6941B3A132C830509CE97E7B618E753BE155BA76750D29BE2AE4F507946C46A5`。
  Astraが18 XML/text hashを照合した。5 runのraw 152 fileをcontrolへbyte同一保存。
  transportは基礎3例に限る。DNS freshness/slow-response/cancel後再利用の実装・試験は次slice、production gateは未解除。
- NF06 migration test`b0d5f56e7bbbd8ffacaf592761c09c3334a6433d`はrepeatable null、sentinel保持、18 unique/12 FK列対応、
  SQLSTATE/vendor code、Flyway validateを補い、Astra静的受理。実MySQL fresh/upgrade runを開始した。
- NF07 Slice A `b1248e6c`はAstra未受理。共有testの順序依存、profile変更後のraw接続/key利用、Unicode canonical衝突、
  witness signed envelope不足とpackage可視性を修復する。W2aの合格を新sliceへ流用しない。

### V184限定rehearsal完了・G2検証worktree

- NF06 fixed `b0d5f56e7bbbd8ffacaf592761c09c3334a6433d`のmigration01は**2/0/0/0**、exit0、起止SHA一致・clean。
  UTC00:59:42.4573191〜01:01:43.5726718。log SHA-256 `6C0EF395CBED8E5087A61A4A616D609C2BC2BAAAB5E754FF5639FAF4D0403805`。
  freshとV182→V184の2独立container、旧sentinel全主要field、18 unique/12 FK列対応、制約エラー分類、Flyway validateを確認した。
  Astraが原XML/text全2 hashとcontainer ID/log対応を照合。両containerのmysql binaryは8.0.46、image IDは
  `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`。
  runtime-observation SHA-256 `367A533FEE35C76FA9FDA6C06E2DDD649FA131EA716BD1DE0DAA33211D83F55C`。
  source/controlの`verification-evidence/nf06-fixed/20261008-migration-01/`へraw13 fileをbyte同一保存。
  このbranchにはV183がない。V183併合後のrehearsalと実運用移行を別gateのまま保持する。
- NF04 verify worktree `C:\work\ses-nf02-remediation`はclean確認後、旧servlet branchを保持し、
  `codex/snf-prod-nf04-g2-verify`を固定a0969030から作成した。元remote baseはB0。
  G2aを`f8de9cf9/3c58de31/c844c434`として局所検証用に適用し、controlへは未統合。
  fixed`c844c434c67f40c5498c400a12914abe7b88451e`のasset contractは**8/0/0/0**、exit0、起止SHA一致・clean。
  UTC01:02:13.4154781〜01:03:09.2700919、log SHA-256 `21CC023316D58DD59CEB000F53A668192A86B4F60C0FFD85310427D320F05A45`。
  Node helper/page mock/worker模擬も成功したが、実queue/IndexedDB/Chromeの代替にしない。
- NF07 Slice A修復`c3140628`の静的findingは閉鎖方向となり、重複@Testを`06b4d52f`で除去した。
  同固定SHAでpure/inventoryから実行する。三DB ACLの実測、後続projection/witness CAS/recoveryはまだ未完。

### W2b Slice A限定統合・lease計画確定

- NF07 fixed`06b4d52fb66afc8f770b8ce56edf3f7216bbf9d3`のpure/inventoryは**6/0/0/0**、
  UTC01:05:22.3106993〜01:06:21.5531686、log SHA-256 `46C34FE41EB341C68D03F0ADB6325FBDE4F963AEFF9D3FF5E18419DF8FAADFD9`。
  三独立MySQLのACL/signature/schema試験は**3/0/0/0**、UTC01:06:39.6398676〜01:09:28.3669067、
  log SHA-256 `44120612CDE39FDFFCD8181379A7CA2E902DF0ECFC8861D03A755CFE210600A4`。両run exit0、起止SHA一致・clean。
  Astraはpure全4 report hashとMySQL全2 report hash、container IDとlogの対応を照合した。
  三containerのmysql binary8.0.46/image ID現場記録のSHA-256は
  `EFEFD306ABA76813789CFAC8F881391BB25711D4F08DA5670DA491B5132EE2BE`と
  `262EFDAAD4C89F8B85D12D9F1485DA4FA16C28CB14EA38142383CE6A4FC592C1`。
  raw28 fileをsource/controlの`verification-evidence/nf07-w2b-fixed/`へbyte同一保存した。
- Astraの結論はSlice A限定CODE＋synthetic integration ACCEPT。controlへ
  `276c0083/816777a3/ce2f8eb4/6264b66f/3e016a0f`として統合した。
  後続CAS/replay/latest freshness、完全anti-rollback、production ACL・実処分/dispatchは含まない。
  既存計画に従いSlice Bのprojection CASへ進める。HOLD_RELEASEDは依然拒否する。
- NF06 lease第一slice計画`aba94e6f3a8a75d05d11e63d1762b22370d9956a`はAstra限定PLAN PASS。
  完全lock順、MAPPED＋両run pointer NULL、server-issued operation/grantとcommit応答喪失再試行を明確化した。
  新serviceを実装するが、run-aware renew/chunk/publish、実workerの完結証拠は後続とする。
  V184の6 COMMENTを日本語化したためchecksumが変わる。旧b0d5の実行証拠を新SHAへ転用しない。
- NF04実Chrome run01 fixed`8d0e611b`は**2/2/0/0、FAIL**。実Spring側はqueue responseのID取得、
  synthetic側はoffline再認証待機で失敗した。さらにAstraがwindow参照、IDB barrier、JS例外の誤合格、cleanupを指摘。
  rawを保持し、strict evaluatorと実waitの成立を修復後に再実行する。Chromeでの合格はまだない。
- NF08 `45a3c224`の3c fixture修復は限定静的受理、実行中。leased connection帰還とOS socket全回収、
  故障解放後のhealthyと故障継続中のavailabilityを区別し、TLS stall/strict parserは未完とする。

### 10月8日 継続検証・固定証拠の更新

- NF07 W2b Slice B fixed `27dc74d7168613dc7b24d60616227e90c68899e6` は三DB MySQL **10/0/0/0**。
  UTC01:25:12.3622622〜01:28:02.6160367、log SHA-256 `5FBFFD65CB4203F73DCE9A3151AC1AEE90C2D264D5C3796D62FA5CF56259D953`。
  Astraはprojection CAS後の最新journal prefix検証、応答喪失回復、偽造projection拒否を限定受理し、raw hashを照合した。
  controlへ `f3b25bd2/17c14fb2/61b0b979` として統合した。fixed `61b0b979aa0a1e11dafa235fcc63b3b48ba3e615` の
  `PrivacyWatermarkWitnessBoundaryMySqlTest` 再試験も **10/0/0/0**、exit0、起止SHA一致・clean。
  UTC01:56:55.4390953〜01:59:48.2483776、log SHA-256 `D13BC764AE2F9A0FAE535E4B2BB77B3D4A3A372C788F6AE260D605D560E9AAD8`。
  rawは `verification-evidence/20261008-control-w2b-mysql-01/`。本run固有のmysql binary/image観測はcontainer終了により未取得。
  witness署名・latest CAS・復元rollback検出のSlice C以降、および本番anti-rollback承認は含まない。
- NF06 COMMENT日本語化後のfixed `aba94e6f3a8a75d05d11e63d1762b22370d9956a` migration02は **2/0/0/0**、exit0、起止SHA一致・clean。
  UTC01:17:32.1350139〜01:19:28.6063800、log SHA-256 `910968BB013466274B15A0077984BFA9CC72C13C5504C7C239F3A3A0F4F2E313`。
  `verification-evidence/nf06-fixed/20261008-migration-02/`。V183併合rehearsalは未実行。
  lease fixed `c69ef8ca8e5a5f4017af61065fba2176e6336205` のAstra再Reviewは **REQUEST CHANGES**。
  P1は異なるservice instanceのgrantによりrenew競合がDB待機へ到達しないテスト、P2はrenew再試行のversion不変assertionと
  hashを含まない状態の誤比較。新instance takeover経路の修復は確認したが、この三件の修復・実試験前にlease PASSを付与しない。
- NF08 fixed `45a3c2244cdd237a81e795de923de51caeab4250` のrun06は **47/0/0/0**。
  UTC01:12:37.6611557〜01:14:17.8615113、log SHA-256 `7BC4D6211A5A37FE3768C218B6386AD1BE44D8BD61BF514CD37DC8F3BF45F389`。
  続くstrict UTF-8/JSONとTLS停滞修復 fixed `020dc4d142b4fa3eccbf67168eab1c1dd282bf97` のrun07は10 class **53/0/0/0**。
  UTC01:29:41.4918040〜01:31:04.4571463、log SHA-256 `150AC513110D35D785042FA7A6067E6EA450D9249DCB0BA8E1E5AB13E18D94DD`。
  両runはexit0、起止SHA一致・clean。Astraはrun07の20/20 report hashとXML集計を独立照合した。
  raw28 fileをcontrolの `verification-evidence/nf08-fixed/20261008-run-07/` へbyte同一保存。
  TLSはClientHello後の停滞・deadline/cancel/pool帰還だけの証拠であり、正常証明書/hostname検証、実provider全chainの合格ではない。
  Gemini接線草案はPLAN REQUEST CHANGES。証明可能な費用上限、送信後失敗の費用証拠保持、immutable invocation/config、
  strict envelope/duplicate keyを明確化する。最初のsliceはproduction cost deny-all、loopback許可はsrc/testのみとする。
- NF04 G2 Chrome run02（fixed `e69fd88e3a3adf3a11153fdc00938392534cfcb8`）、run03（fixed `b1ecfbe7e43814741815d52393351fb8aeb3c732`）は
  ともに **2/2/0/0、FAIL**。run03は合成restart後XSRF cookie欠落で送信前拒否、実Springはworker経由応答でもoffline shellにならなかった。
  requestが発生していない拒否とサーバー拒否を混同しない。pageのCDP offlineをworkerの実fetch障害の証拠へ拡大しない。
  Chrome01/02/03 raw計37 fileをcontrol `verification-evidence/nf04-g2-fixed/` へhash一致保存した。
  source `c423d506e6d4c8b0e8f4a5bd1a7007b3855e79b9` のstrict hookとnavigation単位のresponse対応は未実行。
  Set-Cookieがsession cookieのままであるため、HTTP発行への変更だけで再起動問題が解決したとは判断しない。
- 総合 **NO-GO** を維持する。以上は局所的なcode/synthetic証拠であり、完全CI、全機能完成、実staging/provider、
  backup/restore/PITR、V182本番照合、Security/Privacy-Legal/Operations/Production Owner署名を代替しない。

### lease局所検証・W2b署名slice・Gemini契約計画

- NF06 `696221cf701797e640f1865536f203bfdc8ad3dd` で三件のテストfindingを修復し、H2 leaseは **6/0/0/0**。
  UTC02:01:55.1635827〜02:02:54.8990248、log SHA-256 `7A3B12E3C94990E52B690D77D99A5A797C28FE5DEE2F491FA45FEA03D42CDC9C`。
  Astraはreport 2/2 hashと集計を照合した。追加で旧grant拒否が別instance拒否になっていたP2を検出し、
  `14f81ae58d37f23162549da11c1f8d2f41c3baaa` で同instanceの旧generation拒否へ修正、capture再設定時の旧IDも消去した。
  Astra静的findingは閉鎖し、同fixed SHAの実MySQL leaseは **9/0/0/0**、exit0、起止SHA一致・clean。
  UTC02:08:02.3753415〜02:09:16.7386599、log SHA-256 `E46B7365DC5920F511435EA8FF3B77D55F4307B9A07753536F66E1571FE531A8`。
  rawは `verification-evidence/nf06-fixed/20261008-lease-fast-01/` と `20261008-lease-mysql-01/`、各10 fileをhash一致保存。
  MySQL runtime証拠の独立再照合、F1a全体Reviewとcontrol統合、run-aware validation/chunk/seal/publishはまだ別gateである。
- NF07 W2b Slice C fixed `1803803f3d491a28ea118dd2aaad79a7e4e81528` の三DB MySQLは **13/0/0/0**。
  UTC02:04:41.1907156〜02:07:37.4952254、exit0、起止SHA一致・clean。
  log SHA-256 `3A7EDCD8149E8322C84D72C9A4561AC0E88E16D27DF3E36AFA78CED212290794`。
  raw10 fileを `verification-evidence/nf07-w2b-fixed/20261008-mysql-03/` へhash一致保存した。
  本runは三containerそれぞれのID、image ID、SELECT VERSION()=8.0.46、schema hashをtest内で記録した。
  Astraは **REQUEST CHANGES（P2二件）**。既存ACK喪失はtest wrapperに限られ、main JDBC commit例外readbackを未検証。
  witness CAS競合も逐次例だけで、projectionの並行試験では代替できない。真のcommit応答喪失とwitness実lock待機を追加する。
  signature verifierへのhistory/latest引数は呼出元snapshotであり、live latestやrestore安全性の証拠ではない。
  Slice Dで独立witness再取得・replay後再確認と静止partitionのfreshness方針を明示するまで本番復元を許可しない。
- NF08 Gemini接線計画 fixed `dbed7529f5f875d1fc85674ca9cb50057aed886b` はAstra限定 **PLAN PASS**。
  A: immutable config/invocationと費用/dispatch契約、B: bounded adapterとstrict protocol、C: attempt/失敗費用伝播の順とする。
  最初のproduction cost policyは固定deny-all、loopback許可policyはsrc/testのみ。実費用上限・provider承認・artifact publishは別gate。
  secretは既に供給済みのlocal accessorに限定し、無界なsecret-store I/Oを導入しない。A slice実装を開始したが未完である。

### W2b Slice C限定受理

- fixed `97641ce9dd836696bc8c69570bae25bf2f6fa3da` のMySQL06は **16/0/0/0**、exit0、起止SHA一致・clean。
  UTC02:20:57.7813741〜02:23:51.8939070、log SHA-256 `71DBB29FE4AE7161F8173DCC8C4DC4C2547B193A152B4AA04B3BF271FC445076`。
  Astraは全2 report hash、XML集計、同run固有の三container ID・SELECT VERSION()=8.0.46・image ID/schema hashを確認した。
  実JDBC commit後の例外readback、rollback後のquarantine、readback不能、witness並行CASの異なる候補両順序と同一候補収束を含む。
  待機はglobal countではなく実transaction connection IDsと対象PRIMARY/LOCK_DATA、holderへの待機graphで確認する。
  signature/append/latest/限定合成統合の **CODE＋証拠PASS** とし、raw10 fileをcontrolへhash一致保存した。
- MySQL04 fixed `f792ebe1` は **16/1/0/0**。BEFORE_COMMITの未rollbackによりconnection cleanupがcommitし、REPLAYEDとなった。
  MySQL05 fixed `5b803720` はコンパイルFAIL・新規XMLなしであり、0件PASSではない。裸tryと接続IDの固定snapshotを修復して上記06へ進んだ。
  失敗raw16 fileも `verification-evidence/nf07-w2b-fixed/` に保持し、成功結果で上書きしない。
- Dの独立latest再取得/replay後確認、静止partitionのfreshness、完全restore/anti-rollback、外部witness運用・Privacy/Legal承認は未完。
  この合格をNF07全体完成やproduction approvalへ昇格しない。

### 統合witness再試験と新たなChrome阻害

- Slice Cをcontrolへ `afe544cd/30bc7b3d/b1ae3c94/a0a05f7d` として統合した。
  fixed `a0a05f7d4d179d314469413cf7270fe3986ed8d9` のcontrol MySQL02も **16/0/0/0**、exit0、起止SHA一致・clean。
  UTC02:29:02.0566741〜02:32:04.3232022、log SHA-256 `6A526642C5EEA838A668227B089E18817A9DE1D9D730548C3BF57F89CB5675AE`。
  `verification-evidence/20261008-control-w2b-mysql-02/` に三DB個別の実version/image記録を含む。全MySQL gateの再合格ではない。
- 再fetchしたorigin/mainはB0のまま、最新remote main run36858538460はCOMPLETED/FAILURE。main利用者変更3件は保持している。
- NF04 Chrome04 fixed `485c2db6ca014ac067f3abada0db0560c8488b80` とChrome05 fixed `9313108e7ccc5e834b3eaa11fdab4908d33d4a28` は
  いずれも **2/1/1/0、FAIL**。厳密な削除transaction完了barrierとworker復旧時FETCH_FAILEDの問題を分離して追跡する。
  `pwa-queue.js` のdeleteScope/cleanupExpiredはopenKeyCursorのcursor.delete()を呼んでいる。
  W3C IndexedDB 3.0のdelete手順6によりkey-only cursorではInvalidStateErrorとなることをrootとAstraが独立確認した。
  参照: https://www.w3.org/TR/IndexedDB/#dom-idbcursor-delete 。同一transactionのstore.delete(primaryKey)へ修復し、実IDBのscope/TTLを再検証する。
  worker TypeErrorの原因はこれとは別で、現在の安全診断だけでは確定しない。全sessionのonline復旧と実fetch成功を引き続き要求する。
  両失敗raw各12 fileをcontrolへhash一致保存。過去のNode helper合格を実Chromeの代替にしない。
- NF08 Slice A fixed `39550ec2` はAstra限定静的PASS後、run08でテストのtext() API不存在によりcompile FAILとなった。
  `e0ce3686` はgetText()へ最小修正し、run09で12 class **64/0/1/0**。新local成功fixtureのEffectiveScopeSnapshotが不正である。
  いずれもPASSへ昇格せず、実scope契約を満たすfixtureへ修復して同selectorを再実行する。B/Cは未実装。
- NF06 `0c109fa1` のdisableSource不存在/foreign tenant NPE修復はAstra限定受理。
  実metadata/Document成功経路test `32b36a6f` はfixture tenantとconfigured tenant不一致で静的REQUEST CHANGES。
  実サービスの認可をmockへ置換せず、同じsynthetic tenantで設定とfixtureを一致させる。文書は人工的な既存CLEAN状態でありupload/scan実証ではない。

### 2026-10-08 Chrome局所合格とfixture再検証

- NF04 source `b94173667863cf2b773612f2c923bba781660680`、verify fixed `5b40590ee2792f2afd440a78848c40df05be358c` のChrome06は **2/0/0/0**。
  UTC02:43:08.7034188〜02:44:30.5394342、exit0、起止SHA一致・clean。
  log SHA-256 `12DCF277A912335E9C9695E5F98076E2B3CE035F8FE3DAB1259A1EB4748316CF`。
  Astraは4 report hash・XMLを独立照合し、限定証拠PASS。key-only cursor削除を同一transactionのstore.delete(primaryKey)へ修復した。
  合成HTTP側は実Chrome/IndexedDBのscope複数行削除・foreign scope維持・TTL境界・撤回競合を検証した。
  実Spring側はworkerのoffline fetch障害・online復旧、再認証・明示確認を検証した。確認後の業務送信はtest fenceで停止するため、業務送信成功の証拠ではない。
  Chrome版/cache記録を含む16 fileをcontrol `verification-evidence/nf04-g2-fixed/20261008-chrome-06/` へhash一致保存。
  共通CdpBrowser変更に対する全11 class browser profileは別gateとして実行中。G2のcontrol統合・全体PASSはまだ付与しない。
- NF07 control C MySQL02の16/0/0/0はAstraがreport hash、起止clean、三DBの実version/image/schemaを独立照合し、限定統合証拠PASS。
  D recovery計画 `707987ac0d2b5d1083cab2167129c2db59143c66` は独立Review中。live witness再読・replay後確認を未実装のまま残す。
- NF06 fixed `ebd89a278f6ac8c53f7a1a73e316dd0b3d65e7ee` metadata-fast01は3 class **9/0/0/0**。
  UTC02:36:01.960733〜02:37:09.4785087、log SHA-256 `B0A9B108AA50DBE7DBE2FDF5D9F461FD01CB59331989B49027185B99DF5E860A`。
  Astraは6 report hashを照合。内訳はlease6・不存在/foreign source2・shard inventory1であり、実Document ACL成功経路は含まない。
  同fixed metadata-mysql01は **1/0/1/0、FAIL**。fixture usernameのsubstring範囲誤りで業務呼出前に失敗した。
  UTC02:38:14.4031052〜02:40:02.6891868、log SHA-256 `B068FB1BF2818DAF00923C9E39D41D0ECC1DA92637BF391441491F063C99761B`。
  修復 `23dbe662f88429803fdd4dc32d82fb60e440d225` はAstra限定静的PASS、実MySQL再実行待ち。成功/失敗raw各14/10 fileをcontrolへhash一致保存。
- NF08 fixed `677a0ab0c86331886d686881a44dcba1d03a61e9` のrun10も12 class **64/0/1/0、FAIL**。
  UTC02:40:34.8905933〜02:42:10.783192、log SHA-256 `C4CD8D411C91CB1FC958ABFA37DB63ABA5C23897582DA08A30FD6292FC36B402`。
  fixture request.scopeHashがcontextの実hashと不一致でEXECUTION_CONTEXT_REQUIREDとなった。製品guardは弱めず、`6693abd1f9bbaf96c4b62a9f90070784adfd7b04` でfixtureを一致させた。
  Astra静的PASS、run11待ち。run09/10各32 fileをcontrolへhash一致保存。Slice B実装中、C失敗cost伝播は未実装。
- 全体NO-GOを維持する。局所成功は全CI、staging、実provider、移行/復元演習、人間の各署名を代替しない。

### 2026-10-13 再開時の固定証拠と残留finding

- 再fetch後のorigin/mainは `1443fb68943849a3260d7ebe005259f3eb609d60`。最新main run `36858538460` はcompleted/failureのままである。
  main checkoutの利用者変更3件と各隔離worktreeの既存変更を保持した。push/PR/merge/deploy、実credential使用は行っていない。
- NF08 Slice A fixed `6693abd1f9bbaf96c4b62a9f90070784adfd7b04` のrun11は **12 class、64/0/0/0**。
  UTC2026-10-08 02:54:12.8212588〜02:55:49.8422075、exit0、起止SHA一致・clean。
  log SHA-256 `DD4024BB6D07FC7A058429DD6F0BADC4A0D81343CE9AE427070F9A5A49740953`。
  Astraが24 report hashとXMLを照合し、限定証拠PASS。raw32 fileをcontrolへhash一致保存した。
  Slice B候補 `fdbbe56ddea8d798529cbc293ef591a15354b60f` はAstra **REQUEST CHANGES**。
  adapter自身のproduction deny-all、無効transportによるboundary test偽陽性、TLS/全chain統合、blocked/index overflow、
  usage次元整合、Copilot HTML除去による不正入力の合法化を修復する。Aの合格をBの証拠へ流用しない。
- NF04 fixed `5b40590ee2792f2afd440a78848c40df05be358c` の全browser01は **11 class、16/1/1/0、FAIL**。
  UTC2026-10-08 02:50:56.5135404〜02:53:38.9543478、exit1、起止SHA一致・clean。
  log SHA-256 `FA5AAF03C886B411CFBA1B5601D2FCBB50E92B3A9F91920EE6FA5B3EE5142E24`。
  raw30 fileをcontrolへhash一致保存。AstraはPWA waitForStrictの未準備時undefinedを確定原因として特定した。
  EngineerSelfServiceBrowserMTestの1on1は業務400。指定engineer×salesの関連と再利用salesのrole/statusのfixture欠落を検出したが、
  失敗時DB snapshotがなく、今回の正確な拒否分岐は未確定。HTTP成功assertionを維持し、fixtureと安全な前提診断を補強する。
- NF06 fixed `23dbe662f88429803fdd4dc32d82fb60e440d225` のmetadata-mysql02は **1/0/0/0**。
  UTC2026-10-13 04:37:13.0164850〜04:39:17.3971670、exit0、起止SHA一致・clean。
  log SHA-256 `A2733B89A83F40B62FE5E32913424F702282536ECE609DE928D935FCAB8C95D9`。
  実metadata/Document/FileScopeを通るsource→mapping→job/link→bind→source open→DOWNLOAD監査の成功経路である。
  人工seed済みCLEAN文書でありupload/scanの実証ではない。追加ACL負例 `0e2962245beed1bd18cea44bcd04862a4962d890` は別Review/実行gateとする。
- NF07 D計画 `707987ac` のAstra reviewは2件P1・1件P2でREQUEST CHANGES。
  永続quarantine/generation fence欠落、witness R5で未checkpointのR10→R7巻戻りを検出できない問題、recovery DB権限境界が対象である。
  計画修正版 `0fbd0515e71621194960ae754859d09b4f1971f2` はtest-only永続fence、witness latestとreplay headの厳密一致、
  reader/writer最小権限とfixture-admin分離を定義した。独立再Review前であり、D実装・合格ではない。

### 2026-10-13 ACL実測と統合前の追加阻害

- NF06 fixed `0e2962245beed1bd18cea44bcd04862a4962d890` のmetadata-acl01は **14/0/0/0**。
  UTC04:39:57.4782515〜04:41:48.3766226、exit0、起止SHA一致・clean。
  log SHA-256 `2C63B6BD6CE93F6272FDAA49EF3DE6D6533905574CAF875BC79F463FAE4182CC`。
  Astraは2 report hashとXMLを照合し、限定CODE/証拠PASS。成功1件と呼出前の権限/状態変更後の拒否13件を含む。
  principal tenant負例はconfigured tenant mismatchの入口拒否であり、有効な二tenantの全資源分離の証拠ではない。
  metadata-mysql02/metadata-acl01各10 fileをcontrolへhash一致保存。前commitの英語messageは言語規約との不一致として認識し、固定SHAを改変しない。
- Astraの統合前監査でmetadata writeにP1を検出した。createMapping/registerJob/bindMapping/disableSource等は入口認可後にsource行ロック待機し、
  その間の権限取消後も更新/冪等成功を返し得る。14件の既存ACL拒否試験はこの競合を検証しない。**F1a統合を保留する。**
  resource lock取得後にactor・全membership・group・actionをcurrent locking readし、同一transactionのcommitまで権限ロックを持つ修復を進める。
  RRの通常SELECT再読、RCだけの変更、任意SQL例外を403として扱う試験は代替にならない。source待機中の取消とgate取得後の取消順序を実MySQLで区別する。
  candidate/lease全体のcommit線形化や、未検証の組織変更までこの修復の保証へ拡張しない。
- NF04 fixture修復 source `f919c0bc746a7aeb3c4ad554767c8ca5290f764c`、verify `0226ac542025144131a4024383cdd1cbfc0ee49a` の全browser02は
  **11 class、16/1/0/0、FAIL**。UTC04:44:09.9629886〜04:47:05.1993785、exit1、起止SHA一致・clean。
  log SHA-256 `F42FB6718494FCDAF4DC88B6AA5ACAF797FC0367BA79264A6E1F0C3BFC833AC2`。raw30 fileをhash一致保存した。
  PWA 2 caseは合格したが、1on1業務400が残る。独立sales・精確関連だけで根本原因が解決したとは報告しない。
  source `802964ac8bd0bd9e7b51dfdd73cb59a18e0a4d12` でtest-only resolverによる安全なmessageKey/status診断を追加し、実HTTPの通常例外処理を維持して再実行する。
- NF07 D修正版 `0fbd0515e71621194960ae754859d09b4f1971f2` はAstra限定PLAN PASS。
  D1は永続partition fenceを先行実装する。D2の独立live witness読取/replay/final reread、全復元、production解除は未実装である。

### 2026-10-13 adapter限定合格と永続fence統合

- NF08 Slice B fixed `286ab361f3cfb8c07fbb412cea85559a102b0a0c` のadapter-b-03は **18 class、84/0/0/0**。
  UTC05:09:44.1817905〜05:11:12.4818634、exit0、起止SHA一致・clean。
  log SHA-256 `92C5929AC55831F36CAB6D13C95C4C0F544080739A731947FC7813BBD94C0A40`。
  Astraは36 report hash・更新時刻・XMLを独立照合し、synthetic bounded Gemini adapter/parserの限定CODE・証拠PASS。
  production deny-all、test seamのloopback固定、接続時DNS再検査、厳密なresponse/usage検査、HTML原文拒否、429分類、TLSを含む。
  adapter-b-01はcompile FAIL・XMLなし、adapter-b-02は84/0/4/0であり、失敗履歴を保持する。
  b-03 raw44 fileをcontrolへhash一致保存。実CopilotSummaryService全経路、Slice C失敗費用/attempt伝播、実料金policy、
  DPA/外送承認・credential・flag・artifact publishは未完。NF08新adapterはまだcontrolへ統合していない。
- NF04 selfservice診断単独は1/0/0/0、全browser03は16/0/1/0、全browser04 fixed
  `78475cfe56b4c8912b86c360fd84d691fdcf7870` も **11 class、16/0/1/0、FAIL**。
  全browser04 UTC05:06:06.0729458〜05:08:57.4041692、exit1、起止SHA一致・clean。
  log SHA-256 `02BED0B028D4A089887E3A121AF50DCE88DADC405A1AF05921C81C193AC119B9`、raw30 fileをhash一致保存。
  SelfServiceは専用H2 DBで合格したが、以前の400の正確な業務拒否分岐は確定していない。
  PWA初期化前whenReadyがnullとなる競合をstrict assertionを維持して修復した後、今回はPwaQueueChromeIsolationBrowserTest
  line354でJavaScript例外を検出した。G2全体合格・control統合は引き続き保留する。
- NF06 auth-lock-01 fixed `6e728ba0e6f3baa1cf712c16ff1b8aa1a8a52b8a` は **16/1/0/0、FAIL**。
  UTC04:58:27.0812982〜05:00:28.0134946、log SHA-256 `B15864955B84DF2C7901D512CA7884259F3D2B84C045176DAF639F2D5E557469`。
  permission group PRIMARYへの待機が観測できず、FK親ロックの証明は成立していない。子index/gapで先に待機する可能性は仮説である。
  fixed `cbe9904b1915f0e6280564166896e12909651f5a` はfuture例外と実wait graphの安全診断を追加し、単一file javacはexit0。
  実MySQL再試験前に認可競合P1を閉鎖しない。raw10 fileを保存し、F1a/V184統合は保留する。
- NF07 D1 fixed `8fd3bcb0feac378d46b7b86541f89c0d72221803` は実MySQL **20/0/0/0**。
  UTC05:02:35.5433942〜05:05:32.6246229、exit0、起止SHA一致・clean。
  log SHA-256 `DD3A53E11FE51BAEA0B4585B5D960533C75E570DBBC2E8A92ED58962009EC66A`。
  Astraが2 report hash、三DBの実version/image/schemaを独立照合し限定CODE・証拠PASS。raw10 fileを保存した。
  永続quarantineのREQUIRES_NEW、generation/owner fence、通常投影の拒否、復元・recovery apply、外側rollback後の隔離維持を含む。
  計画とD1をcontrol `2c8f97cb/5ef1b1fa/f0bb8515/b4778dea` に統合した。統合後のruntime再試験は別gateである。
  個別snapshot/applyのtransaction順序を検証したが、cross-DB service全体・返却後の持続的認可を保証しない。
  D2のlive witness/replay/final reread、実process restart、production SQL ACL、隔離解除、Privacy/Legal承認は未完。
- 全体NO-GOを維持する。全fast/MySQL/performance/browser/backup gatesを最新controlで通した証拠はなく、
  staging、実provider、migration/restore rehearsal、各人間Ownerの署名を局所合格で代替しない。

### 2026-10-13 全browser限定合格と追加非同期境界

- NF04 fixed `360de89fa32b8989ca778bcb3e4f7d6f56da2cf6` の全browser05は **11 class、16/0/0/0**。
  UTC05:20:52.5900622〜05:23:48.8188226、exit0、起止SHA一致・clean。
  log SHA-256 `C59C0A78B70C05B8754B885EA72CDAD37F0486C0901B07172A0D9C12D4753C2D`。
  Astraが22 report hash/XMLを独立照合し、既存CdpBrowser全consumerの限定証拠PASS。raw30 fileをhash一致保存した。
  ただし統合前の追加ReviewでbootstrapScopeにP1を発見した。context検証後のdeleteScope待機中に撤回すると、
  遅れて戻るbootstrapがscope/persisted markerを書き戻しquarantine/pausedを解除し得る。既存16件はこの競合を覆わない。
  server認可迂回の証拠ではなく、撤回documentのローカル隔離契約違反として修復する。**G2統合は保留。**
  G1/serverはcontrolへpatch-equivalentで統合済み。G2候補範囲は `f8de9cf9^..360de89f` の22 commit・12 fileでありcommon.jsを含まない。
  sourceのcommon.js利用者変更および未追跡exitcodeは持ち込まない。新競合修復後も全browser gateを要求する。
- NF06 auth-lock-02 fixed `cbe9904b` は **16/1/0/0**。実wait graphは指定gate ownerへの
  `t_permission_group_action/uk_permission_group_action` のINSERT_INTENTION待機であり、元のFK親PRIMARY限定assertionが狭すぎた。
  UTC05:15:14.1026794〜05:17:13.1410705、log SHA-256 `4B9F84D979C49CDF85E86296595B5E70BB75A785447AD1CAF8901102B91ADCCF`。
  test修復後のauth-lock-03 fixed `718833da` も **16/1/0/0**。action待機は通過し、membershipは
  `t_user_permission_group/uk_user_permission_group` のsupremum INSERT_INTENTION待機を実測した。
  UTC05:24:10.1708395〜05:26:05.0135425、log SHA-256 `8A0EE4204E1830E8DD35841E5F4AD6402EFA0864082FC01B9D0A2F8D19F86E10`。
  任意lock waitを許容せず、connection edge/schema/table/index/type/mode/statusを厳密に限定して再検証する。
  これらの失敗はgate無効の実証ではないが、成功試験へ昇格しない。
- NF07 D1 control fixed `a9e8c360f9d6661e35175bde4148d6d2a2e60d28` のMySQL再試験は **20/0/0/0**。
  UTC05:17:37.3787664〜05:20:33.2794410、log SHA-256 `7F12EC481C9A3BB58100A1D8FA1DF9574906B1F9E77A0760903EADE7E2777F32`。
  Astraが固定SHA、report hash、三DB version/schemaを独立照合した。sourceとcontrolのapplication schema hashは異なり、
  各固定tree自身のschemaを基準に照合した。同じcontrolのpure/inventory回帰は **5 class、18/0/0/0**、
  UTC05:26:27.7059412〜05:27:25.7342716、log SHA-256 `911FA2C2A3526956A5F26664F5360FD6CD97FC0C59D0DC06F2896A132AA0DDD3`。
  後者の独立証拠照合は未完。全control CIの合格ではない。
- NF07 D2候補 `52a3ac82` は独立Reviewでconstructor可視性のcompile阻害P1、retry計数P2、時間上限未実装P2を検出。
  live witness/journalとowner-fenced replayを実装した候補だが、未コンパイル・未実測でありD2 PASSではない。
  constructor/計数を先行修復し、総deadline/JDBC待機上限・追加競合matrix・復元ACL・実restartを未完gateとして残す。
- NF08 C1候補 `df0aa935` は独立ReviewでdispatchのNOT_SENTへの逆行、未信頼public DTOからのUPPER_BOUND化、
  headers受領後の失敗でdispatch証拠欠落を指摘された。実production deny-allは維持され、外送迂回のfindingではない。
  C1修復後に定向検証する。ACTUAL/rate card、outer usageとinner失敗の分離、完全な費用/失敗matrixはC2の未完項目である。

### 2026-10-13 metadata認可ロックの限定実証

- NF06 auth-lock-04 fixed `068aaad29f0a793a3defe39a322b62022d91b92e` は **16/0/0/0**、exit0、起止SHA一致・clean。
  UTC05:28:36.1459996〜05:30:25.6412991、log SHA-256 `B5D441F3B5D76A6DDC36B99B481E7486DC1466E97873950D039B82A10DCE07A7`。
  Astraが2 report hash/XMLを独立照合し限定受理。raw10 fileをcontrolへhash一致保存した。
  実metadata registerJobのsource待機中撤権拒否、直接helperが持つ認可lock下のaction/membership INSERT待機、
  解放後のinsert成功と次回403を実証した。観測対象はchild unique indexであり、FK parent PRIMARY待機の証明ではない。
  createMapping/bindMapping/disableSource等の実service競合matrixは追加候補であり、全metadata write・candidate/leaseの競合解決へ拡張しない。
- control D1 pure fixed `a9e8c360` の5 class18/0/0/0についてもAstraが10 report hash・起止SHA/cleanを独立照合し限定受理した。
- NF04 bootstrap修復 `7541e3e8` はAstraがP1の静的閉鎖を確認した。新testのJSON null assertionを `70fe6d0a` で修正し、
  verify fixed `38f684acbf8da9dae37d5473f7c2fa63fc9a7c21` の全browser再試験を次gateとする。前fixedの16件合格は新競合の実証ではない。
- NF07 D2a `d83266af` はpublic constructor・retry計数修復をAstraが限定確認し、実MySQL診断を開始した。
  試験が成功しても総deadline/JDBC待機上限、追加故障matrix、復元ACL/実restartの未完を消さない。

### 2026-10-13 G2ローカル統合とrecovery限定実証

- NF04 source修復 `7541e3e8/70fe6d0a`、verify fixed `38f684acbf8da9dae37d5473f7c2fa63fc9a7c21` の全browser06は
  **11 class、17/0/0/0**。UTC05:35:28.7640580〜05:38:24.4984301、exit0、起止SHA一致・clean。
  log SHA-256 `B8DDC05F1BEAE2A62801282F93AB438008CB40775F1C2B4BB62E3FA2BA74E358`。
  Astraが22 report hash/XMLを独立照合し、実IndexedDB completion待機中の撤回と旧bootstrap帰還拒否を含め限定受理。
  raw30 fileをhash一致保存した。G2の24 commitをcontrol `2053adb2`〜`6980dff8` に統合した。
  common.js利用者変更は含まない。EngineerSelfService testの自動mergeはcontrol側account-link削除とG2側fixture/診断を両方保持する。
- control fixed `6980dff8ae6bde28a5792cf724523d3f971acb7c` の全browser再試験も **11 class、17/0/0/0**。
  UTC05:42:28.4528486〜05:45:25.2562488、exit0、起止SHA一致・clean。
  log SHA-256 `BDA6A100D56917113EFA63B4981C4923347AA7C52E10885F18D377DA13053171`。
  Astraは22 report hash/時刻、統合差分、common.js非混入を独立照合し、ローカル統合の限定CODE・証拠PASS。
  同じcontrolのNF04 fast定向は **13 class、85/0/0/0**、UTC05:45:41.8642789〜05:47:44.5791058、
  log SHA-256 `CBC4D46CCC44E8D1EF30AEDE25F45BEC4149DB8DA2A8B364D87E90AD68AAF1A8`。
  Astraが26 report hash・XML・実行時刻を独立照合し、定向fastの証拠も限定受理した。最新control全fast/全MySQL/performance/backupやproduction多browserの証拠には拡張しない。
- NF07 D2a fixed `d83266af6a1e3044b5ab76ce120272c6a1f1c6a0` は実MySQL **26/0/0/0**。
  UTC05:31:40.4406508〜05:34:42.3743662、exit0、起止SHA一致・clean。
  log SHA-256 `0AC01EFC4F4E086057D057872391DECCB2547EE896ED31F3A0DA68F4C3E846AE`。
  Astraがreport hash・三DB/schemaを独立照合し限定受理、raw10 fileをcontrolへhash一致保存した。D2aは未統合。
  総deadline、stable-read競合の全matrix、復元ACL、実process restartは未完である。
  D2b計画 `66eeaf8d` はowner期限・隔離取得未確認の二P2を修正し、`7ea5287f` で限定PLAN PASS。
  既に持続隔離を確認したpartitionを前提に、不可延長lease context、期限後の同一lease拒否、全JDBC/cleanupの予算共有を実装する。
  初回隔離取得のcommit不明はABORTED/UNKNOWNであり、持続隔離済みとは報告しない。
  5秒作業＋2秒cleanupは選定fixtureの測定基準であってdriver/OS任意挙動の一般的硬上限ではない。実装・試験は未完。
- NF08 C1-01 fixed `20bfca42` は **18 class、90/0/1/0、FAIL**。
  UTC05:38:45.8608091〜05:40:07.4240290、log SHA-256 `FC6632A8151B0C012C37D5D857FA994941951A8CD71F8417403D30ACCE46D7AB`。
  completeSummaryAttemptの条件式でnull費用が自動unboxされNPEとなった。raw44 fileを保存し、
  `3d95d3f5` でenum switchによるnullable代入と旧actual/upper metadata消去・回帰を追加。Astra静的受理、再試験待ち。
  C2のACTUAL/rate-card・outer usage/inner失敗の分離は未実装であり、C1の定向成功で完成としない。
- NF06実service撤権matrix候補 `a498b162/000b2574` はrace-matrix01で **compile FAIL・XMLなし**。
  UTC05:41:07.1109752〜05:41:44.2104230、log SHA-256 `0235FCD764BBBBA7E02DBE152334417A2B26BCFA261FDEB9A76C46423490FF95`。
  test method識別子内の空白を `1daff170` で修正し、単一file javacはexit0。raw6 fileを保存した。23件候補の実MySQL再試験前にPASSとしない。
