# NF05 法人境界 backfill 運用手順

## 目的

V157/V160 は既存データを推測で法人へ割り当てず、V158 は関係から一意に確定できる行だけを監査付きで backfill する。V2 の初期データは法人を持たないため、fresh install直後は `LegalEntityReadinessService` が `503 / LEGAL_ENTITY_BACKFILL_INCOMPLETE` を返す状態を正とする。これにより、確認前のデータが External API や Copilot へ公開されない。

## authoritative source と手順

1. deployment tenant と、OIDC/security organization binding が示す authoritative legal entity ID を運用責任者が確定する。クライアント payload、会社名、opaque/public ID からは決めない。
2. authoritative な `m_organization_unit.legal_entity_id` と security/user の有効所属を先に設定する。複数法人候補または候補なしの場合は更新せず、監査対象として残す。
3. V158 を適用または再実行し、customer は手動で承認された法人だけを設定する。project は customer、engineer は authoritative organization、contract は project/engineer/customer が全て同一の場合、invoice は customer からのみ解決する。
4. 既存値と関係先が不一致の行、NULLの行、deletedではない未束縛行は `t_legal_entity_backfill_audit` の `UNRESOLVED` として修正対象にする。値を上書きして合わせてはならない。
5. `LegalEntityReadinessService.assertReady()`、health、public API/Copilot の fail-closed テストを実行し、全ての active core/join graph と side resource が同一法人になったことを確認する。
6. readiness が UP になるまで `ai.management-copilot-enabled`、`ai.external-send-enabled`、production public API を有効化しない。実providerの承認・設定はこの手順の範囲外であり、実施しない。

## 監査と再実行

V158 は同じ `(entity_type, entity_id, decision)` の監査を重複登録しない。修正後に再実行して `RESOLVED`/`UNRESOLVED` の記録と実列を照合する。部分適用または失敗時は未解決行を公開せず、原因を解消してから同じ手順を再実行する。
