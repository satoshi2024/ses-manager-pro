# NF01 R01b 技術計画の独立Reviewと追加決定

- Reviewer: `astra_review_baseline` / gpt-6-astra、read-only。
- 判定: **PLAN CONDITIONAL PASS**。以下の契約補完が必要。production承認なし。
- 固定実装参照: `8ad0c34e`。R01aの並行scope修復は本Review対象外。

## 二者確認

総括の技術選択: 第一操作は既存task scopeを持つactive担当主体による提出、第二操作は同tenantで
当該task/evidenceへ現在アクセスできる別のactive管理者/HRによる確認とする。managerを第二確認者に含めない。
taskは提出時IN_PROGRESSに保ち、confirmationを別状態で管理する。初回操作の再送は第二確認にしない。

実装前に以下を具体的schema/API/locking表へ落とし、固定Headで再Reviewする。

1. case/task/engineer、task generation、証拠canonical hash、正確なdocument version/content hashを
   immutable revisionへbindingする。identity/hashはserver生成、clientはexpected revisionのみ指定する。
2. 双方のactive/tenant/現在scopeを明示主体の認可APIで再検証する。他人の権限照会をcurrent SecurityContextで代用しない。
3. evidence変更、reassign、case/task holdはpending revisionを無効化し、resumeで復活させない。
4. case→task→evidence/confirmationのlock順を統一する。hold/cancel/confirmも同じ規則で直列化する。
5. COMPLETE、二者identity、terminal eventは同じtransaction。完成後の訂正は追記のみ。
6. 同人確認、偽verifier、失効権限、evidence/担当/hold変更、並行確認、途中rollback、通常task互換を検証する。

既存のclient入力evidenceDataJsonだけで信頼済み確認を表現しない。新migration番号は未割当。

## Document evidence

`DocumentServiceImpl.detail`はアクセス確認をするが、downloadのFileScope検査にはcontroller側処理がある。
`DocumentService.download()`を呼ぶだけでACL検査が完結すると仮定しない。
NF01のdocumentVersionIdとDocumentServiceのversionNoを混同しない。

明示的evidence検証facadeでtenant、既存ACL/FileScope、document/version帰属、CLEAN、非廃棄・非隔離、
固定version hashを検証する。存在しないversionを最新版へfallbackしない。
documentを付す全evidence typeに適用し、linkを閲覧権限の付与として扱わない。
detail/download/第二確認時に現在の可視性を再検証し、非公開title/path/metadataを返さない。

回帰: 同tenant別scope、跨tenant、別documentのversion、隔離/廃棄、後続version作成、権限撤回、
download入口、document状態変更と確認競合。

## NF09 撤権要求と確定の分離

実コードの`ExternalAccountServiceImpl.requestRevokeWithIdempotency`はNOT_SUPPORTEDで、
claimを保存してからproviderを直接呼ぶ。同key再送はreturnするため、claim後・送信前crashを回復できるとは言えない。
pollを未送信要求の再送と同一視しない。外側case/task lockを持ったままNOT_SUPPORTEDを呼ぶことも禁止する。

総括の技術選択:

- ACTIVE RESIGNATION caseのadmin/HRが明示的に開始する。短transactionでtenant/legal entity/engineerを検証し、
  actor、generationとdurable revoke request/outboxを記録する。
- commit後workerが送信する。送信と確認のstatus/attempt/lease/安定idempotency key/失敗/retryを分離する。
- 多accountの部分結果を保持し、二重開始は需要を二重作成しない。
- 要求後hold/cancelでも既に確定した撤権を自動復元しない。commit済み安全撤権は継続し、その事実を表示する。
- PENDING/UNKNOWN/送信失敗はblocker。可信確認または既存の有効waiver以外では解除しない。
- finalization時に再確認する。新規account/asset/license割当と同じengineer/offboarding fenceを使い、
  最終check直後の割当競合を排除する。各割当入口への変更範囲を実装計画で列挙する。

回帰: 提出後送信前crash、未受理provider、部分成功、重複操作、2 worker、遅延確認、hold/cancel、
finalizationと新規割当競合、fail-closed providerの実service chain。
synthetic providerはprotocol検証専用であり、実撤権・waiver・production承認の代替にはしない。
