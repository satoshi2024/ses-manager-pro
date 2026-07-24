/**
 * 外部要員在庫モジュール
 */
(function () {
    'use strict';

    function loadList(page) {
        const status = $('#filterStatus').val();
        $.ajax({
            url: '/api/bp-availabilities',
            data: { current: page || 1, size: 10, status: status },
            success: function (res) {
                if (res.code !== 200) {
                    Toast.error(res.message || '取得に失敗しました。');
                    return;
                }
                renderTable(res.data.records);
                renderPagination(res.data, loadList);
            }
        });
    }

    function renderTable(rows) {
        const tbody = $('#bpa-table-body');
        tbody.empty();
        if (!rows || rows.length === 0) {
            tbody.append(`<tr><td colspan="6" class="text-center text-muted py-4">データがありません。</td></tr>`);
            return;
        }
        rows.forEach(function (row) {
            const badgeClass = statusBadge(row.status);
            tbody.append(`
                <tr>
                    <td class="ps-4 fw-bold text-light">${SES.escapeHtml(row.initialName || '-')}</td>
                    <td>${SES.escapeHtml(row.bpCompany || '-')}</td>
                    <td>${row.unitPrice ? row.unitPrice.toLocaleString() : '-'}</td>
                    <td>${row.availableFrom || '-'}</td>
                    <td><span class="badge ${badgeClass}">${SES.escapeHtml(row.status)}</span></td>
                    <td class="text-end pe-4">
                        <button class="btn btn-sm btn-outline-info me-1" onclick="editBpa(${row.id})">編集</button>
                        ${row.status === '提案可能' ? `<button class="btn btn-sm btn-outline-success" onclick="promoteBpa(${row.id})">要員化</button>` : ''}
                        ${row.status === '要員化済' && row.promotedEngineerId ? `<a href="/engineer/detail/${row.promotedEngineerId}" class="btn btn-sm btn-outline-secondary">要員詳細</a>` : ''}
                    </td>
                </tr>
            `);
        });
    }

    function renderPagination(pageData, callback) {
        const container = $('#bpa-pagination');
        container.empty();
        const total = pageData.total || 0;
        const size = pageData.size || 10;
        const current = pageData.current || 1;
        const pages = Math.ceil(total / size);
        if (pages <= 1) { container.append(`<small class="text-muted">全${total}件</small>`); return; }
        let nav = `<small class="text-muted">全${total}件</small><nav><ul class="pagination pagination-sm mb-0">`;
        for (let i = 1; i <= Math.min(pages, 10); i++) {
            nav += `<li class="page-item${i === current ? ' active' : ''}"><a class="page-link" href="#" onclick="${callback.name}(${i});return false;">${i}</a></li>`;
        }
        nav += '</ul></nav>';
        container.html(nav);
    }

    function statusBadge(status) {
        const map = {
            '提案可能': 'bg-primary',
            '失効': 'bg-secondary',
            '要員化済': 'bg-success'
        };
        return map[status] || 'bg-secondary';
    }

    function editBpa(id) {
        $.ajax({
            url: '/api/bp-availabilities/' + id,
            success: function (res) {
                if (res.code !== 200) { Toast.error(res.message); return; }
                const bpa = res.data;
                $('#frm-id').val(bpa.id);
                $('#frm-initialName').val(bpa.initialName || '');
                $('#frm-bpCompany').val(bpa.bpCompany || '');
                $('#frm-unitPrice').val(bpa.unitPrice || '');
                $('#frm-experienceYears').val(bpa.experienceYears || '');
                $('#frm-availableFrom').val(bpa.availableFrom || '');
                $('#frm-status').val(bpa.status);
                $('#frm-skillsJson').val(bpa.skillsJson || '');
                $('#frm-remarks').val(bpa.remarks || '');
                new bootstrap.Modal(document.getElementById('bpaModal')).show();
            }
        });
    }

    function saveBpa() {
        const id = $('#frm-id').val();
        const data = {
            initialName: $('#frm-initialName').val().trim(),
            bpCompany: $('#frm-bpCompany').val().trim() || null,
            unitPrice: parseInt($('#frm-unitPrice').val()) || null,
            experienceYears: parseInt($('#frm-experienceYears').val()) || null,
            availableFrom: $('#frm-availableFrom').val() || null,
            status: $('#frm-status').val(),
            skillsJson: $('#frm-skillsJson').val().trim() || null,
            remarks: $('#frm-remarks').val().trim() || null
        };
        if (!data.initialName) { Toast.error('イニシャルは必須です。'); return; }

        $.ajax({
            url: '/api/bp-availabilities/' + id,
            method: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(data),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    bootstrap.Modal.getInstance(document.getElementById('bpaModal')).hide();
                    Toast.success('保存しました。');
                    loadList(1);
                } else {
                    Toast.error(res.message || '保存に失敗しました。');
                }
            }
        });
    }

    function deleteBpa() {
        const id = $('#frm-id').val();
        Swal.fire({
            title: '削除確認', text: 'この外部要員を削除しますか？', icon: 'warning',
            showCancelButton: true, confirmButtonText: '削除', confirmButtonColor: '#dc3545'
        }).then((result) => {
            if (result.isConfirmed) {
                $.ajax({
                    url: '/api/bp-availabilities/' + id,
                    method: 'DELETE',
                    headers: SES.csrf.header(),
                    success: function (res) {
                        if (res.code === 200) {
                            bootstrap.Modal.getInstance(document.getElementById('bpaModal')).hide();
                            Toast.success('削除しました。');
                            loadList(1);
                        } else {
                            Toast.error(res.message || '削除に失敗しました。');
                        }
                    }
                });
            }
        });
    }

    function promoteBpa(id) {
        Swal.fire({
            title: '要員化確認', text: 'この外部要員を自社要員として登録しますか？', icon: 'info',
            showCancelButton: true, confirmButtonText: '要員化', confirmButtonColor: '#198754'
        }).then((result) => {
            if (result.isConfirmed) {
                $.ajax({
                    url: '/api/bp-availabilities/' + id + '/promote',
                    method: 'POST',
                    headers: SES.csrf.header(),
                    success: function (res) {
                        if (res.code === 200) {
                            Swal.fire('要員化完了', '自社要員として登録されました。要員詳細へ移動しますか？', 'success')
                                .then(() => {
                                    location.href = '/engineer/detail/' + res.data.id;
                                });
                        } else {
                            Toast.error(res.message || '要員化に失敗しました。');
                        }
                    }
                });
            }
        });
    }

    $(function () {
        loadList(1);
        $('#btn-save').on('click', saveBpa);
        $('#btn-delete').on('click', deleteBpa);
    });

    window.loadList = loadList;
    window.editBpa = editBpa;
    window.promoteBpa = promoteBpa;

}());
