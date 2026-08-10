/**
 * 契約詳細（T063 A1: compliance profile / findings）。
 * GET /api/contracts/{id}/compliance-profile で取得し、role別mask済みの値を表示する。
 * - 派遣/準委任/請負で表示sectionを切替える。
 * - maskLevel != FULL のsensitive field（cpp-sensitive）は編集不可（値は非表示）。
 * - 保存はfull DTOのPUT。masked roleはsensitive keyを省略する（サーバが現値維持）。
 * - findingsはサーバがcompliance menu権限を持つ場合のみ返す（design §5.3）。
 */
const ContractCompliance = (function () {
    let contractId = null;
    let detail = null;

    function init() {
        const match = location.pathname.match(/^\/contract\/detail\/(\d+)/);
        if (!match) {
            showError(SES.i18n.t('error.scope.notFound', '対象が存在しません'));
            return;
        }
        contractId = match[1];
        load();
    }

    function load() {
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-profile',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200 || !res.data) {
                    showError(res.message || SES.i18n.t('error.scope.notFound', '対象が存在しません'));
                    return;
                }
                detail = res.data;
                render();
            },
            error: function (err) {
                showError((err.responseJSON && err.responseJSON.message)
                    || SES.i18n.t('error.scope.notFound', '対象が存在しません'));
            }
        });
    }

    function showError(message) {
        $('#contract-detail-loading').addClass('d-none');
        $('#contract-detail-error-message').text(message);
        $('#contract-detail-error').removeClass('d-none');
    }

    function render() {
        $('#contract-detail-loading').addClass('d-none');
        $('#contract-detail-content').removeClass('d-none');

        const contractNo = detail.contractNo != null ? detail.contractNo : ('C-' + detail.contractId);
        $('#cd-contractNo').text(contractNo);
        $('#cd-contractType').text(detail.contractType || '');
        $('#cd-subtitle').text([detail.customerName, detail.projectName, detail.engineerName]
            .filter(v => v != null && v !== '').join(' / '));

        fillWorkplaceOptions(detail.workplaces || []);

        const profile = detail.profile || {};
        $('[data-key]').each(function () {
            const key = $(this).data('key');
            const value = profile[key];
            if ($(this).is(':checkbox')) {
                $(this).prop('checked', value === 1);
            } else {
                $(this).val(value != null ? String(value) : '');
            }
        });

        $('#cd-profile-version').text(profile.version != null ? ('v' + profile.version) : '');
        if (!detail.profileExists) {
            $('#cpp-no-profile').removeClass('d-none');
        }

        applyContractType(detail.contractType);
        applyMask(detail.maskLevel, detail.canEdit);

        renderFindings(detail.findings || []);

        if (detail.canEdit) {
            $('#cd-save-btn').prop('disabled', false);
        }
    }

    function fillWorkplaceOptions(workplaces) {
        const select = $('#cpp-workplaceId');
        select.find('option:not([value=""])').remove();
        workplaces.forEach(function (w) {
            select.append($('<option>').val(w.id).text(w.name));
        });
    }

    function applyContractType(contractType) {
        const isDispatch = contractType === '派遣';
        const isQuasi = contractType === '準委任' || contractType === '請負';
        $('[data-section="dispatch"]').toggle(isDispatch);
        $('[data-section="quasi"]').toggle(isQuasi);
    }

    function applyMask(maskLevel, canEdit) {
        if (maskLevel === 'MASK') {
            $('#cd-mask-note').removeClass('d-none');
            $('#cpp-form .cpp-sensitive input').each(function () {
                $(this).prop('disabled', true).attr('placeholder', '—');
            });
        }
        if (!canEdit) {
            $('#cpp-form input, #cpp-form select, #cpp-form textarea').prop('disabled', true);
        }
    }

    function renderFindings(findings) {
        if (!findings || findings.length === 0) {
            $('#cpp-findings-empty').removeClass('d-none');
            return;
        }
        $('#cpp-findings-empty').addClass('d-none');
        $('#cpp-findings-table-wrap').removeClass('d-none');
        const body = $('#cpp-findings-body');
        body.empty();
        findings.forEach(function (f) {
            const statusBadge = '<span class="badge ' + (f.status === 'OPEN' ? 'bg-danger' : f.status === 'ACK' || f.status === 'IN_PROGRESS' ? 'bg-warning text-dark' : 'bg-success') + '">'
                + SES.escapeHtml(f.status || '') + '</span>';
            body.append(
                '<tr>' +
                '<td class="px-3 py-2">' + statusBadge + '</td>' +
                '<td class="py-2 small"><code class="text-accent-blue">' + SES.escapeHtml(f.code || '') + '</code></td>' +
                '<td class="px-3 py-2 small text-muted">' + SES.escapeHtml(f.dueDate || '—') + '</td>' +
                '</tr>');
        });
    }

    function buildPayload() {
        const payload = { version: detail.profile ? detail.profile.version : null };
        $('[data-key]').each(function () {
            const key = $(this).data('key');
            if (detail.maskLevel !== 'FULL' && $(this).closest('.cpp-sensitive').length > 0) {
                return;
            }
            const input = $(this);
            if (input.is(':checkbox')) {
                payload[key] = input.is(':checked') ? 1 : 0;
            } else if (input.is('select')) {
                const v = input.val();
                payload[key] = v !== '' && v != null ? parseInt(v, 10) : null;
            } else if (input.attr('type') === 'number') {
                const v = input.val();
                payload[key] = v !== '' && v != null ? (key === 'dispatchFeeAmount' ? parseFloat(v) : parseInt(v, 10)) : null;
            } else if (input.attr('type') === 'date') {
                const v = input.val();
                payload[key] = v !== '' && v != null ? v : null;
            } else {
                const v = input.val();
                payload[key] = v !== '' && v != null ? v : null;
            }
        });
        return payload;
    }

    function save() {
        $('#cd-save-btn').prop('disabled', true);
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-profile',
            method: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(buildPayload()),
            success: function (res) {
                if (res.code === 200) {
                    Toast.success(SES.i18n.t('cpp.saved', '保存しました'));
                    load();
                } else {
                    Toast.error(res.message || SES.i18n.t('js.common.error_network', '通信エラー'));
                    $('#cd-save-btn').prop('disabled', false);
                }
            },
            error: function (err) {
                const msg = err.responseJSON && err.responseJSON.message
                    ? err.responseJSON.message : SES.i18n.t('js.common.error_network', '通信エラー');
                Toast.error(msg);
                $('#cd-save-btn').prop('disabled', false);
            }
        });
    }

    return { init: init, save: save };
})();

$(function () {
    ContractCompliance.init();
});
