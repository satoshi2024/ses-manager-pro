# Requirements — 派遣・準委任コンプライアンス台帳

## 前提

- G2は厚生労働省の公式様式を開発baselineとする。公式URL/版/確認日/effective periodを持つprovisional mappingが
  L0と独立Reviewを通過すれば`PROVISIONAL_REVIEWED`として後続開発を開始できる。特定の社内責任者を開発時に
  固定しない。runtimeの社内責任者assignment、対象version/hashへの実actor承認event、外部社労士/弁護士Reviewは
  `ACTIVE`化、M PASS、本番交付のgateとする。派遣元/先管理台帳は派遣終了日起算3年をbaselineとし、legal holdは延長する。
- システムはリスクと不足を提示するが、契約形態の法的適否を自動確定しない。

## R1. 就業先/責任者/契約条件

1. THE システム SHALL 就業事業所、組織単位、業務内容、就業場所、就業時間、休憩、休日、時間外、指揮命令者、派遣先/元責任者を管理する。
2. THE 派遣契約 SHALL 派遣期間、抵触日、待遇方式、苦情窓口、教育訓練、安全衛生、社会保険通知、派遣料金を持つ。
3. THE 準委任/請負 SHALL 責任分界、成果/役務、作業指示経路、再委託可否、検収方法を持つ。
4. THE 項目 SHALL 契約時snapshotを保持し、マスタ変更で過去帳票を変えない。

## R2. 台帳/帳票

1. THE システム SHALL 派遣元管理台帳、就業条件明示書、派遣先通知書、個別契約書のデータを生成できる。
2. THE 帳票 SHALL document archiveへ保存し、版/交付日/交付方法/受領確認を追跡する。
3. THE 月次就業実績 SHALL 既存勤怠/検収データから派遣先通知用に集計できるが、雇用勤怠と客先工数の差異を表示する。

## R3. リスク/期限

1. THE システム SHALL 既存の直接指揮、多重派遣、契約形態不整合に加え、抵触日、責任者欠落、明示書未交付、保険未確認、期間外稼動を検査する。
2. THE 準委任/請負 SHALL 顧客による個人への直接指示記録、勤怠承認者、作業指示経路から偽装請負リスクを警告する。
3. THE 抵触日/文書期限 SHALL 90/60/30日前通知し、後続契約/組織単位変更を考慮する。
4. THE finding SHALL acknowledged/対応中/解消/例外承認と根拠文書を持つ。

## R4. 権限/個人情報

1. HR/法務/管理者だけが個人別台帳と待遇情報を閲覧し、営業は契約に必要な限定項目だけを見る。
2. export/download SHALL 同じfield permissionとscopeを適用する。

## R5. 受入

- mappingは`DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`を持ち、開発baselineと本番有効版を混同しない。
- runtime責任者未指名、承認event未取得、外部専門家Review未取得では`ACTIVE`化および本番帳票交付をfail-closedにする。
- 派遣1件の必要帳票を同一snapshotから再生成し、版差分を説明できる。
- 抵触日30日前、期間外工数、責任者欠落を検知。
- 準委任のdirect command flagだけでなく指示経路/承認者不足を表示。
