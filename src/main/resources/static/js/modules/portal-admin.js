/**
 * ポータル管理画面JS（/portal-admin。B1）。
 * 組織/user/招待/session/アクセスログ/利用規約を管理する。
 * 既存モジュールと同じパターン（SES.api + Toast + SES.i18n）。
 */
(function () {
    'use strict';

    function t(key) {
        return SES.i18n.t(key, key);
    }

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function reportError(error, fallback) {
        console.error(error);
        Toast.error(error && error.message ? error.message : fallback);
    }

    let selectedOrgId = null;

    // ===== 組織 =====
    function loadOrgs() {
        SES.api.get('/api/portal-admin/orgs?current=1&size=100').then(function (data) {
            const rows = (data.records || []).map(function (org) {
                const typeLabel = org.type === 'BP' ? 'BP' : '顧客';
                const actions = '<div class="d-flex flex-wrap justify-content-end align-items-center gap-1">'
                    + '<button type="button" class="btn btn-sm btn-outline-info" data-org-id="' + org.id + '" title="ユーザー一覧を表示" aria-label="ユーザー一覧を表示">'
                    + t('portalAdmin.orgs.users') + '</button>'
                    + '<button type="button" class="btn btn-sm btn-outline-warning" data-org-status="' + org.id + '" title="組織の状態を変更" aria-label="組織の状態を変更">'
                    + (org.status === 'ACTIVE' ? t('portalAdmin.orgs.suspend') : t('portalAdmin.orgs.resume')) + '</button></div>';
                return '<tr><td>' + org.id + '</td><td>' + typeLabel + '</td><td>' + esc(org.status)
                    + '</td><td>' + esc(org.customerId || org.bpCompanyId || '') + '</td><td>' + actions + '</td></tr>';
            }).join('');
            $('#orgTableBody').html(rows || '<tr><td colspan="5" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
            $('[data-org-id]').on('click', function () {
                selectedOrgId = $(this).data('org-id');
                loadUsers(selectedOrgId);
            });
            $('[data-org-status]').on('click', function () {
                const orgId = $(this).data('org-status');
                Swal.fire({
                    title: t('portalAdmin.orgs.statusConfirm'),
                    icon: 'warning',
                    showCancelButton: true,
                    confirmButtonText: t('common.btn.confirm')
                }).then(function (result) {
                    if (!result.isConfirmed) {
                        return;
                    }
                    // 現在の状態を取得して反転
                    SES.api.get('/api/portal-admin/orgs?current=1&size=100').then(function (data2) {
                        const org = (data2.records || []).find(function (o) { return o.id === orgId; });
                        const next = org && org.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
                        return SES.api.put('/api/portal-admin/orgs/' + orgId + '/status', { status: next });
                    }).then(function () {
                            Toast.success(t('common.msg.saved'));
                            loadOrgs();
                    }).catch(function (error) {
                        reportError(error, t('common.msg.saveFail'));
                    });
                });
            });
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    function loadUsers(orgId) {
        SES.api.get('/api/portal-admin/orgs/' + orgId + '/users?current=1&size=100').then(function (data) {
            const rows = (data.records || []).map(function (user) {
                const mfa = user.mfaEnabledAt ? '有効' : '未設定';
                const actions = '<div class="d-flex flex-wrap justify-content-end align-items-center gap-1">'
                    + '<button type="button" class="btn btn-sm btn-outline-warning" data-user-status="' + user.id + '" title="ユーザーの状態を変更" aria-label="ユーザーの状態を変更">'
                    + (user.status === 'ACTIVE' ? t('portalAdmin.users.suspend') : t('portalAdmin.users.resume'))
                    + '</button>'
                    + '<button type="button" class="btn btn-sm btn-outline-danger" data-user-mfa="' + user.id + '" title="MFAをリセット" aria-label="MFAをリセット">'
                    + t('portalAdmin.users.mfaReset') + '</button></div>';
                return '<tr><td>' + user.id + '</td><td>' + esc(user.email) + '</td><td>' + esc(user.status)
                    + '</td><td>' + mfa + '</td><td>' + esc(user.lastLoginAt || '') + '</td><td>' + actions + '</td></tr>';
            }).join('');
            $('#userTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
            $('[data-user-status]').on('click', function () {
                const userId = $(this).data('user-status');
                SES.api.get('/api/portal-admin/orgs/' + orgId + '/users?current=1&size=100').then(function (data2) {
                    const user = (data2.records || []).find(function (u) { return u.id === userId; });
                    const next = user && user.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
                    return SES.api.put('/api/portal-admin/users/' + userId + '/status', { status: next });
                }).then(function () {
                        Toast.success(t('common.msg.saved'));
                        loadUsers(orgId);
                }).catch(function (error) {
                    reportError(error, t('common.msg.saveFail'));
                });
            });
            $('[data-user-mfa]').on('click', function () {
                const userId = $(this).data('user-mfa');
                Swal.fire({
                    title: t('portalAdmin.users.mfaResetConfirm'),
                    icon: 'warning',
                    showCancelButton: true,
                    confirmButtonText: t('common.btn.confirm')
                }).then(function (result) {
                    if (!result.isConfirmed) {
                        return;
                    }
                    SES.api.post('/api/portal-admin/users/' + userId + '/mfa-reset').then(function () {
                        Toast.success(t('common.msg.saved'));
                        loadUsers(orgId);
                    }).catch(function (error) {
                        reportError(error, t('common.msg.saveFail'));
                    });
                });
            });
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    // ===== 招待 =====
    function loadInvitations() {
        SES.api.get('/api/portal-admin/invitations?current=1&size=100').then(function (data) {
            const rows = (data.records || []).map(function (inv) {
                return '<tr><td>' + esc(inv.email) + '</td><td>' + esc(inv.role)
                    + '</td><td>' + esc(inv.expiresAt) + '</td><td>' + (inv.usedAt ? t('portalAdmin.invitations.usedYes')
                        : t('portalAdmin.invitations.usedNo')) + '</td></tr>';
            }).join('');
            $('#invitationTableBody').html(rows || '<tr><td colspan="4" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    function loadOrgOptions() {
        SES.api.get('/api/portal-admin/orgs?current=1&size=100').then(function (data) {
            const options = (data.records || []).map(function (org) {
                return '<option value="' + org.id + '">' + org.id + ' (' + org.type + ') '
                    + esc(org.customerId || org.bpCompanyId || '') + '</option>';
            }).join('');
            $('#invitationOrgId').html('<option value="">' + t('portalAdmin.invitations.org') + '</option>' + options);
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    /** portalユーザー選択肢。session照会でID直打ちにしない。 */
    async function loadSessionUserOptions() {
        try {
            const orgData = await SES.api.get('/api/portal-admin/orgs', { current: 1, size: 100 });
            const orgs = orgData.records || [];
            if (!orgs.length) return;

            const responses = await Promise.all(orgs.map(function (org) {
                return SES.api.get('/api/portal-admin/orgs/' + org.id + '/users', { current: 1, size: 100 });
            }));
            const seen = {};
            let options = '<option value="">' + esc(t('portalAdmin.sessions.selectUser')) + '</option>';
            responses.forEach(function (userData) {
                (userData.records || []).forEach(function (user) {
                    if (seen[user.id]) return;
                    seen[user.id] = true;
                    options += '<option value="' + user.id + '">' + esc(user.email || ('#' + user.id)) + '</option>';
                });
            });
            $('#sessionUserId').html(options);
        } catch (error) {
            reportError(error, t('common.msg.fetchFail'));
        }
    }

    // ===== session =====
    function loadSessions() {
        const userId = $('#sessionUserId').val();
        if (!userId) {
            return;
        }
        SES.api.get('/api/portal-admin/users/' + userId + '/sessions').then(function (data) {
            const rows = (data || []).map(function (s) {
                return '<tr><td>' + s.id + '</td><td>' + esc(s.issuedAt) + '</td><td>' + esc(s.lastSeenAt)
                    + '</td><td>' + esc(s.expiresAt) + '</td><td>' + esc(s.userAgent || '') + '</td>'
                    + '<td><button type="button" class="btn btn-sm btn-outline-danger" data-session-revoke="' + s.id
                    + '" title="セッションを失効" aria-label="セッションを失効">'
                    + t('portalAdmin.sessions.revoke') + '</button></td></tr>';
            }).join('');
            $('#sessionTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
            $('[data-session-revoke]').on('click', function () {
                SES.api.post('/api/portal-admin/users/' + userId + '/sessions/revoke',
                    { sessionId: $(this).data('session-revoke') }).then(function () {
                    Toast.success(t('common.msg.saved'));
                    loadSessions();
                }).catch(function (error) {
                    reportError(error, t('common.msg.saveFail'));
                });
            });
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    // ===== アクセスログ =====
    function loadLogs() {
        SES.api.get('/api/portal-admin/access-logs?current=1&size=100').then(function (data) {
            const rows = (data.records || []).map(function (log) {
                return '<tr><td>' + esc(log.createdAt) + '</td><td>' + esc(log.email)
                    + '</td><td>' + esc(log.orgType) + '</td><td>' + esc(log.action)
                    + '</td><td>' + esc((log.targetType || '') + ':' + (log.targetId || '')) + '</td></tr>';
            }).join('');
            $('#logTableBody').html(rows || '<tr><td colspan="5" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    // ===== 利用規約 =====
    function loadTerms() {
        SES.api.get('/api/portal-admin/terms').then(function (data) {
            $('#termsCurrent').text(t('portalAdmin.terms.current') + ': ' + (data.version || ''));
        }).catch(function (error) {
            reportError(error, t('common.msg.fetchFail'));
        });
    }

    $(function () {
        loadOrgs();
        loadInvitations();
        loadOrgOptions();
        loadSessionUserOptions();
        loadLogs();
        loadTerms();

        $('#orgCreateButton').on('click', function () {
            Swal.fire({
                title: t('portalAdmin.orgs.create'),
                html: '<select id="swal-org-type" class="swal2-select"><option value="CUSTOMER">顧客</option>'
                    + '<option value="BP">BP</option></select>'
                    + '<input id="swal-org-target" class="swal2-input" placeholder="customer_id / bp_company_id">',
                showCancelButton: true,
                confirmButtonText: t('common.btn.confirm')
            }).then(function (result) {
                if (!result.isConfirmed) {
                    return;
                }
                const type = $('#swal-org-type').val();
                const target = $('#swal-org-target').val();
                const body = type === 'BP'
                    ? {type: type, bpCompanyId: Number(target)}
                    : {type: type, customerId: Number(target)};
                SES.api.post('/api/portal-admin/orgs', body).then(function () {
                    Toast.success(t('common.msg.saved'));
                    loadOrgs();
                    loadOrgOptions();
                }).catch(function (error) {
                    reportError(error, t('common.msg.saveFail'));
                });
            });
        });

        $('#invitationForm').on('submit', function (event) {
            event.preventDefault();
            SES.api.post('/api/portal-admin/orgs/' + $('#invitationOrgId').val() + '/invitations',
                {
                    email: $('#invitationEmail').val().trim(),
                    role: $('#invitationRole').val()
                }).then(function () {
                Toast.success(t('common.msg.saved'));
                $('#invitationEmail').val('');
                loadInvitations();
            }).catch(function (error) {
                reportError(error, t('common.msg.saveFail'));
            });
        });

        $('#sessionLoadButton').on('click', loadSessions);

        $('#termsPublishButton').on('click', function () {
            SES.api.put('/api/portal-admin/terms',
                { version: $('#termsVersion').val() }).then(function () {
                Toast.success(t('common.msg.saved'));
                $('#termsVersion').val('');
                loadTerms();
            }).catch(function (error) {
                reportError(error, t('common.msg.saveFail'));
            });
        });

        $('a[data-bs-toggle="tab"]').on('shown.bs.tab', function (event) {
            const target = $(event.target).attr('data-bs-target');
            if (target === '#tab-logs') { loadLogs(); }
            if (target === '#tab-invitations') { loadInvitations(); }
            if (target === '#tab-terms') { loadTerms(); }
        });
    });
})();
