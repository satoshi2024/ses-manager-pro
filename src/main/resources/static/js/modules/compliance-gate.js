// 派遣コンプライアンスG2 gate（R23-P1-01 §5）
// capabilityはserver計算（/api/compliance-gate/capabilities）し、JS role判定をauthorizationに使わない。
$(document).ready(function () {
    loadCapabilities();
});

let gateCapabilities = {};

function localDateTimeString(d) {
    const dt = d || new Date();
    return SES.util.getLocalDateString(dt) + 'T'
        + String(dt.getHours()).padStart(2, '0') + ':'
        + String(dt.getMinutes()).padStart(2, '0') + ':'
        + String(dt.getSeconds()).padStart(2, '0');
}

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

// ===== Policy（group/type/freeze操作・P0-1） =====

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
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}`,
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const m = res.data;
                renderPolicy(mappingId, m);
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
});

function renderPolicy(mappingId, m) {
    const canManage = !!gateCapabilities.canManagePolicy;
    const isDraft = m.status === 'DRAFT';
    let html = `<div class="small text-light">
        <p><strong>reviewPolicyHash:</strong> <code>${SES.escapeHtml((m.reviewPolicyHash || '').slice(0, 16))}…</code></p>
        <p><strong>status:</strong> <span class="badge ${statusBadge(m.status)}">${SES.escapeHtml(m.status || '')}</span></p>
        <div id="policyGroups" class="mt-2"></div>`;
    if (canManage && isDraft) {
        html += `<div class="mt-3 d-flex gap-2">
            <input id="newGroupCode" class="form-control form-control-sm bg-dark text-light" placeholder="group code" style="max-width:180px">
            <input id="newGroupName" class="form-control form-control-sm bg-dark text-light" placeholder="表示名" style="max-width:180px">
            <button class="btn btn-sm btn-outline-primary" onclick="createPolicyGroup(${mappingId})">group追加</button>
            <button class="btn btn-sm btn-outline-warning" onclick="freezeMapping(${mappingId})">freeze（PROVISIONAL_REVIEWED）</button>
        </div>`;
    } else if (m.status === 'PROVISIONAL_REVIEWED' || m.status === 'ACTIVE') {
        html += `<button class="btn btn-sm btn-outline-success mt-2" onclick="promoteMapping(${mappingId})">promote（future→ACTIVE）</button>`;
    }
    html += `</div>`;
    $('#policyContent').html(html);
    loadPolicyGroups(mappingId);
}

function createPolicyGroup(mappingId) {
    const code = $('#newGroupCode').val().trim();
    const name = $('#newGroupName').val().trim();
    if (!code || !name) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}/requirement-groups`,
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ groupCode: code, displayName: name, minimumDistinctReviewers: 1 }),
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadPolicyGroups(mappingId);
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function loadPolicyGroups(mappingId) {
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}/requirement-groups`,
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                renderPolicyGroups(mappingId, res.data || []);
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function renderPolicyGroups(mappingId, groups) {
    const $box = $('#policyGroups');
    if (!groups.length) {
        $box.html('<p class="text-muted small">' + SES.i18n.t('compliance.gate.noData') + '</p>');
        return;
    }
    let html = '<h6 class="text-info mt-2">requirement groups</h6>';
    groups.forEach(function (g) {
        html += `<div class="border rounded p-2 mb-2 bg-dark">
            <strong>${SES.escapeHtml(g.requirementGroupCode)}</strong> - ${SES.escapeHtml(g.displayName)}
            <span class="badge bg-secondary ms-2">minimum=${g.minimumDistinctReviewers}</span>
            <div class="mt-1" id="groupTypes-${g.id}"><span class="text-muted">types読み込み...</span></div>
            ${gateCapabilities.canManagePolicy ? `
            <div class="mt-1 d-flex gap-2">
                <select id="typeSelect-${g.id}" class="form-select form-select-sm bg-dark text-light" style="max-width:220px"></select>
                <button class="btn btn-sm btn-outline-primary" onclick="addPolicyType(${mappingId}, ${g.id})">type追加</button>
            </div>` : ''}
        </div>`;
    });
    $box.html(html);
    groups.forEach(function (g) {
        loadGroupTypes(g.id, g);
    });
    // type選択肢を読み込む
    $.ajax({
        url: '/api/compliance-gate/reviewer-types',
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                (res.data || []).forEach(function (t) {
                    groups.forEach(function (g) {
                        $(`#typeSelect-${g.id}`).append(`<option value="${t.id}">${SES.escapeHtml(t.typeCode)} / ${SES.escapeHtml(t.displayName)}</option>`);
                    });
                });
            }
        },
        error: function () { /* type選択肢の失敗は無視 */ }
    });
}

function loadGroupTypes(groupId, group) {
    // group配下のtypeはmapping一覧APIに含まれないため、requirement-typeを取得する
    $.ajax({
        url: `/api/compliance-gate/mappings/${group.mappingId}/requirement-groups`,
        method: 'GET',
        success: function (res) {
            if (res.code !== 200) return;
            const groups = res.data || [];
            const g = groups.find(x => x.id === groupId);
            if (!g) return;
            const types = g.requirementTypes || [];
            const $box = $(`#groupTypes-${groupId}`);
            if (!types.length) {
                $box.html('<span class="text-muted">typeなし（最低1type必須・§4-3）</span>');
            } else {
                $box.html(types.map(t =>
                    `<span class="badge bg-secondary me-1">${SES.escapeHtml(t.reviewerTypeCodeSnapshot)}${t.credentialRequiredSnapshot === 1 ? '・必須' : ''}</span>`
                ).join(''));
            }
        },
        error: function () { /* 無視 */ }
    });
}

function addPolicyType(mappingId, groupId) {
    const reviewerTypeId = $(`#typeSelect-${groupId}`).val();
    if (!reviewerTypeId) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: `/api/compliance-gate/requirement-groups/${groupId}/requirement-types`,
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ reviewerTypeId: Number(reviewerTypeId) }),
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadPolicyGroups(mappingId);
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function freezeMapping(mappingId) {
    transitionMapping(mappingId, 'PROVISIONAL_REVIEWED');
}

// ===== Assignment（P0-1） =====

function loadAssignmentTab() {
    const canManage = !!gateCapabilities.canManageAssignment;
    let html = `<div class="small text-light">
        <h6 class="text-info">COMPLIANCE_RESPONSIBLE assignment（active_slot=1・半開区間）</h6>`;
    if (canManage) {
        html += `<div class="d-flex gap-2 mb-3">
            <input id="assignWorkplaceId" class="form-control form-control-sm bg-dark text-light" placeholder="workplaceId" style="max-width:130px">
            <input id="assignUserId" class="form-control form-control-sm bg-dark text-light" placeholder="userId" style="max-width:130px">
            <button class="btn btn-sm btn-outline-primary" onclick="createAssignment()">指名</button>
        </div>`;
    }
    html += `<table class="table table-dark table-hover table-sm"><thead><tr>
        <th>assignmentId</th><th>workplaceId</th><th>userId</th><th>effectiveFrom</th><th>effectiveTo</th><th>activeSlot</th></tr></thead>
        <tbody id="assignmentTableBody"><tr><td colspan="6" class="text-center text-muted py-3">${SES.i18n.t('js.common.loading')}</td></tr></tbody></table>
        </div>`;
    $('#assignmentContent').html(html);
    $.ajax({
        url: '/api/compliance-gate/mappings',
        method: 'GET',
        success: function () {
            // assignment一覧APIは存在しないため、workplace別に状態を表示する（現行はmapping一覧から推測不可）
            $('#assignmentTableBody').html(`<tr><td colspan="6" class="text-center text-muted py-3">assignment一覧は管理者操作で作成後、mapping approval時に検証されます</td></tr>`);
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function createAssignment() {
    const workplaceId = $('#assignWorkplaceId').val().trim();
    const userId = $('#assignUserId').val().trim();
    if (!workplaceId || !userId) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: '/api/compliance-gate/assignments',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ workplaceId: Number(workplaceId), userId: Number(userId), effectiveFrom: localDateTimeString() }),
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                loadAssignmentTab();
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== Internal Approval（P0-1） =====

function loadApprovalTab() {
    const canApprove = !!gateCapabilities.canApprove;
    let html = `<div class="small text-light">
        <h6 class="text-info">Internal Approval（PROVISIONAL_REVIEWED mapping・実actor本人のみ）</h6>`;
    if (canApprove) {
        html += `<div class="d-flex gap-2 mb-3">
            <input id="apprMappingId" class="form-control form-control-sm bg-dark text-light" placeholder="mappingId" style="max-width:130px">
            <input id="apprWorkplaceId" class="form-control form-control-sm bg-dark text-light" placeholder="workplaceId" style="max-width:130px">
            <input id="apprEvidenceDocId" class="form-control form-control-sm bg-dark text-light" placeholder="evidence docId" style="max-width:130px">
            <input id="apprEvidenceVersionId" class="form-control form-control-sm bg-dark text-light" placeholder="evidence versionId" style="max-width:140px">
            <input id="apprReason" class="form-control form-control-sm bg-dark text-light" placeholder="承認理由" style="max-width:200px">
            <button class="btn btn-sm btn-outline-success" onclick="submitApproval()">承認</button>
        </div>`;
    }
    html += `</div>`;
    $('#approvalContent').html(html);
}

function submitApproval() {
    const body = {
        mappingId: Number($('#apprMappingId').val()),
        workplaceId: Number($('#apprWorkplaceId').val()),
        reason: $('#apprReason').val().trim(),
        evidenceDocumentId: Number($('#apprEvidenceDocId').val()),
        evidenceDocumentVersionId: Number($('#apprEvidenceVersionId').val())
    };
    if (!body.mappingId || !body.workplaceId || !body.reason || !body.evidenceDocumentId || !body.evidenceDocumentVersionId) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: '/api/compliance-gate/approvals',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(body),
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== External Review（P0-1） =====

function loadExternalReviewTab() {
    let html = `<div class="small text-light">
        <h6 class="text-info">External Review（SUBMITTED登録・資格保有者本人のReview）</h6>
        <div class="d-flex gap-2 mb-3 flex-wrap">
            <input id="extMappingId" class="form-control form-control-sm bg-dark text-light" placeholder="mappingId" style="max-width:110px">
            <input id="extGroupId" class="form-control form-control-sm bg-dark text-light" placeholder="group id" style="max-width:100px">
            <input id="extTypeId" class="form-control form-control-sm bg-dark text-light" placeholder="type id" style="max-width:100px">
            <input id="extName" class="form-control form-control-sm bg-dark text-light" placeholder="氏名" style="max-width:130px">
            <input id="extOrg" class="form-control form-control-sm bg-dark text-light" placeholder="組織" style="max-width:130px">
            <input id="extCredential" class="form-control form-control-sm bg-dark text-light" placeholder="credential" style="max-width:140px">
            <button class="btn btn-sm btn-outline-primary" onclick="submitExternalReview()">SUBMITTED登録</button>
        </div>
        <div id="extReviewList" class="mt-2"></div>
        </div>`;
    $('#externalReviewContent').html(html);
}

function submitExternalReview() {
    const body = {
        mappingId: Number($('#extMappingId').val()),
        requirementGroupId: Number($('#extGroupId').val()),
        reviewerTypeId: Number($('#extTypeId').val()),
        reviewerName: $('#extName').val().trim(),
        organization: $('#extOrg').val().trim(),
        credentialRaw: $('#extCredential').val().trim()
    };
    if (!body.mappingId || !body.requirementGroupId || !body.reviewerTypeId || !body.reviewerName) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: '/api/compliance-gate/external-reviews',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(body),
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
                const r = res.data;
                $('#extReviewList').html(`<p class="text-muted">chain: <code>${SES.escapeHtml(r.reviewChainId || '')}</code> / eventId: <code>${r.id}</code>（verificationは「本人・資格・作成者確認」tabで実施）</p>`);
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== 本人・資格・作成者確認（P0-1） =====

function loadVerificationTab() {
    const canVerify = !!gateCapabilities.canVerify;
    let html = `<div class="small text-light">
        <h6 class="text-info">本人性・資格有効性・Review作成者確認（IDENTITY/AUTHORSHIP常時必須・QUALIFICATION/ACTIVE_STATUSはfrozen flag=trueのみ）</h6>`;
    if (canVerify) {
        html += `<div class="d-flex gap-2 mb-3 flex-wrap">
            <input id="verSubmittedId" class="form-control form-control-sm bg-dark text-light" placeholder="submitted review id" style="max-width:140px">
            <input id="verSubjectId" class="form-control form-control-sm bg-dark text-light" placeholder="subject id" style="max-width:110px">
            <input id="verTypeId" class="form-control form-control-sm bg-dark text-light" placeholder="type id" style="max-width:100px">
            <select id="verKind" class="form-select form-select-sm bg-dark text-light" style="max-width:200px">
                <option value="IDENTITY">IDENTITY（本人性）</option>
                <option value="QUALIFICATION">QUALIFICATION（資格有効性）</option>
                <option value="ACTIVE_STATUS">ACTIVE_STATUS（業務状態）</option>
                <option value="REVIEW_AUTHORSHIP">REVIEW_AUTHORSHIP（作成者）</option>
            </select>
            <select id="verResult" class="form-select form-select-sm bg-dark text-light" style="max-width:150px">
                <option value="VERIFIED">VERIFIED</option>
                <option value="FAILED">FAILED</option>
                <option value="INCONCLUSIVE">INCONCLUSIVE</option>
            </select>
            <input id="verMethod" class="form-control form-control-sm bg-dark text-light" placeholder="methodCode" style="max-width:140px">
            <input id="verSource" class="form-control form-control-sm bg-dark text-light" placeholder="sourceCode" style="max-width:140px">
            <input id="verSourceName" class="form-control form-control-sm bg-dark text-light" placeholder="sourceName" style="max-width:140px">
            <input id="verRegId" class="form-control form-control-sm bg-dark text-light" placeholder="登録識別子（任意）" style="max-width:150px">
            <input id="verEvDocId" class="form-control form-control-sm bg-dark text-light" placeholder="evidence docId" style="max-width:110px">
            <input id="verEvVersionId" class="form-control form-control-sm bg-dark text-light" placeholder="evidence versionId" style="max-width:130px">
            <button class="btn btn-sm btn-outline-primary" onclick="submitVerification()">記録</button>
        </div>`;
    }
    html += `<div id="verificationList" class="mt-2"></div></div>`;
    $('#verificationContent').html(html);
}

function submitVerification() {
    const kind = $('#verKind').val();
    const body = {
        submittedReviewEventId: Number($('#verSubmittedId').val()),
        reviewerSubjectId: Number($('#verSubjectId').val()),
        reviewerTypeId: Number($('#verTypeId').val()),
        verificationKind: kind,
        result: $('#verResult').val(),
        methodCode: $('#verMethod').val().trim(),
        authoritySourceCode: $('#verSource').val().trim(),
        authoritySourceName: $('#verSourceName').val().trim(),
        officialUrlReference: '',
        registrationIdentifier: $('#verRegId').val().trim() || null,
        checkedAt: localDateTimeString(),
        sourceDataAsOf: localDateTimeString(),
        maxAgeDays: 365,
        validUntil: null,
        evidenceDocumentId: Number($('#verEvDocId').val()),
        evidenceDocumentVersionId: Number($('#verEvVersionId').val()),
        reviewPolicyVersion: null,
        reviewPolicyHash: null,
        mappingId: null,
        mappingVersion: null,
        mappingHash: null,
        externalReviewEventId: null,
        externalReviewChainId: null,
        idempotencyKey: 'UI-VERIFY-' + Date.now()
    };
    if (!body.submittedReviewEventId || !body.reviewerSubjectId || !body.reviewerTypeId
        || !body.methodCode || !body.authoritySourceCode || !body.evidenceDocumentId || !body.evidenceDocumentVersionId) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: '/api/compliance-gate/verifications',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(body),
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== ACTIVE（P0-1） =====

function loadActiveTab() {
    const canManage = !!gateCapabilities.canManageActive;
    let html = `<div class="small text-light">
        <h6 class="text-info">ACTIVE化（gate全条件成立後・approval id必須）</h6>`;
    if (canManage) {
        html += `<div class="d-flex gap-2 mb-3">
            <input id="activeMappingId" class="form-control form-control-sm bg-dark text-light" placeholder="mappingId" style="max-width:130px">
            <input id="activeApprovalId" class="form-control form-control-sm bg-dark text-light" placeholder="approvalEventId" style="max-width:150px">
            <button class="btn btn-sm btn-outline-success" onclick="activateMapping()">ACTIVE化</button>
        </div>`;
    }
    html += `<p class="text-muted">gate成立条件: APPROVED adoption・未REVOKED・frozen policyのverification setがVERIFIED・exact evidence（§4-8）</p></div>`;
    $('#activeContent').html(html);
}

function activateMapping() {
    const mappingId = $('#activeMappingId').val().trim();
    const approvalId = $('#activeApprovalId').val().trim();
    if (!mappingId || !approvalId) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}/transition?toStatus=ACTIVE&approvalEventId=${approvalId}`,
        method: 'PUT',
        success: function (res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.common.saveSuccess'));
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_save'));
            }
        },
        error: function () {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ===== Event History（P0-1） =====

function loadEventHistoryTab() {
    let html = `<div class="small text-light">
        <h6 class="text-info">Event History（mappingId指定でevent一覧）</h6>
        <div class="d-flex gap-2 mb-3">
            <input id="historyMappingId" class="form-control form-control-sm bg-dark text-light" placeholder="mappingId" style="max-width:130px">
            <button class="btn btn-sm btn-outline-primary" onclick="loadHistory()">表示</button>
        </div>
        <div id="historyList" class="mt-2"><p class="text-muted">mappingIdを指定してください</p></div>
        </div>`;
    $('#eventHistoryContent').html(html);
}

function loadHistory() {
    const mappingId = $('#historyMappingId').val().trim();
    if (!mappingId) {
        Toast.warning(SES.i18n.t('compliance.gate.requiredFields'));
        return;
    }
    const $box = $('#historyList');
    $box.html('<p class="text-muted">' + SES.i18n.t('js.common.loading') + '</p>');
    let html = '';
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}/external-reviews`,
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const rows = res.data || [];
                html += '<h6 class="text-info mt-2">external reviews（SUBMITTED）</h6>';
                if (!rows.length) {
                    html += '<p class="text-muted">なし</p>';
                } else {
                    html += `<table class="table table-dark table-sm"><thead><tr><th>id</th><th>chain</th><th>type</th><th>氏名</th><th>action</th><th>reviewedAt</th></tr></thead><tbody>` +
                        rows.map(r => `<tr><td>${r.id}</td><td><code>${SES.escapeHtml((r.reviewChainId || '').slice(0, 8))}…</code></td><td>${SES.escapeHtml(r.reviewerTypeCodeSnapshot || '')}</td><td>${SES.escapeHtml(r.reviewerNameSnapshot || '')}</td><td>${SES.escapeHtml(r.action || '')}</td><td>${SES.escapeHtml(r.reviewedAt || '')}</td></tr>`).join('') +
                        '</tbody></table>';
                }
                $box.html(html);
            }
        },
        error: function () {
            $box.html(html + '<p class="text-muted">' + SES.i18n.t('js.common.error_network') + '</p>');
        }
    });
    $.ajax({
        url: `/api/compliance-gate/mappings/${mappingId}/verifications`,
        method: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const rows = res.data || [];
                html += '<h6 class="text-info mt-2">verification events</h6>';
                if (!rows.length) {
                    html += '<p class="text-muted">なし</p>';
                } else {
                    html += `<table class="table table-dark table-sm"><thead><tr><th>id</th><th>kind</th><th>result</th><th>subjectId</th><th>checkedBy</th></tr></thead><tbody>` +
                        rows.map(r => `<tr><td>${r.id}</td><td>${SES.escapeHtml(r.verificationKind || '')}</td><td>${SES.escapeHtml(r.result || '')}</td><td>${r.reviewerSubjectId || ''}</td><td>${r.checkedBy || ''}</td></tr>`).join('') +
                        '</tbody></table>';
                }
                $box.html(html);
            }
        },
        error: function () { /* 無視 */ }
    });
}

$('#btnCreateMapping').on('click', function () {
    $('#mappingCodeInput').val('');
    $('#mappingVersionInput').val('');
    $('#mappingEffectiveFromInput').val('');
    $('#mappingEffectiveToInput').val('');
    new bootstrap.Modal(document.getElementById('mappingModal')).show();
});
$('#btnSaveMapping').on('click', saveMapping);
