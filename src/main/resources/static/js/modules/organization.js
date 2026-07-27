(function (window, $) {
    'use strict';

    var organizations = [];
    var modal;

    function message(key, fallback) {
        return window.SES && SES.i18n && SES.i18n.t ? SES.i18n.t(key) : fallback;
    }

    function loadOrganizations() {
        var asOf = $('#organizationAsOf').val();
        $.get('/api/organizations', asOf ? {asOf: asOf} : {})
            .done(function (res) {
                if (res.code !== 200) { showError(res.message); return; }
                organizations = res.data || [];
                renderOrganizations();
            })
            .fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message || message('organization.loadFailed', '組織一覧の取得に失敗しました')); });
    }

    function renderOrganizations() {
        var byId = {};
        organizations.forEach(function (item) { byId[item.id] = item; });
        var rows = organizations.map(function (item) {
            var parent = item.parentId && byId[item.parentId] ? byId[item.parentId].name : '—';
            var action = '<button class="btn btn-sm btn-outline-info me-1" onclick="editOrganization(' + item.id + ')"><i class="bi bi-pencil"></i></button>';
            action += '<button class="btn btn-sm btn-outline-warning me-1" onclick="toggleOrganizationStatus(' + item.id + ', \'' + (item.status === '有効' ? '無効' : '有効') + '\')">' + (item.status === '有効' ? '−' : '＋') + '</button>';
            action += '<button class="btn btn-sm btn-outline-danger" onclick="deleteOrganization(' + item.id + ')"><i class="bi bi-trash"></i></button>';
            return '<tr><td>' + escapeHtml(item.code) + '</td><td>' + escapeHtml(item.name) + '</td><td>' + escapeHtml(item.type) + '</td><td>' + escapeHtml(parent) + '</td><td>' + escapeHtml(item.status) + '</td><td class="text-end">' + action + '</td></tr>';
        }).join('');
        $('#organizationTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">—</td></tr>');
        var parentOptions = '<option value="">—</option>' + organizations.map(function (item) { return '<option value="' + item.id + '">' + escapeHtml(item.code + ' ' + item.name) + '</option>'; }).join('');
        $('#organizationParent').html(parentOptions);
    }

    function openOrganizationModal(id) {
        $('#organizationId').val(''); $('#organizationVersion').val('0');
        $('#organizationCode').val(''); $('#organizationName').val(''); $('#organizationType').val('部門');
        $('#organizationParent').val(''); $('#organizationValidFrom').val(localDateString()); $('#organizationValidTo').val('');
        $('#organizationModalTitle').text(message('organization.new', '組織登録'));
        modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('organizationModal')); modal.show();
    }

    function editOrganization(id) {
        var item = organizations.find(function (entry) { return entry.id === id; });
        if (!item) return;
        openOrganizationModal();
        $('#organizationId').val(item.id); $('#organizationVersion').val(item.version == null ? 0 : item.version);
        $('#organizationCode').val(item.code); $('#organizationName').val(item.name); $('#organizationType').val(item.type);
        $('#organizationParent').val(item.parentId || ''); $('#organizationValidFrom').val(item.validFrom); $('#organizationValidTo').val(item.validTo || '');
        $('#organizationModalTitle').text(message('organization.edit', '組織編集'));
    }

    function saveOrganization() {
        var id = $('#organizationId').val();
        var payload = {legalEntityId: null, code: $('#organizationCode').val(), name: $('#organizationName').val(), type: $('#organizationType').val(), parentId: $('#organizationParent').val() || null, validFrom: $('#organizationValidFrom').val(), validTo: $('#organizationValidTo').val() || null, status: '有効', version: Number($('#organizationVersion').val() || 0)};
        var request = id ? $.ajax({url: '/api/organizations/' + id, method: 'PUT', contentType: 'application/json', data: JSON.stringify(payload)}) : $.ajax({url: '/api/organizations', method: 'POST', contentType: 'application/json', data: JSON.stringify(payload)});
        request.done(function (res) { if (res.code !== 200) { showError(res.message); return; } modal.hide(); Toast.success(message('common.saveSuccess', '保存しました')); loadOrganizations(); })
            .fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message || message('organization.saveFailed', '組織の保存に失敗しました')); });
    }

    function toggleOrganizationStatus(id, status) {
        $.ajax({url: '/api/organizations/' + id + '/status?status=' + encodeURIComponent(status), method: 'PUT'})
            .done(function (res) { if (res.code === 200) { loadOrganizations(); } else { showError(res.message); } })
            .fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message); });
    }

    function deleteOrganization(id) {
        if (!window.Swal) return;
        Swal.fire({title: message('organization.deleteConfirm', 'この組織を削除しますか？'), icon: 'warning', showCancelButton: true}).then(function (result) {
            if (!result.isConfirmed) return;
            $.ajax({url: '/api/organizations/' + id, method: 'DELETE'}).done(function (res) { if (res.code === 200) { loadOrganizations(); } else { showError(res.message); } }).fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message); });
        });
    }

    function escapeHtml(value) { return $('<div>').text(value == null ? '' : value).html(); }
    function localDateString() { var now = new Date(); var pad = function (value) { return String(value).padStart(2, '0'); }; return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()); }
    function showError(text) { if (window.Toast && Toast.error) Toast.error(text || 'Error'); else if (window.Swal) Swal.fire({icon: 'error', text: text || 'Error'}); }

    window.loadOrganizations = loadOrganizations;
    window.openOrganizationModal = openOrganizationModal;
    window.editOrganization = editOrganization;
    window.saveOrganization = saveOrganization;
    window.toggleOrganizationStatus = toggleOrganizationStatus;
    window.deleteOrganization = deleteOrganization;
    $(function () { $('#organizationAsOf').val(localDateString()); loadOrganizations(); });
}(window, jQuery));
