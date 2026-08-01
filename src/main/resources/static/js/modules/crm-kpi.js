$(function () { loadCrmKpi(); });
function loadCrmKpi() {
    $.get('/api/crm/opportunities/kpi', function (res) {
        if (res.code !== 200) { Toast.error(res.message || SES.i18n.t('crm.kpi.error')); return; }
        const data = res.data || {}, forecast = data.forecast || {};
        $('#kpi-opportunity-forecast').text(`¥${Number(forecast.opportunityAmount || 0).toLocaleString()} (${forecast.opportunityCount || 0})`);
        $('#kpi-proposal-forecast').text(`¥${Number(forecast.proposalAmount || 0).toLocaleString()} (${forecast.proposalCount || 0})`);
        $('#kpi-owner-body').html((data.owners || []).map(o => `<tr><td>${o.ownerUserId ? `<a href="/crm/opportunities?ownerUserId=${o.ownerUserId}" class="link-info">${SES.escapeHtml(o.ownerName || '')}</a>` : SES.escapeHtml(o.ownerName || '')}</td><td>${o.leadCount}</td><td>${o.convertedLeadCount}</td><td>${o.leadConversionRate || 0}%</td><td>${o.opportunityConversionRate || 0}%</td></tr>`).join('') || '<tr><td colspan="5" class="text-muted">-</td></tr>');
        $('#kpi-stage-body').html((data.stages || []).map(s => `<tr><td><a href="/crm/opportunities?stage=${encodeURIComponent(s.stage)}" class="link-info">${SES.escapeHtml(s.stage)}</a></td><td>${s.count}</td><td>¥${Number(s.amount || 0).toLocaleString()}</td><td>¥${Number(s.weightedAmount || 0).toLocaleString()}</td><td>${s.averageStagnationDays}</td><td>${s.averageNoActivityDays}</td></tr>`).join(''));
        $('#kpi-lost-body').html((data.lostReasons || []).map(r => `<tr><td>${SES.escapeHtml(r.reason)}</td><td>${r.count}</td><td>¥${Number(r.amount || 0).toLocaleString()}</td></tr>`).join('') || '<tr><td class="text-muted">-</td></tr>');
        $('#kpi-source-body').html((data.sources || []).map(s => `<tr><td>${SES.escapeHtml(s.source)}</td><td>${s.convertedLeadCount}/${s.leadCount}</td><td>${s.roiRate || 0}%</td></tr>`).join('') || '<tr><td class="text-muted">-</td></tr>');
    }).fail(() => Toast.error(SES.i18n.t('crm.kpi.error')));
}
