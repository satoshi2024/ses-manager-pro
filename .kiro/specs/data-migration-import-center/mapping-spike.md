# Read-only Mapping Spike

## 1. 目的と制約

現行Engineer CSVの既存11列を入力例にし、source → canonical DTO候補 → validation findingまでを確認する。これは既存 /api/engineers/import-csv の代替でも、DBへEngineerを作成するfixtureでもない。

- 実行結果はメモリ上のcanonical候補とhashだけにする。
- t_engineer、m_skill_tag、t_engineer_skill、監査、DocumentServiceへwriteしない。
- DG-06が未決定のため、自然キー、既存行update、upsert、rollbackを確定しない。
- sourceの実バイトhashは、実ファイルをOwnerから受領した後に計測する。本文中のサンプルに対して本番artifact hashを発行しない。

## 2. サンプル入力

既存Engineer CSVのheaderと、quoted newline、通常の負数、formula文字列を含む観測用サンプルである。入力をファイルとして保存しても、read-only spike以外の処理へ渡さない。

    ﻿氏名,氏名カナ,イニシャル,性別,雇用形態,ステータス,希望単価,経験年数,最寄駅,日本語レベル,備考
    山田太郎,ヤマダタロウ,Y.T,男性,正社員,Bench,600000,5,新宿,ビジネスレベル,"1行目
    2行目"
    佐藤花子,サトウハナコ,S.H,女性,BP,Bench,-50000,3,品川,日常会話レベル,通常の負数
    田中一郎,タナカイチロウ,T.I,男性,正社員,Bench,600000,4,渋谷,基礎レベル,=HYPERLINK(""https://example.invalid"")

サンプルの数値は安全性観測用であり、実在する金額・個人情報を表さない。通常の負数は業務上の受入可否を別途validationで判定し、formula文字列と同じ規則で無害化しない。

## 3. 期待する観測結果

| 行 | parser観測 | canonical候補 | finding候補 | DB write |
|---:|---|---|---|---|
| header | UTF-8 BOMを除去して11列 | schema=legacy-engineer | header compatible | 0 |
| 2 | quoted newlineを1 cellとして復元 | fullName=山田太郎、remarksにLF | なし | 0 |
| 3 | -50000を数値候補として復元 | expectedUnitPrice=-50000 JPY | 非負規則を採用するmappingならreject | 0 |
| 4 | formula文字列を評価しない | remarksはliteral string | formula-like-input warning | 0 |

row hashはsource row numberを含めず、正規化したrow valuesから算出する。同じsource/mappingならrow hashは再現し、行番号変更はresult orderingだけに影響する。

## 4. Mapping候補

| header | canonical path | 型/単位 | 現行正本 | read-only check |
|---|---|---|---|---|
| 氏名 | engineer.fullName | String | EngineerSaveDto / Engineer | required、length |
| 氏名カナ | engineer.fullNameKana | String | EngineerSaveDto / Engineer | length |
| イニシャル | engineer.initialName | String | EngineerSaveDto / Engineer | length |
| 性別 | engineer.gender | allowlist | EngineerSaveDto / EnumMappings | 男性/女性/空 |
| 雇用形態 | engineer.employmentType | allowlist | EngineerSaveDto / DB ENUM | 正社員/契約社員/BP/空 |
| ステータス | engineer.status | allowlist | EngineerSaveDto / EngineerService | 稼動中/退場予定/Bench/提案中/空 |
| 希望単価 | engineer.expectedUnitPrice | BigDecimal, JPY | Engineer / accounting history | scale、桁、mappingの負数規則 |
| 経験年数 | engineer.experienceYears | Integer, years | EngineerSaveDto | 0以上 |
| 最寄駅 | engineer.nearestStation | String | Engineer | length |
| 日本語レベル | engineer.japaneseLevel | allowlist/String | EngineerSaveDto | 選択値/length |
| 備考 | engineer.remarks | literal String | Engineer | length、formula-like warning |

## 5. No-write確認項目

read-only spikeの前後で、次を比較してすべて差分0にする。

- t_engineerのcount、max(id)、max(updated_at)
- m_skill_tagのcount、max(id)
- t_engineer_skillのcount、max(id)
- t_audit_logのcount
- DocumentServiceのregister/link呼出回数
- source/mapping/resultのhash値

Mockitoまたはread-only datasourceで、次の呼出回数を0として確認する。

- EngineerService.save / updateWithStatusGuard
- SkillTagResolver.resolveOrCreate
- EngineerSkillService.replaceSkills
- mapper.insert / updateById / delete

## 6. 必須fixture/test matrix

| 観点 | fixture | 合格条件 |
|---|---|---|
| UTF-8 | BOM / no BOM | headerとrow hashが安定 |
| Shift_JIS | 日本語を含むsource | 明示encodingで同じcanonical値 |
| quoting | comma / quote / quoted newline | row境界が崩れない |
| formula | =、+、@、tab、CR始まり | 評価せずliteral/warning |
| negative | -50000、-1.5 | formulaと区別してnumeric parse |
| huge cell | 上限超過cell | 全量蓄積せずrow error |
| duplicate | 同一候補key | auto-upsertせずduplicate finding |
| missing ref | 未解決customer/project/engineer | reject/candidate resolution |
| scale | 10,000 rows | stream/chunk、重複0、hash再現 |

## 7. Spikeの結論

現行CSV parserはUTF-8固定で全sourceをStringへ読み込むため、Import Centerの実装にはそのまま流用せず、互換golden testを残した上でstreaming parser/canonical adapterを新設する。既存Engineer CSV endpointは変更せず、DG-06承認後にlegacy-engineer mappingを明示的に適用する。
