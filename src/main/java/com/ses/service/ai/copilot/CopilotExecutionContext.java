package com.ses.service.ai.copilot;

import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** 1 queryのasOf・期間・法人・scopeを束ねる不変の実行時刻基準と一回限りのbind状態。 */
public final class CopilotExecutionContext {
    private final String tenantId;
    private final Long legalEntityId;
    private final Instant asOf;
    private final ZoneId zoneId;
    private String queryId;
    private CopilotQueryParameters parameters;
    private CopilotScopeContext scope;
    private EffectiveScopeSnapshot effectiveScopeSnapshot;

    public CopilotExecutionContext(String tenantId, Long legalEntityId, Instant asOf, ZoneId zoneId) {
        if (tenantId == null || tenantId.isBlank() || legalEntityId == null || asOf == null || zoneId == null) {
            throw new IllegalArgumentException("Copilot execution context is incomplete");
        }
        this.tenantId = tenantId;
        this.legalEntityId = legalEntityId;
        this.asOf = asOf;
        this.zoneId = zoneId;
    }

    public void bind(String queryId, CopilotQueryParameters parameters, CopilotScopeContext scope) {
        if (this.parameters != null || queryId == null || parameters == null || scope == null
                || !queryId.equals(parameters.queryId()) || !tenantId.equals(scope.tenantId())
                || !legalEntityId.equals(scope.legalEntityId())
                || (effectiveScopeSnapshot != null && effectiveScopeSnapshot.scope() != scope)) {
            throw new IllegalStateException("Copilot execution context binding is invalid");
        }
        this.queryId = queryId;
        this.parameters = parameters;
        this.scope = scope;
    }

    /** query開始時に一度だけscope snapshotを束縛する。再認可は別contextで行う。 */
    public void bindSnapshot(EffectiveScopeSnapshot snapshot) {
        if (snapshot == null || effectiveScopeSnapshot != null || queryId != null || parameters != null
                || !tenantId.equals(snapshot.tenantId()) || !legalEntityId.equals(snapshot.legalEntityId())
                || !asOfDate().equals(snapshot.asOf())) {
            throw new IllegalStateException("Copilot effective scope snapshot binding is invalid");
        }
        effectiveScopeSnapshot = snapshot;
        scope = snapshot.scope();
    }

    public String tenantId() { return tenantId; }
    public Long legalEntityId() { return legalEntityId; }
    public Instant asOf() { return asOf; }
    public LocalDate asOfDate() { return asOf.atZone(zoneId).toLocalDate(); }
    public YearMonth asOfMonth() { return YearMonth.from(asOf.atZone(zoneId)); }
    public ZoneId zoneId() { return zoneId; }
    public String queryId() { return queryId; }
    public CopilotQueryParameters parameters() { return parameters; }
    public CopilotScopeContext scope() { return scope; }
    public EffectiveScopeSnapshot effectiveScopeSnapshot() { return effectiveScopeSnapshot; }
    public String scopeHash() { return scope == null ? null : scope.scopeHash(); }
}
