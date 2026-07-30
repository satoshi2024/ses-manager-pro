# Implementation Plan — 横断検索・実ToDo・保存ビュー・一括操作

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T028〜T032はL1〜L3の定向test・直接回帰、T033でL4全量を実行する。
> 通常Taskの完了条件へ無条件の`mvn test`全量を追加しない。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの予約番号は **V68**。着手時にmerge済み`db/migration`の最新を再確認し、
> 衝突していれば後発（本spec）を上へ繰り上げる。前の欠番を埋めない。V59は永久欠番。

- [ ] F1. task/saved view基盤
  - **Objective**: 通知とは独立したtaskを登録して担当・期限・状態を管理でき、
    一覧のfilter/sort/列を個人viewとして保存できる。不正なview JSONは保存時に拒否される。
  - **実装ガイダンス**: **V68**/V1/H2(`sql/schema-productivity-h2.sql`)/MySQL smoke、
    `SavedViewSchemaRegistry`によるallowlist、状態機械。
    `m_saved_view.owner_user_id IS NULL`は**共有view**を表す業務値（design §6.1）。未設定として扱わない。
    `due_date IS NULL`は期限なしであり、期限超過判定から明示的に除外する。
  - **テスト要件**: L1〜L3。task遷移（未着手→進行中→完了/取消、終端からの再openが不可）、
    view allowlist（未許可field名の拒否）、owner/共有の区別、`due_date IS NULL`が期限超過に**含まれない**こと。
  - **Demo**: task登録→担当変更→完了。期限なしtaskが期限超過一覧に出ないことを確認。

- [ ] A1. 横断検索
  - **Objective**: 検索窓に顧客名を入れると顧客・案件・契約・請求が種別ごとに返り、
    そこから各画面へ遷移できる。営業Aの検索結果に営業B専用データが**件数を含めて**現れない。
  - **実装ガイダンス**: entity種別ごとの`GlobalSearchProvider`、header UI、scope付き上限。
    **各providerは既存mapperのscope付きqueryをそのまま呼ぶ**（design §6.2）。
    検索用に新しいSQLを書くと母集団が二重定義になる。DTOは`type,id,title,subtitle,status,url,updatedAt`のみ。
    PII/原価をsubtitleへ出さない。parallel実行でDB poolを枯らさない。
  - **テスト要件**: L2〜L3。種別別結果、**営業A/営業Bの漏洩なし（件数も0）**、
    2文字未満の拒否、種別ごと最大件数、全体timeout。
  - **Demo**: 顧客名から顧客/案件/契約/請求へ移動。営業Bで同じ語を検索し担当外が0件を確認。

- [ ] A2. ToDo/通知分離
  - **Objective**: 通知を既読にしてもtaskは残り、taskを完了しても通知履歴は残る。
    通知からtaskを作成でき、期限通知が1日1回だけ届く。
  - **実装ガイダンス**: todo画面をタスク/通知tabへ分離、関連link、期限scheduler、通知→task。
    既存notification APIを壊さない。taskの可視性は`assignee_user_id` OR `requester_user_id`で、
    **組織scopeを重ねない**（design §6.2。異動した本人が自分のtaskを失う）。
  - **テスト要件**: L2〜L3。既読と完了の独立、期限通知の冪等（`UNIQUE(task_id, notify_date)`）、
    scheduler再起動・重複起動で二重送信なし、既存notification APIの回帰。
  - **Demo**: 通知を既読後もtask継続。schedulerを2回起動して通知が1件のみを確認。

- [ ] B1. 保存ビュー/表示列
  - **Objective**: 一覧の検索条件と表示列を保存し、再ログイン後に復元される。
    管理者は共有viewを作れるが、他ユーザーの個人viewを上書きできない。
  - **実装ガイダンス**: engineer/customer/project/contract/invoiceから段階導入。
    URL queryがある場合はURLを優先、明示的保存時だけDB更新。default view削除時のfallbackを用意。
  - **テスト要件**: L1〜L3。個人/共有の区別、無効field名の拒否、default fallback、
    **管理者でも他人の個人viewを更新できない**こと、`version`楽観ロック競合。
  - **Demo**: 列/検索を保存し再login後復元。管理者が他ユーザーの個人viewを更新しようとして拒否されることを確認。

- [ ] B2. 安全な一括操作
  - **Objective**: 一覧から最大200件を選んで担当変更をpreviewし、変更差分と権限不足行を確認してから適用できる。
    201件は拒否され、200件は各行の成功/失敗が返る。preview後に対象がすり替わっても適用されない。
  - **実装ガイダンス**: preview tokenに対象ID集合のhashと有効期限を署名（design §5）、担当/状態/task、最大200。
    各対象を既存単件serviceへ委譲し、状態機械/監査を再実装しない。
    **apply時の母集団はpreview時のscopeで固定**（design §6.2）。apply時に再評価して広げない。
    危険操作（削除、支払済、月次締め）は一括対象外（R4.4）。
  - **テスト要件**: L2〜L3。200件成功/201件は**リクエスト全体を拒否**、token改ざん拒否、
    partial success の各行結果、preview後に変化した行だけが失敗すること、権限/状態競合。
  - **Demo**: 20要員へ担当営業変更preview→apply→結果CSV。201件で拒否されることを確認。

- [ ] M. 回帰/負荷
  - **Objective**: 検索→saved view→bulk→taskの一連が実データ量で動き、検索p95が実測される。
    既存の通知・一覧機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、MySQLで検索p95実測、Node/JS syntax、
    mobile keyboard操作、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 検索→saved view→bulk→taskの業務シナリオ。検索p95の実測値を提示。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
