(function (window, $) {
    'use strict';

    var organizations = [];
    var assignments = [];
    var users = [];
    var costCenters = [];

    function message(key, fallback) {
        return window.SES && SES.i18n && SES.i18n.t ? SES.i18n.t(key) : fallback;
    }

    function asOf() {
        return $('#organizationAsOf').val();
    }

    // ---------- 組織ツリー ----------

    function loadOrganizations() {
        var params = asOf() ? {asOf: asOf()} : {};
        $.get('/api/organizations', params)
            .done(function (res) {
                if (res.code !== 200) { showError(res.message); return; }
                organizations = res.data || [];
                renderOrganizations();
                renderOrganizationOptions();
                loadCostCenters();
            })
            .fail(function (xhr) { showError(errorText(xhr, 'organization.loadFailed', '組織一覧の取得に失敗しました')); });
    }

    /** 親子関係を辿って階層順に並べ、名称のインデントで木構造を表現する。 */
    function toTreeOrder(items) {
        var childrenOf = {};
        var known = {};
        items.forEach(function (item) { known[item.id] = true; });
        items.forEach(function (item) {
            var parentKey = item.parentId && known[item.parentId] ? item.parentId : 'root';
            (childrenOf[parentKey] = childrenOf[parentKey] || []).push(item);
        });
        var ordered = [];
        function walk(key, depth) {
            (childrenOf[key] || []).sort(function (a, b) {
                return String(a.code || '').localeCompare(String(b.code || ''));
            }).forEach(function (item) {
                ordered.push({item: item, depth: depth});
                walk(item.id, depth + 1);
            });
        }
        walk('root', 0);
        // scope の都合で親が見えない組織も必ず1回は出す（取りこぼすと編集不能になる）。
        if (ordered.length !== items.length) {
            var shown = {};
            ordered.forEach(function (row) { shown[row.item.id] = true; });
            items.forEach(function (item) {
                if (!shown[item.id]) { ordered.push({item: item, depth: 0}); }
            });
        }
        return ordered;
    }

    function renderOrganizations() {
        var rows = toTreeOrder(organizations).map(function (row) {
            var item = row.item;
            var indent = new Array(row.depth + 1).join('　') + (row.depth > 0 ? '└ ' : '');
            var toggleLabel = item.status === '有効' ? message('organization.disable', '無効化') : message('organization.enable', '有効化');
            var action = '<button class="btn btn-sm btn-outline-info me-1" onclick="editOrganization(' + item.id + ')" title="' + escapeAttr(message('common.edit', '編集')) + '"><i class="bi bi-pencil"></i></button>'
                + '<button class="btn btn-sm btn-outline-warning me-1" onclick="openMergeModal(' + item.id + ')" title="' + escapeAttr(message('organization.merge', '統合')) + '"><i class="bi bi-sign-merge-left"></i></button>'
                + '<button class="btn btn-sm btn-outline-secondary me-1" onclick="toggleOrganizationStatus(' + item.id + ', \'' + (item.status === '有効' ? '無効' : '有効') + '\')">' + escapeHtml(toggleLabel) + '</button>'
                + '<button class="btn btn-sm btn-outline-danger" onclick="deleteOrganization(' + item.id + ')" title="' + escapeAttr(message('common.delete', '削除')) + '"><i class="bi bi-trash"></i></button>';
            var statusBadge = item.status === '有効'
                ? '<span class="badge bg-success">' + escapeHtml(item.status) + '</span>'
                : '<span class="badge bg-secondary">' + escapeHtml(item.status) + '</span>';
            return '<tr><td>' + escapeHtml(indent) + escapeHtml(item.name) + '</td><td>' + escapeHtml(item.code)
                + '</td><td>' + escapeHtml(item.type) + '</td><td>' + escapeHtml(item.validFrom) + '</td><td>' + statusBadge
                + '</td><td class="text-end">' + action + '</td></tr>';
        }).join('');
        $('#organizationTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">—</td></tr>');
    }

    function organizationOptionHtml(selectedId, includeBlank) {
        var html = includeBlank ? '<option value="">—</option>' : '';
        return html + toTreeOrder(organizations).map(function (row) {
            var indent = new Array(row.depth + 1).join('　');
            var selected = String(row.item.id) === String(selectedId) ? ' selected' : '';
            return '<option value="' + row.item.id + '"' + selected + '>' + escapeHtml(indent + row.item.code + ' ' + row.item.name) + '</option>';
        }).join('');
    }

    function renderOrganizationOptions() {
        $('#organizationParent').html(organizationOptionHtml('', true));
        $('#assignmentOrganization').html(organizationOptionHtml('', false));
        $('#costCenterOrganization').html(organizationOptionHtml('', false));
        $('#mergeTargetId').html(organizationOptionHtml('', false));
    }

    function openOrganizationModal() {
        $('#organizationId').val(''); $('#organizationVersion').val('0');
        $('#organizationCode').val(''); $('#organizationName').val(''); $('#organizationType').val('部');
        $('#organizationParent').html(organizationOptionHtml('', true));
        $('#organizationValidFrom').val(localDateString()); $('#organizationValidTo').val('');
        $('#organizationModalTitle').text(message('organization.new', '組織登録'));
        bootstrap.Modal.getOrCreateInstance(document.getElementById('organizationModal')).show();
    }

    function editOrganization(id) {
        var item = organizations.find(function (entry) { return entry.id === id; });
        if (!item) return;
        openOrganizationModal();
        $('#organizationId').val(item.id); $('#organizationVersion').val(item.version == null ? 0 : item.version);
        $('#organizationCode').val(item.code); $('#organizationName').val(item.name); $('#organizationType').val(item.type);
        $('#organizationParent').html(organizationOptionHtml(item.parentId || '', true));
        $('#organizationValidFrom').val(item.validFrom); $('#organizationValidTo').val(item.validTo || '');
        $('#organizationModalTitle').text(message('organization.edit', '組織編集'));
    }

    function saveOrganization() {
        var id = $('#organizationId').val();
        var payload = {
            legalEntityId: null,
            code: $('#organizationCode').val(),
            name: $('#organizationName').val(),
            type: $('#organizationType').val(),
            parentId: $('#organizationParent').val() || null,
            validFrom: $('#organizationValidFrom').val(),
            validTo: $('#organizationValidTo').val() || null,
            status: '有効',
            version: Number($('#organizationVersion').val() || 0)
        };
        send(id ? '/api/organizations/' + id : '/api/organizations', id ? 'PUT' : 'POST', payload, 'organizationModal',
            'organization.saveFailed', '組織の保存に失敗しました', loadOrganizations);
    }

    function toggleOrganizationStatus(id, status) {
        var item = organizations.find(function (entry) { return String(entry.id) === String(id); });
        if (!item) { showError(message('organization.saveFailed', '組織の保存に失敗しました')); return; }
        var version = item.version == null ? 0 : item.version;
        $.ajax({url: '/api/organizations/' + id + '/status?status=' + encodeURIComponent(status)
                + '&version=' + encodeURIComponent(version), method: 'PUT'})
            .done(function (res) { if (res.code === 200) { loadOrganizations(); } else { showError(res.message); } })
            .fail(function (xhr) { showError(errorText(xhr, 'organization.saveFailed', '組織の保存に失敗しました')); });
    }

    function deleteOrganization(id) {
        confirmThen(message('organization.deleteConfirm', 'この組織を削除しますか？'), function () {
            $.ajax({url: '/api/organizations/' + id, method: 'DELETE'})
                .done(function (res) { if (res.code === 200) { loadOrganizations(); } else { showError(res.message); } })
                .fail(function (xhr) { showError(errorText(xhr, 'organization.saveFailed', '組織の保存に失敗しました')); });
        });
    }

    function openMergeModal(id) {
        var item = organizations.find(function (entry) { return entry.id === id; });
        if (!item) return;
        $('#mergeSourceId').val(item.id);
        $('#mergeSourceVersion').val(item.version == null ? 0 : item.version);
        $('#mergeTargetId').html(organizationOptionHtml('', false));
        bootstrap.Modal.getOrCreateInstance(document.getElementById('organizationMergeModal')).show();
    }

    function mergeOrganization() {
        var id = $('#mergeSourceId').val();
        var payload = {targetOrganizationId: Number($('#mergeTargetId').val()), version: Number($('#mergeSourceVersion').val() || 0)};
        send('/api/organizations/' + id + '/merge', 'POST', payload, 'organizationMergeModal',
            'organization.saveFailed', '組織の保存に失敗しました', loadOrganizations);
    }

    // ---------- 所属・上長 ----------

    function loadUsers() {
        $.get('/api/autocomplete/assignable-users').done(function (res) {
            if (res.code !== 200) { showError(res.message); return; }
            users = res.data || [];
            var options = users.map(function (user) {
                return '<option value="' + user.id + '">' + escapeHtml((user.realName || user.username) + ' (' + user.role + ')') + '</option>';
            }).join('');
            $('#assignmentUser').html(options);
            $('#assignmentManager').html('<option value="">—</option>' + options);
            loadAssignments();
        }).fail(function (xhr) { showError(errorText(xhr, 'organization.assignment.loadFailed', '所属一覧の取得に失敗しました')); });
    }

    function loadAssignments() {
        var userId = $('#assignmentUser').val();
        if (!userId) { $('#assignmentTableBody').html('<tr><td colspan="6" class="text-center text-muted">—</td></tr>'); return; }
        $.get('/api/organizations/' + userId + '/assignments', asOf() ? {asOf: asOf()} : {})
            .done(function (res) {
                if (res.code !== 200) { showError(res.message); return; }
                assignments = res.data || [];
                renderAssignments();
            })
            .fail(function (xhr) { showError(errorText(xhr, 'organization.assignment.loadFailed', '所属一覧の取得に失敗しました')); });
    }

    function renderAssignments() {
        var orgById = {};
        organizations.forEach(function (item) { orgById[item.id] = item; });
        var userById = {};
        users.forEach(function (user) { userById[user.id] = user; });
        var rows = assignments.map(function (item) {
            var org = orgById[item.organizationId];
            var manager = userById[item.managerUserId];
            var period = (item.validFrom || '') + ' 〜 ' + (item.validTo || '');
            var primary = item.primaryFlag === 1
                ? '<span class="badge bg-info text-dark">' + escapeHtml(message('organization.assignment.primary', '主所属')) + '</span>' : '';
            var action = '<button class="btn btn-sm btn-outline-danger" onclick="releaseAssignment(' + item.id + ', ' + (item.version == null ? 0 : item.version) + ')">'
                + escapeHtml(message('organization.assignment.release', '所属解除')) + '</button>';
            return '<tr><td>' + escapeHtml(org ? org.name : item.organizationId) + '</td><td>' + escapeHtml(item.positionName || '—')
                + '</td><td>' + escapeHtml(manager ? (manager.realName || manager.username) : '—') + '</td><td>' + primary
                + '</td><td>' + escapeHtml(period) + '</td><td class="text-end">' + (item.validTo ? '' : action) + '</td></tr>';
        }).join('');
        $('#assignmentTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">—</td></tr>');
    }

    function openAssignmentModal(transferMode) {
        if (!$('#assignmentUser').val()) { showError(message('organization.assignment.loadFailed', '所属一覧の取得に失敗しました')); return; }
        $('#assignmentTransferMode').val(transferMode ? '1' : '');
        // 異動は「今の主所属を閉じて新しい所属を開く」操作なので、現在の主所属の版番号を持ち回る。
        var currentPrimary = assignments.filter(function (item) { return item.primaryFlag === 1 && !item.validTo; })[0];
        $('#assignmentVersion').val(currentPrimary && currentPrimary.version != null ? currentPrimary.version : 0);
        $('#assignmentOrganization').html(organizationOptionHtml('', false));
        $('#assignmentPosition').val('');
        $('#assignmentManager').val('');
        $('#assignmentValidFrom').val(localDateString());
        $('#assignmentPrimary').prop('checked', !!transferMode);
        $('#assignmentModalTitle').text(transferMode
            ? message('organization.assignment.transfer', '異動')
            : message('organization.assignment.new', '所属登録'));
        bootstrap.Modal.getOrCreateInstance(document.getElementById('assignmentModal')).show();
    }

    function saveAssignment() {
        var userId = $('#assignmentUser').val();
        var transfer = $('#assignmentTransferMode').val() === '1';
        var payload = {
            organizationId: Number($('#assignmentOrganization').val()),
            positionName: $('#assignmentPosition').val() || null,
            managerUserId: $('#assignmentManager').val() ? Number($('#assignmentManager').val()) : null,
            primaryFlag: $('#assignmentPrimary').is(':checked') ? 1 : 0,
            validFrom: $('#assignmentValidFrom').val(),
            validTo: null,
            version: Number($('#assignmentVersion').val() || 0)
        };
        var url = '/api/organizations/' + userId + (transfer ? '/transfer' : '/assignments');
        send(url, 'POST', payload, 'assignmentModal', 'organization.saveFailed', '組織の保存に失敗しました', loadAssignments);
    }

    function releaseAssignment(assignmentId, version) {
        var userId = $('#assignmentUser').val();
        confirmThen(message('organization.assignment.releaseConfirm', 'この所属を解除しますか？'), function () {
            $.ajax({
                url: '/api/organizations/' + userId + '/assignments/' + assignmentId + '?version=' + encodeURIComponent(version),
                method: 'DELETE'
            }).done(function (res) {
                if (res.code === 200) { Toast.success(message('common.saveSuccess', '保存しました')); loadAssignments(); }
                else { showError(res.message); }
            }).fail(function (xhr) { showError(errorText(xhr, 'organization.saveFailed', '組織の保存に失敗しました')); });
        });
    }

    // ---------- 原価部門 ----------

    function loadCostCenters() {
        $.get('/api/organizations/cost-centers', asOf() ? {asOf: asOf()} : {})
            .done(function (res) {
                if (res.code !== 200) { showError(res.message); return; }
                costCenters = res.data || [];
                renderCostCenters();
            })
            .fail(function (xhr) { showError(errorText(xhr, 'organization.costCenter.loadFailed', '原価部門一覧の取得に失敗しました')); });
    }

    function renderCostCenters() {
        var orgById = {};
        organizations.forEach(function (item) { orgById[item.id] = item; });
        var rows = costCenters.map(function (item) {
            var org = orgById[item.organizationId];
            var action = '<button class="btn btn-sm btn-outline-info me-1" onclick="editCostCenter(' + item.id + ')"><i class="bi bi-pencil"></i></button>'
                + '<button class="btn btn-sm btn-outline-danger" onclick="deleteCostCenter(' + item.id + ')"><i class="bi bi-trash"></i></button>';
            return '<tr><td>' + escapeHtml(item.code) + '</td><td>' + escapeHtml(item.name) + '</td><td>'
                + escapeHtml(org ? org.name : (item.organizationId || '—')) + '</td><td>' + escapeHtml(item.status)
                + '</td><td class="text-end">' + action + '</td></tr>';
        }).join('');
        $('#costCenterTableBody').html(rows || '<tr><td colspan="5" class="text-center text-muted">—</td></tr>');
    }

    function openCostCenterModal() {
        $('#costCenterId').val(''); $('#costCenterVersion').val('0');
        $('#costCenterCode').val(''); $('#costCenterName').val('');
        $('#costCenterOrganization').html(organizationOptionHtml('', false));
        $('#costCenterValidFrom').val(localDateString());
        bootstrap.Modal.getOrCreateInstance(document.getElementById('costCenterModal')).show();
    }

    function editCostCenter(id) {
        var item = costCenters.find(function (entry) { return entry.id === id; });
        if (!item) return;
        openCostCenterModal();
        $('#costCenterId').val(item.id); $('#costCenterVersion').val(item.version == null ? 0 : item.version);
        $('#costCenterCode').val(item.code); $('#costCenterName').val(item.name);
        $('#costCenterOrganization').html(organizationOptionHtml(item.organizationId || '', false));
        $('#costCenterValidFrom').val(item.validFrom);
    }

    function saveCostCenter() {
        var id = $('#costCenterId').val();
        var payload = {
            legalEntityId: null,
            code: $('#costCenterCode').val(),
            name: $('#costCenterName').val(),
            organizationId: Number($('#costCenterOrganization').val()),
            validFrom: $('#costCenterValidFrom').val(),
            validTo: null,
            status: '有効',
            version: Number($('#costCenterVersion').val() || 0)
        };
        send(id ? '/api/organizations/cost-centers/' + id : '/api/organizations/cost-centers', id ? 'PUT' : 'POST',
            payload, 'costCenterModal', 'organization.costCenter.loadFailed', '原価部門一覧の取得に失敗しました', loadCostCenters);
    }

    function deleteCostCenter(id) {
        confirmThen(message('organization.deleteConfirm', 'この組織を削除しますか？'), function () {
            $.ajax({url: '/api/organizations/cost-centers/' + id, method: 'DELETE'})
                .done(function (res) { if (res.code === 200) { loadCostCenters(); } else { showError(res.message); } })
                .fail(function (xhr) { showError(errorText(xhr, 'organization.costCenter.loadFailed', '原価部門一覧の取得に失敗しました')); });
        });
    }

    // ---------- 共通 ----------

    function send(url, method, payload, modalId, failKey, failFallback, onSuccess) {
        $.ajax({url: url, method: method, contentType: 'application/json', data: JSON.stringify(payload)})
            .done(function (res) {
                if (res.code !== 200) { showError(res.message); return; }
                var modal = bootstrap.Modal.getInstance(document.getElementById(modalId));
                if (modal) { modal.hide(); }
                Toast.success(message('common.saveSuccess', '保存しました'));
                onSuccess();
            })
            .fail(function (xhr) { showError(errorText(xhr, failKey, failFallback)); });
    }

    function confirmThen(text, callback) {
        if (!window.Swal) { callback(); return; }
        Swal.fire({title: text, icon: 'warning', showCancelButton: true}).then(function (result) {
            if (result.isConfirmed) { callback(); }
        });
    }

    function errorText(xhr, key, fallback) {
        return (xhr && xhr.responseJSON && xhr.responseJSON.message) || message(key, fallback);
    }

    function escapeHtml(value) { return $('<div>').text(value == null ? '' : value).html(); }
    function escapeAttr(value) { return String(value == null ? '' : value).replace(/"/g, '&quot;'); }
    function localDateString() {
        var now = new Date();
        var pad = function (value) { return String(value).padStart(2, '0'); };
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }
    function showError(text) {
        if (window.Toast && Toast.error) { Toast.error(text || 'Error'); }
        else if (window.Swal) { Swal.fire({icon: 'error', text: text || 'Error'}); }
    }

    window.loadOrganizations = loadOrganizations;
    window.openOrganizationModal = openOrganizationModal;
    window.editOrganization = editOrganization;
    window.saveOrganization = saveOrganization;
    window.toggleOrganizationStatus = toggleOrganizationStatus;
    window.deleteOrganization = deleteOrganization;
    window.openMergeModal = openMergeModal;
    window.mergeOrganization = mergeOrganization;
    window.loadAssignments = loadAssignments;
    window.openAssignmentModal = openAssignmentModal;
    window.saveAssignment = saveAssignment;
    window.releaseAssignment = releaseAssignment;
    window.openCostCenterModal = openCostCenterModal;
    window.editCostCenter = editCostCenter;
    window.saveCostCenter = saveCostCenter;
    window.deleteCostCenter = deleteCostCenter;

    $(function () {
        $('#organizationAsOf').val(localDateString());
        loadOrganizations();
        loadUsers();
    });
}(window, jQuery));
