# 既存 `/api/ai/**` のNF08境界

`/api/ai/match/**`、`/api/ai/chat`、`/api/ai/proposal-draft` は management-copilot のcanonical query/citation入口ではなく、既存のlegacy local-only入口として明示的に分離する。入力にリソースIDがある場合は `DataScope`、`OrganizationScope`、現在の法人コンテキストを先に通し、scopeを解決できない固定mock結果は返さない。`/api/ai/evaluations/**` は test/dev 限定のoffline評価入口であり、本番controllerには存在せず、評価カタログの参照はprovider呼出しや業務リソースの推論を行わない。`/api/ai/feedback` は推論を行わない運用記録入口だが、management flagがOFFなら拒否する。どの推論入口も `mock`/`rule` 以外のproviderや外部送信を許可せず、法人不明・scope不一致はfail-closedとする。実providerへ到達できる経路は `AiExecutionGateway` の最終gateにも束縛する。
