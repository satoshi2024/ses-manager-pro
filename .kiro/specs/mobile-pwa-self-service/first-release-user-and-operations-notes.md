# NF-04 初回リリース 利用者向け説明・運用説明（端末の一時保存・対応ブラウザ・構成前提）

- 状態: **草案**。2026-11-13 の決定記録（B-NF04-1 / B-NF04-2 / B-NF04-3）と 2026-11-14 の決定（NF04 P1-N4-1 = 方案A）を利用者・運用者向けに文章化したもの。production NO-GO の解除、Owner・Security・Privacy の署名、および B-NF04-4（撤回・復元方式の受入条件差分。Owner/Security判断）の承認を意味しない。
- 決定:
  - B-NF04-1: 対応ブラウザは spec のとおり維持する（Chrome・Edge・Safari の現行版と1つ前の版、Android Chrome、iOS Safari）。
  - B-NF04-2: 端末内の下書き30日保存と、撤回時の既知の制限を受け入れ、利用者説明と運用説明へ記載する。
  - B-NF04-3: 初回リリースは**単一インスタンス、または sticky session** で運用する。
  - P1-N4-1 = 方案A（2026-11-14）: シングルサインオン（OIDC）でログインしたsessionでは、ロック中の下書きを**復元できない**と明示し、復元入口を隠す。復元APIはパスワード照合より前に拒否し、失敗回数に数えない。未送信の下書きがあるときは、画面を離れる前・logout前に確認する。撤回epoch cookieは定期的に期限を延ばす。ローカルパスワードでログインしたsessionの復元は従来どおり（「復元入口は常時有効」の旧決定はローカルパスワードsessionにだけ引き続き適用する）。
- 技術的な根拠: `requirements.md`（PW-R2）、`revocation-recovery-decision.md`、`static/js/pwa-queue.js`、`static/offline.html`、`static/js/common.js`（logout）、`service/pwa/**`（`PwaRestoreAuthorizationService`）、`controller/api/PwaSessionApiController`・`MyPwaRestoreApiController`、`.kiro/specs/production-launch/feature-switches.md` 4.4。

## 1. 利用者向け説明（要員向け。社内案内へ転記して使う）

### 1.1 対応ブラウザ

- パソコン: Google Chrome、Microsoft Edge、Safari（いずれも最新版と1つ前の版）
- スマートフォン: Android の Chrome、iPhone の Safari
- ホーム画面への追加（インストール）は任意です。追加しなくても通常のブラウザで同じように使えます。
- プッシュ通知はありません。
- Safari には保存期間の制限があります（1.7）。

### 1.2 通信が不安定なときの入力（端末への一時保存）

- 勤怠・作業報告（日次の入力）、経費の下書き、プロフィール変更申請の下書きは、通信が不安定なときやサーバーが一時的に応答しないとき、**お使いの端末（ブラウザ）に一時保存**され、通信が戻ると自動で送信されます。
- 領収書・添付ファイルのアップロード、申請（提出）、承認、休暇申請などは、通信がある状態でだけ行えます。
- 同じ入力が二重に登録されることはありません（再送しても1件として扱われます）。他の人や別の画面で先に更新されていた場合は「競合」と表示されるので、内容を確認して入力し直してください。

### 1.3 一時保存の期間（30日）

- 端末に一時保存した入力は、**保存してから30日間**だけ送信できます。
- 30日を過ぎた入力は自動では送信されず、「保持期間（30日）を過ぎたため送信できません。破棄して再入力してください。」と表示されます。表示に従って破棄し、改めて入力してください（サーバー側でも30日を過ぎた送信は受け付けません）。
- Safari では、30日以内でも一時保存した入力が無効になることがあります（1.7）。

### 1.4 「ロック中の下書き」と復元

- 一時保存した入力があるまま画面を移動・再読み込みすると、その入力は「ロック中の下書き」になり、自動では表示・送信されません。
- **復元できるのは、パスワードでログインしている場合だけです。** 同期パネルの「下書きを復元」を選び、**パスワードを入力して本人確認**したうえで「下書きを復元する」を選ぶと、内容が表示されて送信を再開できます。本人確認には通信が必要です。通信が無い間はロックされたままです。
- **シングルサインオン（社内の共通ログイン）でログインしている場合、ロック中の下書きは復元できません。**
  - 同期パネルに「ロック中の下書き N 件は、現在のログイン方式（シングルサインオン）では復元できません。」と表示され、「下書きを復元」は表示されません。復元画面にもパスワード欄は表示されず、「この下書きは復元できません。」と表示されます。
  - パスワードを入力する必要はありません。入力しても照合されず、アカウントのロック（5回失敗で30分）の原因にもなりません。
  - ロック中の下書きは、ログアウトしたとき、または保存から30日後に端末から削除されます。必要な内容は通常の画面で再入力してください。
- **シングルサインオンの場合は、未送信の入力がある間はその画面を離れないでください。**
  - 同期パネルに「未送信 N 件。送信が終わる前にこの画面を離れると、この下書きは復元できません。」（通信が無いときは「オフライン: 未送信 N 件。…」）と表示されます。
  - 未送信の入力がある状態で画面を閉じる・移動・再読み込みしようとすると、ブラウザが確認を表示します。ただし、ブラウザの仕様により、画面を一度も操作していない場合などは確認が表示されないことがあります。
  - 画面を切り替える・閉じるときには、通信があれば送信を試みますが、送信が終わることは保証されません。通信が戻ると自動で送信され、送信が終われば確認は表示されなくなります。
- 端末の時計は「自動設定」にしておいてください。端末の時計がサーバーの時刻より5分以上進んでいると、一時保存した入力の送信がサーバーに受け付けられません（サーバーは送信に付いた作成時刻を検査します。`PwaClientMutationLedgerService`）。

### 1.5 ログアウト・利用者の切り替え

- ログアウトしたとき、または同じ端末で別の利用者がログインしたときは、その端末に一時保存されていた入力（ロック中の下書きを含む）は削除されます。**未送信の入力がある場合は、送信が終わったことを確認してからログアウトしてください。**
- 未送信またはロック中の下書きがある状態でログアウトしようとすると、「この端末に未送信またはロック中の下書きが N 件あります。ログアウトすると端末から削除され、復元できません。ログアウトしますか？」と確認が表示されます。「キャンセル」を選ぶとログアウトせず、下書きも削除しません。
- 共用の端末を使った場合は、使い終わったら必ずログアウトしてください。

### 1.6 既知の制限（ログアウト・権限変更の直後）

- 通常は、ログアウトや権限の変更をすると、同じ端末で開いている他のタブの下書きも直ちに隠されます。
- ただし、**端末の保存領域への書き込みと、他のタブへの通知が、すべて同時に失敗した場合**（端末の容量不足やブラウザの保存機能の制限など）は、既に開いている他のタブに表示されている下書きが**直ちに消えることは保証されません**。その場合も、そのタブで操作・再表示・送信をしようとした時点で本人確認と権限の再確認が行われ、送信や再表示は止まります。
- 削除できたことを確認できない場合、削除済みとは表示しません。気になる場合は、そのブラウザのタブをすべて閉じてください。

### 1.7 Safari（iPhone・Mac）の保存期間の制限

- Safari は、ウェブサイトが端末に保存した情報の保存期間を短く制限しています（Intelligent Tracking Prevention）。本システムは画面を開く・同期するたびに期限を延ばしますが、**Safari で7日以上本システムを開かなかった場合は、30日以内でも、一時保存した入力が無効になる（表示・送信・復元できなくなる）ことがあります**。
- Safari をお使いの場合は、未送信の入力を何日も残したままにせず、通信のある場所で本システムを開いて送信を済ませてください。

## 2. 運用向け説明

### 2.1 構成の前提: 単一インスタンス または sticky session（B-NF04-3）

| 理由 | 内容 |
|---|---|
| ログインsessionがメモリ上にある | 外部のsession storeを持たないため、instanceをまたぐとsessionが見つからない（CLAUDE.md「Capacity and concurrency」）。再起動すると全員がログアウトする |
| 下書き復元の本人確認がinstanceに結び付く | 復元用の再認証（`/api/my/pwa/restore/reauthenticate`）で発行するproof（有効120秒・1回限り）の状態は、発行したinstanceのメモリ上のHTTP sessionに保持する（`PwaRestoreAuthorizationService`。復元後の許可は最大15分）。再認証と確定（`/confirm`）が別instanceへ振り分けられると、確定は失敗する（安全側に拒否され、利用者はやり直しになる）。OIDC sessionでは復元自体を行わない（2.4） |
| 撤回の記録もinstance内 | logout・session破棄の記録（遅れて届いた応答を拒否するための失効世代）は、`PwaRestoreSessionListener` が各instanceのメモリ上の `PwaRestoreSessionRegistry`（`ConcurrentHashMap`、60分保持）へ書く。他のinstanceには共有されない |

- したがって、複数instanceで運用する場合は**同じ利用者の要求を常に同じinstanceへ送る sticky session（session affinity）を必須**とする。sticky sessionを使わない複数instance構成は初回リリースでは非対応である。
- sticky session構成でも、instanceの停止・入れ替え時には、そのinstanceに結び付いたsessionと復元許可は失われる（利用者は再ログイン・再認証になる。下書き自体は端末に残り、パスワードで再ログインした場合は1.4の手順で復元できる。シングルサインオンの場合は復元できない）。
- sticky sessionの実機での実証（分散session topology）は production gate に残っている（`revocation-recovery-decision.md`）。sticky sessionなしで台数を増やすには、registryの共有化（DDLを伴う。backlog A-NF04-3）が前提となる。

### 2.2 保存期間の設定値

| 対象 | 値 | 設定 | 変更可否 |
|---|---|---|---|
| 端末内の下書きの有効期限 | 30日（Safariは2.4の制限あり） | `static/js/pwa-queue.js` の固定値 | 設定では変えられない |
| サーバーが受け付ける送信の古さ | 作成から30日以内（`X-Client-Created-At` をサーバー時刻で検査。超過は `QUEUE_EXPIRED`） | `PwaClientMutationLedgerService` の固定値 | 設定では変えられない |
| サーバーの二重登録防止台帳（idempotency ledger）の保持 | 既定30日。毎日03:20に削除 | `PWA_QUEUE_RETENTION_DAYS`、`PWA_QUEUE_CLEANUP_CRON` | 変更可。ただし**30日未満にしないこと**（30日以内の再送を二重登録として検出できなくなる） |

- `PWA_QUEUE_RETENTION_DAYS` を変えても、端末の下書きの期限（30日）は変わらない。
- 端末の保存期間30日は利用者の決定（B-NF04-2）で受け入れたものであり、Privacy の署名は別途必要である（4章）。

### 2.3 機能スイッチとの関係

- `FEATURE_NF04_ENABLED` で止まるのは PWA shell（Service Worker とインストール用manifest）だけである。日次入力の一時保存・送信、ロック中の下書きの復元（ローカルパスワードsessionだけ。2.4）、server側台帳の削除は、スイッチに関係なく常に有効である（`feature-switches.md` 4.4）。
- NF-04 をOFFにしても、1.2〜1.7 の説明は変わらない（オフライン中に画面を開き直せなくなることだけが異なる）。
- Service Worker のshell cacheは版（`ses-pwa-shell-v4`）で入れ替える。`pwa-queue.js`・`offline.html` 等のshell資産を変えたときは `service-worker.js` の `CACHE_NAME` も上げる（cache優先で配るため、上げないとSW登録済みの端末へ新しい資産が届かない）。

### 2.4 既知の制限（運用者向けの正確な記述）

**撤回時の制限（B-NF04-4）**

- 全ての永続化経路（IndexedDB・localStorage・cookie）への書込みと、全タブへの通知（BroadcastChannel・storage event）が同時に失敗した場合、撤回（logout・利用者切替・権限変更）後に、既に表示済みの他タブのdraftを瞬時に消去することは保証しない。focus・visibility復帰・送信前の再検証で再利用は止めるが、これを瞬時消去とは呼ばない（`revocation-recovery-decision.md`）。
- この残余リスクは利用者の決定（B-NF04-2）で受け入れたが、受入条件（PW-R2.7 の logout 時即時物理削除）との差分の承認は B-NF04-4 として Owner/Security の判断待ちである。
- 問い合わせ対応では、「ログアウト後も他のタブに下書きが表示されていた」という報告は上記の既知の制限に該当し得る。該当タブで送信・再表示ができない（本人確認が求められる）ことを確認し、タブをすべて閉じるよう案内する。

**OIDC sessionでは復元しない（P1-N4-1 方案A）**

- 本番はOIDCが必須で、要員のローカルパスワードによるloginは無効である（`ProductionSecurityConfigurationValidator`、`application-prod.yml` の `oidc.local-login-enabled`）。要員は通常ローカルパスワードを知らず、IdPのMFAやパスワード方針も及ばないため、ローカルパスワードを「本人確認」として使わない。
- 判定はserver側sessionの認証主体の型だけで行う（client申告は使わない）: `UsernamePasswordAuthenticationToken` + `LoginUser`（form login）だけを「パスワード再認証に対応」とし、`OAuth2AuthenticationToken` + `OidcLoginUser`（OIDC login）とそれ以外の型は対応しない（fail-closed。`PwaRestoreAuthorizationService.supportsPasswordReauthentication`）。
- `GET /api/my/session-context` は `restoreAvailable`（true/false）を返す。画面はfalseまたは未確認のとき「下書きを復元」入口・パスワード欄を出さない。
- `POST /api/my/pwa/restore/{reauthenticate,confirm}` は、OIDC等のsessionからの要求をパスワード照合・DB読取・`AccountLockService` の失敗加算より前に拒否する。応答は HTTP 403、`data.type` と `X-SES-Error-Code` が `PWA_RESTORE_REAUTH_UNSUPPORTED`、文言は `error.pwa.restore.reauthUnsupported`（4言語）。監査ログにはstatus 403として残る。
- 結果として、OIDC sessionで画面移動・再読み込み・ブラウザ終了をまたいだ未送信の下書きは復元できず、logout時または30日後に削除される。これを減らすため、復元できない（または未確認の）sessionで現在の画面に未送信の下書きがある間は `beforeunload` の確認を出し、画面が隠れる・離れるとき（`visibilitychange`・`pagehide`）に送信を試みる。logout時は、未送信またはロック中の下書きがあれば確認を取る（Service Worker非対応の環境でも同じ。`common.js`）。ブラウザは利用者操作の無い画面で `beforeunload` の確認を出さない場合がある。
- 復元をOIDC sessionでも可能にするには、IdPのstep-up再認証（`prompt=login`・`max_age=0`・`auth_time` 検証とdocument nonceの結合）が必要であり、Plan ReviewとSecurity設計審査を経た別batch（方案B）とする。

**Safari（ITP）の保存期間**

- 撤回epochはlocalStorageとcookie（`ses_pwa_revocation_epoch`、`SameSite=Strict`、HTTPSでは `Secure`）の二重記録で照合し、不一致は `unverified` として旧下書きを隔離する（fail-closed）。
- Safari（ITP）はscriptで書いたcookieの期限を最終書込みから7日に制限する。online bootstrap（画面表示・同期）のたびに、照合済みの同じ値でcookieだけを書き直して「最後の利用から7日」まで延ばす（localStorageは書き直さない。他tabの撤回と交差しても不一致として隔離側に倒れ、旧epochを復活させない）。
- server側の `Set-Cookie` で配る案は採らなかった。ITPは7日間利用の無いサイトのscript書込み可能な保存領域（IndexedDB・localStorage）自体も削除するため、cookieだけを長く残しても下書きは残らず、clientの値をserverが反射するheaderの検証とlogout直後の古い応答による上書き競合が増えるためである。
- したがって、**Safariで7日以上利用が無い場合、30日以内でも下書きが無効になり得る**（cookie失効による `unverified`、またはITPによる保存領域の削除）。実機での確認は 2.5 の受入記録（`real-device-browser-acceptance-template.md` の S-07）で行う。

### 2.5 対応ブラウザの検証状況と受入記録

- 自動テストで確認しているのは Chromium系（Chrome。CDP経由）である。Edge・Safari・Android Chrome・iOS Safari の実機受入は、業務受入者による手動確認が必要である（backlog A-NF04-2）。Safari のITP（2.4）は実機でしか確認できない。
- 手動受入は `real-device-browser-acceptance-template.md` の確認項目と記録様式を使う（記録先: 本specの `review-ledger.md`）。

## 3. 問い合わせ対応の早見表

| 利用者の申告 | 確認すること | 案内 |
|---|---|---|
| 一時保存した入力が送信されない | 30日を過ぎていないか、「ロック中の下書き」になっていないか、通信があるか | 1.3 / 1.4 の手順 |
| 下書きが消えた | ログアウト・別利用者のログインをしていないか、30日を過ぎて破棄していないか、Safariで7日以上開いていなかったか | 1.3 / 1.5 / 1.7。消えた入力は再入力してもらう |
| 「復元できません」と表示される・「下書きを復元」が無い | シングルサインオンでログインしているか | 1.4。シングルサインオンでは復元できない（仕様）。再入力してもらう。パスワード入力は不要で、ロックの原因にもならない |
| 下書きを復元しようとすると失敗する（パスワードでログインしている場合） | 通信があるか、パスワード誤りでアカウントがロックされていないか（既存の5回失敗/30分ロックを共用）、端末の時計 | 複数instance構成なら sticky session が効いているかを運用側で確認する（2.1） |
| 画面を離れようとすると確認が出る | 未送信の下書きがあるか（シングルサインオンの場合） | 1.4。通信のある状態で送信が終わるのを待ってもらう |
| 「競合」と表示される | 別の画面・別の人が先に更新していないか | 内容を確認し、端末版を破棄して入力し直す |

## 4. 承認・署名（空欄。各担当が記入する）

| 対象 | 役割 | 氏名 | 日付 | 結果 | 備考 |
|---|---|---|---|---|---|
| 1章 利用者向け説明の文面 | 業務Owner | | | | |
| 2.1 単一instance / sticky session の構成 | SRE / Operations | | | | 分散session topologyの実証はproduction gate |
| 2.2 端末内30日保存 | Privacy | | | | B-NF04-2 は利用者の決定。Privacy署名は別 |
| 2.4 撤回時の既知の制限（B-NF04-4 受入条件差分） | Owner / Security | | | | 判断待ち |
| 2.4 OIDC sessionでは復元しない（P1-N4-1 方案A） | Owner / Security | | | | 2026-11-14 利用者決定。方案B（step-up再認証）は別batch |
| 2.5 実機受入 | 業務受入者 | | | | iOS / Android / Safari / Edge。`real-device-browser-acceptance-template.md` |
