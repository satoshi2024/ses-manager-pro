# Repository key escrow / rotation（HFP-03-010 / AC-008-03）

## 1. 前提と役割分離

- repository への書き込みは backup role、読み取りは restore role、
  **削除（forget/prune）は retention role のみ**が行える（AC-008-02）。
- `retention.sh` は `RETENTION_ROLE=retention|admin` を必須とする。
  これ以外の role（writer など）では dry-run も含めて実行できない。

## 2. Key escrow

- repository key（`RESTIC_PASSWORD_FILE` の内容）は escrow に保存し、
  通常運用では使わない read-only 経路から参照すること。
- escrow のバックアップはリポジトリ本体と異なる媒体・拠点に置く。

## 3. Rotation（rotate-key.sh）

```bash
rotate-key.sh --new-key-file <new-key>
```

手順（失敗したら切替えない）:

1. 旧キーで最新 checkpoint/full の `restic restore --verify` が成功することを確認
2. 新キーでも同じ snapshot の restore verify が成功すること（旧・新両方が読める）
3. 両方成功した場合のみ `RESTIC_PASSWORD_FILE` を新キーへ atomic 切替
4. 切替後に新キーで再 verify して完了

- いずれかの手順で失敗した場合は非 0 で終了し、**切替えは行われない**
  （旧キーで運用継続。障害原因の復旧後に再実行する）。
- 切替後 30 日は旧キーを escrow に残し、復元確認が終わってから廃棄する。

## 4. Retention（retention.sh）

```bash
# dry-run（変更なし。restic に触れない）
retention.sh --dry-run

# apply（report 再計算 → 一致確認 → 二者承認 → maintenance lock → forget --prune）
retention.sh --apply --report <report.json> --approval <claim1> --approval <claim2>
```

- 保持ポリシー（既定値）: PITR 30 日（window 内の全 checkpoint）+
  window 前 30 日は日次代表 + 週次代表 8 週 + 月次代表 12 ヶ月 +
  weekly 8 / monthly 12 の full-only アーカイブ。
- 各保持 checkpoint のチェーン（base full + binlog + uploads snapshot）は
  削除しない。チェーンが不完全（`PITR_AVAILABLE=false`）の checkpoint は
  dry-run report に明示される。
- apply は report を再計算し、dry-run と一致する場合のみ受理する（AC-009-03）。
- prune は maintenance lock（bounded timeout）下で実行。取得できない場合は
  非 0 で終了し alert 対象とする（AC-009-02）。

## 5. 障害時

- dependency が不正（削除候補の取り違え）であれば何も削除せず非 0 で終了。
- prune 失敗は repository repair を自動実行せず、通常ジョブを停止して
  本 runbook の手順で対応する（無断の auto-repair は行わない）。
