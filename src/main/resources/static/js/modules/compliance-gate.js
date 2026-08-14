// 派遣コンプライアンスG2 gate（R23-P1-01 §5）
// capabilityはserver計算（/api/compliance-gate/capabilities）し、JS role判定をauthorizationに使わない。
$(document).ready(function () {
    loadCapabilities();
});

let gateCapabilities = {};

function loadCapabilities() {
    $.ajax({
        url: '/api/compliance-gate/capabilities',
        method: 'GET',
        success: function (res) {
            if (res.code === 200 && res.data) {
                gateCapabilities = res.data;
                applyCapabilities();
                initGate();
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_fetch'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function applyCapabilities() {
    const $note = $('#gateCapabilityNote');
    $note.html(capabilityLabel());
    // server計算capabilityでUIを制御する（authorization自体はserverが強制）
    $('#btnCreateMapping').toggle(!!gateCapabilities.canManageMapping);
    $('#btnCreateReviewerType').toggle(!!gateCapabilities.canManageReviewerType);
    const canViewApprove = !!gateCapabilities.canApprove;
    const canManage = !!gateCapabilities.canManageMapping;
    const hidden = !(canViewApprove || canManage);
    if (hidden) {
        $('#tab-approval').hide();
    }
}

function capabilityLabel() {
    const active = Object.entries(gateCapabilities).filter(function (e) { return e[1]; });
    return `<span class="badge bg-info text-dark">capabilities: ${active.map(e => e[0]).join(', ') || 'none'}</span>`;
}

function initGate() {
    loadMappings();
    loadReviewerTypes();
    // tabs表示時に遅延読み込み
    $('#tab-policy').on('shown.bs.tab', loadPolicyTab);
    $('#tab-assignment').on('shown.bs.tab', loadAssignmentTab);
    $('#tab-approval').on('shown.bs.tab', loadApprovalTab);
    $('#tab-external-review').on('shown.bs.tab', loadExternalReviewTab);
    $('#tab-verification').on('shown.bs.tab', loadVerificationTab);
    $('#tab-active').on('shown.bs.tab', loadActiveTab);
    $('#tab-event-history').on('shown.bs.tab', loadEventHistoryTab);
}

// ===== Mapping =====

function loadMappings() {
    $.ajax({
        url: '/api/compliance-gate/mappings',
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                renderMappings(res.data || []);
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function renderMappings(rows) {
    const $body = $('#mappingTableBody');
    if (!rows.length) {
        $body.html(`<tr><td colspan="6" class="text-center text-muted py-4">${SES.i18n.t('compliance.gate.noData')}</td></tr>`);
        return;
    }
    let html = '';
    rows.forEach(function (r) {
        const effective = (r.effectiveFrom || '') + ' ~ ' + (r.effectiveTo || '∞');
        html += `<tr>
            <td>${SES.escapeHtml(r.mappingCode || '')}</td>
            <td>${SES.escapeHtml(r.mappingVersion || '')}</td>
            <td><span class="badge ${statusBadge(r.status)}">${SES.escapeHtml(r.status || '')}</span></td>
            <td>${SES.escapeHtml(effective)}</td>
            <td><code class="small">${SES.escapeHtml((r.mappingHash || '').slice(0, 12))}…</code></td>
            <td class="text-end">
                <button class="btn btn-sm btn-outline-warning" onclick="transitionMapping(${r.id}, 'PROVISIONAL_REVIEWED')">freeze</button>
                <button class="btn btn-sm btn-outline-success" onclick="promoteMapping(${r.id})">promote</button>
            </td>
        </tr>`;
    });
    $body.html(html);
}

function statusBadge(status) {
    if (status === 'ACTIVE') return 'bg-success';
    if (status === 'PROVISIONAL_REVIEWED') return 'bg-warning text-dark';
    if (status === 'DRAFT') return 'bg-secondary';
    return 'bg-dark';
}

function saveMapping() {
    const body = {
        mappingCode: $('#mappingCodeInput').val().trim(),
        mappingVersion: $('#mappingVersionInput').val().trim(),
        effectiveFrom: $('#mappingEffectiveFromInput').val() || null,
        effectiveTo: $('#mappingEffectiveToInput').val() || null,
        sources: []
    };
    if (!body.mappingCode || !body.mappingVersion) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: '/api/compliance-gate/mappings',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(body),
        success: function (res) {
            if (res.code === 200) {
                bootstrap.Modal.getInstance(document.getElementById('mappingModal')).hide();
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadMappings();
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function transitionMapping(id, toStatus) {
    $.ajax({
        url: `/api/compliance-gate/mappings/${id}/transition?toStatus=${toStatus}`,
        method: 'PUT',
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadMappings();
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function promoteMapping(id) {
    $.ajax({
        url: `/api/compliance-gate/mappings/${id}/promote`,
        method: 'PUT',
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadMappings();
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== Reviewer Type =====

function loadReviewerTypes() {
    $.ajax({
        url: '/api/compliance-gate/reviewer-types',
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                renderReviewerTypes(res.data || []);
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function renderReviewerTypes(rows) {
    const $body = $('#reviewerTypeTableBody');
    if (!rows.length) {
        $body.html(`<tr><td colspan="6" class="text-center text-muted py-4">${SES.i18n.t('compliance.gate.noData')}</td></tr>`);
        return;
    }
    let html = '';
    rows.forEach(function (t) {
        html += `<tr>
            <td><code>${SES.escapeHtml(t.typeCode || '')}</code></td>
            <td>${SES.escapeHtml(t.displayName || '')}</td>
            <td>${SES.escapeHtml(t.credentialLabel || '')}</td>
            <td>${t.credentialRequired ? '<span class="badge bg-danger">必須</span>' : '<span class="badge bg-secondary">任意</span>'}</td>
            <td>${t.enabled ? '<span class="badge bg-success">enabled</span>' : '<span class="badge bg-secondary">disabled</span>'}</td>
            <td class="text-end">
                <button class="btn btn-sm btn-outline-secondary" onclick="toggleReviewerType(${t.id}, ${!t.enabled})">${t.enabled ? '無効化' : '有効化'}</button>
            </td>
        </tr>`;
    });
    $body.html(html);
}

function toggleReviewerType(id, enabled) {
    $.ajax({
        url: `/api/compliance-gate/reviewer-types/${id}/enabled?enabled=${enabled}`,
        method: 'PUT',
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadReviewerTypes();
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== Policy =====

function loadPolicyTab() {
    $.ajax({
        url: '/api/compliance-gate/mappings',
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const rows = res.data || [];
                const $select = $('#policyMappingSelect');
                $select.empty();
                rows.forEach(function (r) {
                    $select.append(`<option value="${r.id}">${SES.escapeHtml(r.mappingCode)} / ${SES.escapeHtml(r.mappingVersion)} (${SES.escapeHtml(r.status)})</option>`);
                });
                if (rows.length) {
                    $select.trigger('change');
                } else {
                    $('#policyContent').html(`<p class="text-muted small">${SES.i18n.t('compliance.gate.noData')}</p>`);
                }
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

$('#policyMappingSelect').on('change', function () {
    const mappingId = $(this).val();
    if (!mappingId) return;
    $('#policyContent').html('<p class="text-muted small">' + SES.i18n.t('js.common.loading') + '</p>');
    // policy group一覧はmapping detailから取得（現状はmapping一覧のみ。詳細APIを利用）
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}`,
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const m = res.data;
                $('#policyContent').html(`<div class="small text-light">
                    <p><strong>reviewPolicyHash:</strong> <code>${SES.escapeHtml((m.reviewPolicyHash || '').slice(0, 16))}…</code></p>
                    <p><strong>status:</strong> ${SES.escapeHtml(m.status || '')}</p>
                    <p class="text-muted">policy group/typeの管理はmapping詳細画面で行います（Phase A step 3 API）</p>
                </div>`);
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
});

// ===== placeholder tabs =====
function loadAssignmentTab() { $('#assignmentContent').html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.placeholder') + '</p>'); }
function loadApprovalTab() { $('#approvalContent').html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.placeholder') + '</p>'); }
function loadExternalReviewTab() { $('#externalReviewContent').html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.placeholder') + '</p>'); }
function loadVerificationTab() { $('#verificationContent').html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.placeholder') + '</p>'); }
function loadActiveTab() { $('#activeContent').html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.placeholder') + '</p>'); }
function loadEventHistoryTab() { $('#eventHistoryContent').html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.placeholder') + '</p>'); }

$('#btnCreateMapping').on('click', function () {
    $('#mappingCodeInput').val('');
    $('#mappingVersionInput').val('');
    $('#mappingEffectiveFromInput').val('');
    $('#mappingEffectiveToInput').val('');
    new bootstrap.Modal(document.getElementById('mappingModal')).show();
});
$('#btnSaveMapping').on('click', saveMapping);
