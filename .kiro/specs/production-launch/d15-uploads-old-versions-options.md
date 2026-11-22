# D15: uploads の切替えの後の旧版の扱い（候補案）

- 状態: **草案（決定の前）**。各項の「推奨」は統括の案で、決定は各項の「決める人」が行う。どの項も**未決定**で、受理・署名は**未取得**。
- 対象: `deploy-runbook.md` §4.4 の D15（uploads の旧版 directory `v<n>` と切替えの残り `.stage-*`・`.<pointer>.switch-*` の人手の削除）のうち、本書は次の4つを扱う。(a) 旧版の保存期間、(b) 旧版の読取り専用化の方式、(c) 旧版に hold の対象の実体が無いことの確かめ方、(d) DSAR の削除と保持期限の purge が旧版に届かないことの受理。切替えの残り（`.stage-*`・`.<pointer>.switch-*`）は唯一の写しではなく、runbook D15 のとおり restore 主体が消してよい（本書の対象外）。
- 正本: `deploy-runbook.md` §4.4 の D15 と D-05 owner の受理事項 (9)、`.kiro/specs/database-backup-recovery/design.md` §10・§12、`ops/backup/README.md`「uploads の版の切替え（`uploads-cutover.sh`）」、`ops/backup/runbooks/restore-cutover.md` §2・§4.5.1、NF07 の hold の設計（CLAUDE.md の S-NF の節、`PrivacyHoldPathGuard`・`PrivacyHoldFileReferenceProvider`）。

## 0. 初回リリースとの関係（2026-11-22 利用者決定）

NF07 保持・保全（hold の開始と解除）と NF07 本人請求（DSAR）は初回リリースで ON にする（`deploy-runbook.md` §1.7）。そのため本書の (c)（hold）と (d)（DSAR・保持期限の purge）は **go-live の前提**である。どちらも D-05 owner の受理事項 (9) に当たり、(d) は D-06 owner とも合わせて受理する（runbook §4.4 末尾。**未取得**）。

(a)・(b) は、旧版が初めてできる時（最初の本番の restore の uploads の switch）の前に要る。ただし (d) の受理は「旧版がいつまで残るか」（(a) の上限）を前提にするため、**(a) も go-live の前に (d) と合わせて決めることを推奨する**（決定は下の (a) の「決める人」）。

## 1. 事実（現状の実装と手順）

- 旧版ができるのは restore の後の `uploads-cutover.sh switch` だけで、旧版は restore の前に本番で使っていた版である。復旧点より後に置かれた実体（その DB の行は restore で失われた）を持ち得て、その唯一の写しになり得る（runbook D15）。
- `uploads-cutover.sh` は何も消さず、mode も変えない。旧版は restore 主体と app の uid（同じ uid。2026-11-18 利用者決定）で書ける。app は `current` が指す版だけを使うため、app が旧版へ書くことはない（runbook D15）。
- write-enable の前は旧版が rollback（逆向きの switch）の先である。`write-enabled` の後は旧版へ戻さない（`restore-cutover.md` §2・§4.5.1 の6）。
- 孤児清掃（D10）・保持期間の purge（D3〜D7）・DSAR の削除は `current` の版だけを対象にし、旧版に届かない。hold の gate（`PrivacyHoldPathGuard`）も D15 に接続しない（app の経路ではない。runbook D15）。
- `database-backup-recovery/design.md` §10 の「旧 version は retention 期限まで read-only で保持」は、read-only 化も retention の期限も実装していない（runbook D15）。
- backup は PITR の30日分の checkpoint を復元できる状態で残す（`database-backup-recovery/design.md` §12）。旧版にだけある実体（復旧点より後のもの）は、最後の checkpoint の後に置かれたものなので backup にも無い。
- restore の後の本番 DB の hold の projection（`t_legal_hold`・`t_privacy_projection_state`）は復旧点の内容である。hold の正本の Journal は restore の対象外（runbook §3.6）なので、復旧点より後に Journal に記録された hold は、restore した DB の照会だけでは見えないことがある。

## 2. (a) 旧版の保存期間

| 候補 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| a-1（推奨） | `write-enabled` の後30日（PITR の保持と同じ）で D15 の削除の対象にする。incident が続いて旧版が調査・回収に要るときは、incident commander が理由と新しい期限を change ticket に記録して延ばす | 期限が決まっていて、個人データが purge の外に残る期間に上限がある。backup の PITR の窓と揃い、説明しやすい | 30日で足りない調査では延長の手続が要る。延長を決める incident commander は baseline の 008 の事項で、まだ決まっていない（owner 未指定。runbook §1.7「Backup 開始の前提」の 008） |
| a-2 | incident の close まで（復旧点より後の実体を回収する（forward recovery）かを incident commander が決め、close を宣言した時点）。日数の上限は置かない | 業務の判断と連動し、回収に要る写しを途中で失わない | 期限が不定で長くなり得る。その間、旧版の個人データは保持期限と DSAR の外に残る（(d) の受理の前提が弱くなる） |
| a-3 | `write-enabled` の後、旧版を backup の repository（restic）へ1回 snapshot して検証し、その後すぐ D15 で消す。保存は backup の保存期間に従う | uploads の mount に旧版が残らず、保存は backup と同じ扱い（暗号化・versioning/immutability）になる | 今の backup は `current` の指す版だけを写し、旧版を写す手段が無い（後続の batch で実装が要る）。取り出しの手順が増える |

- 推奨: a-1。決める人: DBA（運用・容量）と Privacy Owner（個人データの保存期間）。延長の判断は incident commander（008。**owner 未指定**）。

## 3. (b) 旧版の読取り専用化

| 候補 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| b-1 | 手順だけで守る（現状）。保存期間の間は旧版を変更も削除もしないことを change ticket で約束し、削除は D15 の確認を経る | 追加の作業が無い | 同じ uid の restore 主体の誤操作を防げない（app は旧版へ書かないため app からの危険は無い） |
| b-2（推奨） | `write-enabled` の後に、restore 主体が旧版の書込みの mode を外す（例: `chmod -R a-w -- <uploads の根>/v<n>`。directory の書込み権を外すと、その中の作成・削除・改名もできない）。write-enable の前は rollback の先なので行わない（戻すと app が書く）。D15 で消す時は、同じ change の中で書込み権を戻してから消す。前後の `ls -ld` と `uploads-cutover.sh status` の出力を記録する | 簡単で、誤った書込み・削除を止められる。実装が要らない | 所有者（同じ uid）は mode を戻せるため、境界ではなく誤操作の防止にとどまる。削除の前に戻す手順が要る |
| b-3 | `write-enabled` の後に、旧版を app の access point から見えない場所（uploads の access point の根の外）へ移す | app の task から届かず、EFS の access point と file system policy の境界で守れる | 別の mount（file system の根または別の access point）と EFS policy の変更が要る（Security。runbook §1.6 の11）。restore・回収の手順が増える。write-enable の前は使えない |

- 推奨: b-2。決める人: DBA と Privacy Owner（runbook D15「read-only 化を行うかは DBA・Privacy Owner の確認項」）。b-3 を選ぶ場合は Security と storage owner（baseline の PROD-003）も決める。

## 4. (c) 旧版に hold の対象の実体が無いことの確かめ方（go-live の前提）

| 候補 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| c-1（推奨。初回リリース） | tenant 単位の保守的な判定。消す時点で、hold を使った tenant（`t_privacy_projection_state` に行がある tenant）に有効な hold（`t_legal_hold.status = 'ACTIVE'`）が1件でもあれば、旧版を消さない。restore した DB の照会だけで判断せず（§1 の最後）、privacy-admin が hold 画面で、projection が Journal に追随していること（gate が `UNAVAILABLE` でないこと）と有効な hold の一覧を確かめる。確かめられない間は消さない（fail-closed） | 実装も実体ごとの照合も要らない。誤って消す余地が最も小さい | hold が続く間は旧版を消せず、(a) の期限を過ぎても残る（(d) の不及の期間が延びる） |
| c-2 | 実体ごとの照合。旧版にだけある実体（と、同じ名前で内容が違う実体）を一覧し（両方の版の相対 path と SHA-256 の比較）、各保存名を所有の行・本人へ解決して、有効な hold の対象と照合する。両方の版に同じ内容がある実体は `current` の版にあり、D10 の hold の参照（`PrivacyHoldFileReferenceProvider`）で守られる。旧版にだけある実体は restore した DB に行が無いため、restore の前の本番 DB（旧環境を残している間）か Journal の記録で解決する。解決できない実体が1つでもあれば消さない | hold と関係の無い旧版を (a) の期限どおり消せる | 人手の照合が重く、誤りの危険がある。旧環境の DB が要る。手順書と予行演習が要る |
| c-3 | app に読取りの入口を足し（後続の batch）、`PrivacyHoldFileReferenceProvider` と同じ判定で hold の対象の保存名を privacy-admin が出力し、旧版の保存名と機械的に照合する | D10 と同じ判定で、人手の解決が減る | code の変更が要り、本 batch の範囲外（初回リリースには間に合わない）。旧版にだけある実体は解決できない点は c-2 と同じ（解決できなければ消さない、で補う） |

- c-1 の照会の例（本番 DB。読むだけ。最終の判断は hold 画面の確認と合わせる）:
  ```sql
  SELECT tenant_id, journal_id, last_applied_seq, verified_at FROM t_privacy_projection_state;   -- 0行なら hold を使っていない
  SELECT tenant_id, COUNT(*) FROM t_legal_hold WHERE status = 'ACTIVE' GROUP BY tenant_id;          -- 0行なら有効な hold が無い
  ```
- 推奨: 初回リリースは c-1。c-2 は D-05 owner が個別に認めた場合の例外の手順、c-3 は後続の batch の候補。決める人: D-05 owner（runbook D15「確かめ方（照会と記録の形）は D-05 owner が決める」）。確かめるのは privacy-admin、消すのは restore 主体。

## 5. (d) DSAR の削除と保持期限の purge が旧版に届かないことの受理（go-live の前提）

| 候補 | 内容 | 利点 | 欠点 |
|---|---|---|---|
| d-1（推奨） | 届かないことを受理し、残る期間を (a) の上限で限る。DSAR の削除の回答と SOP に「restore の後の旧版と backup には、それぞれの保存期間の間残り、その後に消える」と書く（backup の扱いと揃える。backup の扱いは D-06 の SOP で決める）。保持期限の purge も同じ扱いにする | 実装も人手の追随も要らない。説明が backup と一貫する | 期限を過ぎた個人データが最長で (a) の期間残る。c-1 で hold が続くと更に長くなる |
| d-2 | 人手で追随する。旧版がある間に行った DSAR の削除・保持期限の purge の対象の保存名を記録し、旧版からも同じ名前の実体を D15 の手順で消す（`write-enabled` の後だけ） | 消すべき実体を早く消せる | 人手で誤りの危険がある。旧版を部分的に変えるため、調査・回収の写しとして使いにくくなる。旧版にだけある実体は DB の行が無く対象を導けない（届かない部分は残る）。毎回 hold との順序の確認が要る |
| d-3 | 旧版を短期で消して不及の期間を短くする（(a) を短くする。例: incident の close と `write-enabled` の後7日の早い方） | 不及の期間が短い | forward recovery の調査の時間が足りないおそれがある。hold があれば c-1 で消せない点は変わらない |

- 推奨: d-1（a-1・c-1 と組にする）。決める人: D-06 owner（DSAR の削除）と D-05 owner（保持期限の purge・hold）、Privacy Owner（runbook §4.4 末尾の受理事項 (9)。**未取得**）。

## 6. 推奨の組合せと決めること（統括の案）

| 項 | 推奨 | 決める人・受理する人 | 時期 | 状態 |
|---|---|---|---|---|
| (a) 保存期間 | a-1（`write-enabled` の後30日。延長は incident commander が記録） | DBA・Privacy Owner（延長は incident commander） | go-live の前（(d) の前提のため。推奨） | **未決定** |
| (b) 読取り専用化 | b-2（`write-enabled` の後に書込みの mode を外す） | DBA・Privacy Owner | 最初の本番の restore の前 | **未決定** |
| (c) hold の確かめ方 | c-1（有効な hold が1件でもあれば消さない。hold 画面で Journal への追随を確かめる） | D-05 owner | **go-live の前**（D-05 の受理事項 (9)） | **未決定・未取得** |
| (d) DSAR・purge の不及の受理 | d-1（(a) の上限の間残ることを受理し、SOP と回答に書く） | D-06 owner・D-05 owner・Privacy Owner | **go-live の前**（D-05 の受理事項 (9)） | **未取得** |

- 決まったら runbook §4.4 の D15 の「保持」「hold」「DSAR・保持期間の purge との関係」「read-only 化」の各項を決定の内容に書き換え、本書は経緯として残す。
- 記録は runbook D15 の「記録」のとおり（消した版・残りの名前、日時、確かめた者（hold の確認を含む）、消す前後の `status` の出力を change ticket と配備記録に残す）。b-2 を選んだ場合は mode を外した日時と前後の `ls -ld` も残す。
