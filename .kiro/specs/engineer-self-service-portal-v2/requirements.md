# Requirements — 要員セルフサービスポータルV2

## 前提

- 既存`/my/timesheet`を拡張し、内部要員アカウントを利用する。外部BP portal userとは混同しない。
- masterを本人が直接上書きせず、影響のある変更は申請/承認後に反映する。

## R1. プロフィール/スキル変更申請

1. THE 要員 SHALL 住所等を除く公開プロフィール、最寄駅、連絡先、希望条件、skill、careerの変更を申請できる。
2. THE 申請 SHALL before/after、理由、添付、承認状態を持ち、HR承認後に既存Engineer/Skill/Career serviceへ反映する。
3. THE スキルシート公開項目 SHALL 本人previewでき、客先提出前の確認日を記録する。
4. THE 本人 SHALL 自分の担当営業、現在契約の公開条件、契約終了予定を閲覧できるが、原価/commissionを見られない。

## R2. 給与/勤怠/休暇

1. THE 要員 SHALL freee連携済み給与/賞与明細を本人だけ閲覧できる。
2. THE 給与明細 SHALL 一覧に金額を不用意に露出せず、再認証またはMFA後に詳細表示できる。
3. THE 要員 SHALL attendance specの勤怠/休暇、既存作業報告へ1つのmy dashboardから遷移できる。

## R3. 経費

1. THE 要員 SHALL 交通費/立替経費を日付、区分、金額、顧客/案件、理由、領収書付きで申請できる。
2. THE 経費 SHALL 下書き→申請→承認/差戻し→会計連携済/支払済の状態を持つ。
3. THE 領収書 SHALL archive/scan/本人scopeを使い、承認後の差替えは再申請する。
4. THE accounting integration SHALL 承認済経費を外部会計へ冪等送信できる。

## R4. 1on1/フォロー/サーベイ

1. THE 要員 SHALL 担当営業/上長との1on1候補日を申請し、実施記録の本人公開部分を閲覧する。
2. THE 要員 SHALL 稼動満足度、負荷、継続意向、相談希望を定期回答できる。
3. THE confidential相談 SHALL 閲覧者をHR/指定管理者へ限定し、通常営業画面へ自由記述を出さない。
4. THE retention risk SHALL 回答値を入力に使えるが、自動人事判断を行わず理由を表示する。

## R5. 受入

- 本人Aが本人Bの給与/経費/相談を取得できない。
- profile申請承認前はEngineer master不変、承認後1回だけ反映。
- 経費二重連携なし、領収書感染/未scan時閲覧不可。

