# NF04 撤回・復元境界の追加判断

状態: **技術修復案 / 追加Plan Review待ち / production NO-GO**。総括は安全側の技術方式を選定するが、Ownerの受入条件変更承認やPrivacy/Legal署名を代行しない。従来のrequirementsは履歴として変更しない。

## 固定されたReview結果

Astraは2026-10-02、B0 `1443fb68943849a3260d7ebe005259f3eb609d60`上の凍結WIPを静的Reviewし、**CODE FAIL**とした。Chrome run 12は1/0/1/0であり、全NF04のPASSではない。

- P1: localStorage書込・削除、cookie書込、IDB clearがすべて失敗すると、旧epoch同士の一致が残る。現在tabのquarantineをdraft読取が無視し、cold startも撤回履歴を失って旧scopeを復元する。meta tombstoneの書込だけで読取検証がない。
- P1: offline画面が一度表示したdraft DOMを、別tabのlogout後も残す。非同期読取が撤回後に完了して再表示する経路も閉じる必要がある。
- P2: Date.now依存のTTL/leaseは時刻巻戻りで期間が延びる。撤回tokenの時刻非依存性だけではこの検証を代替しない。

対象source SHA-256:

| File | SHA-256 |
|---|---|
| common.js | 2386B52ED1832FD2CAA83543E6799839CB863C24DD6327F527B9BDDDF57D530F |
| pwa-queue.js | E7570E7B0246A601D2EEA2BA77961FA0563FDEF9C8B3A1FF1015B29A6015FCA3 |
| offline.html | C50C56AF93AFB775B93F3819EACC55B01D897310EB990DB349D628BE1AB3DC2B |
| service-worker.js | 04A567DE76234ED76139ABD9C0DC60CFC630B86029F8D488E7E1F647592E6FB9 |
| CdpBrowser.java | C2F43888987918CB219B2BD3C98D98C42BE128C78E0C12C89F423B58318EE3EE |
| PwaAssetContractTest.java | BC261E25F747277430B0D6CE6582238BEB60DEE4AEB676D0ECD37A3A4A2AE08D |
| PwaQueueChromeIsolationBrowserTest.java | 62982F69D675E567B5A30B59AC466E55F9009E0FBC64D67065884EE9748F6833 |

## 区別できない障害状態と選定方針

全永続化経路が書込を拒否した場合、「正常に残ったdraft」と「logoutしたが撤回を記録できなかったdraft」はcold startから区別できない。旧値二つの一致、未検証のmeta値、Date.now、通常session-context GETを追加するだけでは解決しない。offline logoutでserver revokeも未確認なら、旧session cookieを使うGETは同一userの復元を自動許可し得る。

総括の選定案は、新documentで過去draftを自動表示・自動送信せず、**オンラインでの対話的な再認証と、そのdocumentに結合した短命proofの検証後に、本人が明示的に復元する**方式である。現在documentの検証済み最小draft操作はofflineでも継続できるが、logout/quarantine/期限切れ/未知時刻は直ちに停止する。旧proof、他tab proof、永続storage値だけを復元許可にしない。

これはPW-R2受入条件のbrowser再起動復元に追加条件を課す。PW-R2.7のlogout時即時物理削除も、storage障害中には保証できない。**受入条件差分は未承認のまま残す**。処理不能時は削除済み表示をせず、隔離と未消去状態を明示する。正常系の成功削除と、障害時の読取・送信停止を別の証拠として提出する。

## 実装前に固定する契約

1. 全draft read/write/renderは共通の状態gateを通す。撤回通知で全draft DOMを同期的に空にして隠す。非同期読取にはdocument generation/epochを渡し、完了直前にも照合する。
2. 新document nonceは暗号学的乱数で生成し、memoryだけに保持する。復元は既存session/CSRF/現在DB user・role・engineer linkとpassword再確認を通る専用server経路で認可する。通常GETから復元proofを発行しない。
3. server proofはtenant/user/session/document nonce/対象scope/有効期限へ結合する。passwordやproofをqueue、CacheStorage、localStorage、ログへ保存しない。旧session・別tab・nonce差替・期限切れ・role/link失効は拒否する。失敗時のrate limit、監査の秘密情報除外、応答safe errorも設計する。
4. 復元は明示操作に限定し、serverが同一user bindingを認めた最小draftだけを扱う。既知の撤回tombstone/epoch不一致は再認証だけで無視しない。削除ACK不能時は自動再送しない。
5. 時刻はserver基準時刻とdocument内単調経過時間を組み合わせる案とし、未知/逆行/大きな乖離はquarantineと再検証へ倒す。30日expiryとlease timeoutを別々に検証し、reloadで時間疑義を解消したと推定しない。具体的skew許容値とAPI応答契約は追加Plan Reviewで固定する。
6. endpoint/service/DTO/testの追加範囲はPWA専用とする。既存NF10のreauth grantを目的外流用しない。全体SecurityConfigやmigrationの変更が必要なら別途差分を審査する。

## 必須回帰と残存harness修復

- 3 durable経路同時失敗・旧queue残存: 現在tab、他tab、同profile cold startで旧内容を表示/送信しない。通常GETでは解除されず、fresh reauthと明示復元の許可/拒否を別々に確認する。
- offline DOM表示後の別tab logout、読取promiseをbarrierで止めた状態でlogout、遅延完了で再表示しない。
- TTL満了後のwall clock回退、lease保持中の回退、疑義状態からrestart。期限切れqueueの送信0をserver時計でも検証する。
- v1→v2実IndexedDB upgradeで過大conflictが残らないこと。
- CDP window.openはprimitiveを返す。queue全件空を仮定せず、対象request ID削除と既存conflict保持を別々に検査する。
- lease旧ownerの遅延結果はconflict/errorまたは後継record改変を試みさせ、guard拒否の可視差を作る。HTTP barrier timeoutをassertしfinallyで解放する。重要なCDP変更命令は結果を確認し、evaluateのnullを成功扱いしない。
- synthetic HTML stubは実Spring認証を証明しない。専用APIのMVC/session testsと、実Springを使うreal Chromeで再認証・A→B・失効・offline→onlineを確認する。

追加Plan Reviewと実装・独立CODE Reviewが済んでも、OwnerによるAC差分承認、stagingとproduction enablementは別gateである。
