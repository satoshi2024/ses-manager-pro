# G5/Provider/Spec Version Spike Result

## 1. Provider (G5決定済)
- **事業者**: ファーストアカウンティング株式会社
- **提供API**: Peppol Access Point API
- **契約・sandbox**: 未取得。公式fixture/mockを用いて開発（F1/F2）を進める。
- **Blocker**: B1/B2/Mの結合テストは、実際のprovider sandbox環境の証跡なしにはPASSとしない。sandbox環境の手配を発注者に要求する。

## 2. JP PINT Version
- **最新仕様**: JP PINT (Peppol BIS Standard Invoice JP PINT) Ver. 1.1.3 (2026年6月8日更新)
- **方針**: 本システムはVer. 1.1.3をベースに送信フォーマット(CanonicalInvoice -> XML)を実装する。
- **無検証自動upgradeの禁止**: デジタル庁が新versionを発表しても自動的にupgradeしない。送信runテーブルには必ず使用version (`1.1.3`) を記録し、新しいversionへの移行は明示的なconfig変更と公式fixtureの再検証を伴うものとする。

## 3. Delivery Preference
- **前提の再確認**: 既存のPDF/email送付機能は廃止しない。顧客(Customer)エンティティなどの宛先設定として、`delivery_preference` (PDF, Email, Peppol) を保持し、Peppolを選択した顧客にのみデジタルインボイスを送信する。
