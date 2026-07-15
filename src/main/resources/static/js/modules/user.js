let allMenus = [];

$(document).ready(function() {
    loadUsers();
    loadMenus();
});

function loadUsers(page = 1) {
    const data = {
        current: page,
        size: 10,
        username: $('#searchUsername').val(),
        role: $('#searchRole').val(),
        status: $('#searchStatus').val()
    };

    $.ajax({
        url: '/api/users',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderUsers(res.data.records || res.data);
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadUsers');
                }
            } else {
                Toast.error(SES.i18n.t('common.msg.fetchFail'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('common.msg.networkError'));
        }
    });
}

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('#accounts-pane .card-footer');
    if (pageData.total === 0) {
        paginationContainer.html(`<div class="text-muted small ps-2">${SES.i18n.t('user.pagination.zero')}</div>`);
        return;
    }

    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);

    let html = `
        <div class="text-muted small ps-2">
            ${SES.i18n.t('user.pagination.info', { total: pageData.total, start: start, end: end })}
        </div>
        <nav aria-label="Page navigation">
            <ul class="pagination pagination-sm mb-0 pe-2">
    `;

    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }

    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-info border-info text-dark fw-bold" href="javascript:void(0)">${i}</a></li>`;
        } else {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${i})">${i}</a></li>`;
        }
    }

    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }

    html += `</ul></nav>`;
    paginationContainer.html(html);
}

function renderUsers(records) {
    const tbody = $('#user-table-body');
    tbody.empty();

    if (!records || records.length === 0) {
        tbody.append(`<tr><td colspan="6" class="text-center text-muted py-4">${SES.i18n.t('common.msg.noData')}</td></tr>`);
        return;
    }

    records.forEach(user => {
        const statusBadge = user.status === 1
            ? `<span class="status-badge status-success">${SES.i18n.t('user.status.active')}</span>`
            : `<span class="status-badge status-secondary">${SES.i18n.t('user.status.inactive')}</span>`;

        const tr = `
            <tr>
                <td class="ps-4 py-3 fw-bold text-light">${SES.escapeHtml(user.username)}</td>
                <td>${SES.escapeHtml(user.realName || '-')}</td>
                <td><span class="status-badge status-primary">${SES.i18n.e('userRole', user.role)}</span></td>
                <td>${SES.escapeHtml(user.email || '-')}</td>
                <td>${statusBadge}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <button type="button" class="btn btn-outline-info text-info border-info" onclick="editUser(${user.id})"><i class="bi bi-pencil"></i></button>
                        <button type="button" class="btn btn-outline-warning text-warning border-warning" onclick="toggleUserStatus(${user.id}, ${user.status})"><i class="bi bi-power"></i></button>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteUser(${user.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function editUser(id) {
    $.ajax({
        url: '/api/users/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const user = res.data;
                $('#user-id').val(user.id);
                $('#user-username').val(user.username);
                $('#user-realName').val(user.realName);
                $('#user-role').val(user.role);
                $('#user-email').val(user.email);
                $('#user-password').val('');
                $('#user-password-label').html('パスワード');
                $('#user-password-hint').show();

                bootstrap.Modal.getOrCreateInstance(document.getElementById('userModal')).show();
            } else {
                Toast.error(SES.i18n.t('common.msg.fetchFail'));
            }
        }
    });
}

function saveUser() {
    const username = $('#user-username').val();
    if (!username) {
        Toast.error(SES.i18n.t('user.msg.loginIdRequired'));
        return;
    }

    const id = $('#user-id').val();
    const password = $('#user-password').val();
    if (!id && !password) {
        Toast.error(SES.i18n.t('user.msg.passwordRequired'));
        return;
    }

    const data = {
        username: username,
        realName: $('#user-realName').val(),
        role: $('#user-role').val(),
        email: $('#user-email').val()
    };
    if (password) {
        data.password = password;
    }
    if (id) {
        data.id = parseInt(id);
    }

    $.ajax({
        url: '/api/users',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? SES.i18n.t('user.msg.updateSuccess') : SES.i18n.t('user.msg.registerSuccess'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('userModal')).hide();
                $('#user-form')[0].reset();
                $('#user-id').val('');
                loadUsers(1);
            } else {
                Toast.error(res.message || SES.i18n.t('common.msg.saveFail'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(err.responseJSON && err.responseJSON.message ? err.responseJSON.message : '通信エラーが発生しました');
        }
    });
}

function toggleUserStatus(id, currentStatus) {
    const newStatus = currentStatus === 1 ? 0 : 1;
    $.ajax({
        url: '/api/users/' + id + '/status',
        method: 'PUT',
        data: { status: newStatus },
        success: function(res) {
            if (res.code === 200) {
                Toast.success(newStatus === 1 ? SES.i18n.t('user.msg.activateSuccess') : SES.i18n.t('user.msg.deactivateSuccess'));
                loadUsers();
            } else {
                Toast.error(res.message || SES.i18n.t('common.msg.updateFail'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(err.responseJSON && err.responseJSON.message ? err.responseJSON.message : '通信エラーが発生しました');
        }
    });
}

function deleteUser(id) {
    Swal.fire({
        title: SES.i18n.t('user.confirm.deleteTitle'),
        text: SES.i18n.t('user.confirm.deleteMsg'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('common.btn.delete'),
        cancelButtonText: SES.i18n.t('common.btn.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/users/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('user.msg.deleteSuccess'));
                        loadUsers();
                    } else {
                        Toast.error(res.message || SES.i18n.t('common.msg.deleteFail'));
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error(err.responseJSON && err.responseJSON.message ? err.responseJSON.message : '通信エラーが発生しました');
                }
            });
        }
    });
}

function loadMenus() {
    $.ajax({
        url: '/api/role-menus/menus',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                allMenus = res.data || [];
                loadRoleMenus();
            }
        }
    });
}

function loadRoleMenus() {
    const role = $('#permissionRole').val();
    $.ajax({
        url: '/api/role-menus',
        method: 'GET',
        data: { role: role },
        success: function(res) {
            if (res.code === 200) {
                renderRoleMenuCheckboxes(res.data || []);
            } else {
                Toast.error(SES.i18n.t('user.msg.permissionFetchFail'));
            }
        }
    });
}

function renderRoleMenuCheckboxes(allowedKeys) {
    const container = $('#role-menu-checkboxes');
    container.empty();

    allMenus.forEach(menu => {
        const checked = allowedKeys.includes(menu.menuKey) ? 'checked' : '';
        container.append(`
            <div class="form-check form-switch">
                <input class="form-check-input role-menu-checkbox" type="checkbox" role="switch" id="menu-${menu.id}" value="${menu.id}" ${checked}>
                <label class="form-check-label text-light" for="menu-${menu.id}">${SES.escapeHtml(menu.menuName)}</label>
            </div>
        `);
    });
}

function saveRoleMenus() {
    const role = $('#permissionRole').val();
    const menuIds = [];
    $('.role-menu-checkbox:checked').each(function() {
        menuIds.push(parseInt($(this).val()));
    });

    $.ajax({
        url: '/api/role-menus?role=' + encodeURIComponent(role),
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify(menuIds),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('user.msg.permissionSaveSuccess'));
            } else {
                Toast.error(res.message || SES.i18n.t('common.msg.saveFail'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('common.msg.networkError'));
        }
    });
}
