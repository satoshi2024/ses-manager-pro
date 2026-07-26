# Requirements — 組織・管理会計

## R1. 組織マスタ

1. THE システム SHALL 法人配下に階層組織（事業部/部/課/チーム）を有効期間付きで管理する。
2. THE ユーザー SHALL 複数組織に所属でき、主所属、役職、上長、所属期間を持つ。
3. THE 組織改編 SHALL 過去実績の所属を上書きせず、異動履歴を保持する。
4. THE 組織削除 SHALL 参照中なら禁止し、無効化/統合先指定で運用する。

## R2. 原価部門・管理会計軸

1. THE システム SHALL cost centerを管理し、契約/要員/請求/BP支払に既定配賦先を設定できる。
2. THE 月次実績 SHALL work month時点の組織/cost center snapshotで集計し、現在所属変更で過去を変えない。
3. THE 管理会計 SHALL 法人・組織・cost center・顧客・案件・営業別に売上、原価、粗利、待機費、予算差を表示する。
4. THE 予算 SHALL 月別の売上/粗利/稼働人数/採用人数を組織単位で入力またはCSV取込できる。

## R3. 組織スコープ

1. THE 管理者 SHALL 全件、部門責任者 SHALL 自組織と子組織、一般ユーザー SHALL 既存role/data scope範囲だけを閲覧する。
2. THE 組織scope SHALL menu roleや営業担当scopeを置換せず、両方の積集合/和集合規則を明文化する。
3. THE export/notification/dashboard SHALL 同じ組織scopeを適用する。

## R4. 効果・受入

- 人事異動後も過去月の部門損益が変わらない。
- 上長が配下の承認対象とKPIだけを閲覧できる。
- 全社合計と組織別合計の総和が一致する。

