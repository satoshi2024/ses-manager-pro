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

    let selectedOrgId = null;

    // ===== 組織 =====
    function loadOrgs() {
        SES.api.get('/api/portal-admin/orgs?current=1&size=100').done(function (res) {
            const rows = (res.data.records || []).map(function (org) {
                const typeLabel = org.type === 'BP' ? 'BP' : '顧客';
                const actions = '<button class="btn btn-sm btn-outline-info" data-org-id="' + org.id + '">'
                    + t('portalAdmin.orgs.users') + '</button>'
                    + '<button class="btn btn-sm btn-outline-warning" data-org-status="' + org.id + '">'
                    + (org.status === 'ACTIVE' ? t('portalAdmin.orgs.suspend') : t('portalAdmin.orgs.resume'))
                    + '</button>';
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
                    const row = $(this);
                    SES.api.get('/api/portal-admin/orgs?current=1&size=100').done(function (res2) {
                        const org = (res2.data.records || []).find(function (o) { return o.id === orgId; });
                        const next = org && org.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
                        SES.api.put('/api/portal-admin/orgs/' + orgId + '/status',
                            JSON.stringify({status: next})).done(function () {
                            Toast.success(t('common.msg.saved'));
                            loadOrgs();
                        });
                    });
                });
            });
        });
    }

    function loadUsers(orgId) {
        SES.api.get('/api/portal-admin/orgs/' + orgId + '/users?current=1&size=100').done(function (res) {
            const rows = (res.data.records || []).map(function (user) {
                const mfa = user.mfaEnabledAt ? '有効' : '未設定';
                const actions = '<button class="btn btn-sm btn-outline-warning" data-user-status="' + user.id + '">'
                    + (user.status === 'ACTIVE' ? t('portalAdmin.users.suspend') : t('portalAdmin.users.resume'))
                    + '</button>'
                    + '<button class="btn btn-sm btn-outline-danger" data-user-mfa="' + user.id + '">'
                    + t('portalAdmin.users.mfaReset') + '</button>';
                return '<tr><td>' + user.id + '</td><td>' + esc(user.email) + '</td><td>' + esc(user.status)
                    + '</td><td>' + mfa + '</td><td>' + esc(user.lastLoginAt || '') + '</td><td>' + actions + '</td></tr>';
            }).join('');
            $('#userTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
            $('[data-user-status]').on('click', function () {
                const userId = $(this).data('user-status');
                SES.api.get('/api/portal-admin/orgs/' + orgId + '/users?current=1&size=100').done(function (res2) {
                    const user = (res2.data.records || []).find(function (u) { return u.id === userId; });
                    const next = user && user.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
                    SES.api.put('/api/portal-admin/users/' + userId + '/status',
                        JSON.stringify({status: next})).done(function () {
                        Toast.success(t('common.msg.saved'));
                        loadUsers(orgId);
                    });
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
                    SES.api.post('/api/portal-admin/users/' + userId + '/mfa-reset').done(function () {
                        Toast.success(t('common.msg.saved'));
                        loadUsers(orgId);
                    });
                });
            });
        });
    }

    // ===== 招待 =====
    function loadInvitations() {
        SES.api.get('/api/portal-admin/invitations?current=1&size=100').done(function (res) {
            const rows = (res.data.records || []).map(function (inv) {
                return '<tr><td>' + esc(inv.email) + '</td><td>' + esc(inv.role)
                    + '</td><td>' + esc(inv.expiresAt) + '</td><td>' + (inv.usedAt ? t('portalAdmin.invitations.usedYes')
                        : t('portalAdmin.invitations.usedNo')) + '</td></tr>';
            }).join('');
            $('#invitationTableBody').html(rows || '<tr><td colspan="4" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
        });
    }

    function loadOrgOptions() {
        SES.api.get('/api/portal-admin/orgs?current=1&size=100').done(function (res) {
            const options = (res.data.records || []).map(function (org) {
                return '<option value="' + org.id + '">' + org.id + ' (' + org.type + ') '
                    + esc(org.customerId || org.bpCompanyId || '') + '</option>';
            }).join('');
            $('#invitationOrgId').html('<option value="">' + t('portalAdmin.invitations.org') + '</option>' + options);
        });
    }

    /** portalユーザー選択肢。session照会でID直打ちにしない。 */
    function loadSessionUserOptions() {
        $.get('/api/portal-admin/orgs', { current: 1, size: 100 }, function (res) {
            if (!res || res.code !== 200) {
                return;
            }
            const orgs = (res.data && res.data.records) || [];
            if (!orgs.length) {
                return;
            }
            const requests = orgs.map(function (org) {
                return $.get('/api/portal-admin/orgs/' + org.id + '/users', { current: 1, size: 100 });
            });
            $.when.apply($, requests).done(function () {
                const responses = requests.length === 1 ? [arguments[0]] : Array.prototype.map.call(arguments, function (a) { return a[0]; });
                const seen = {};
                let options = '<option value="">' + esc(t('portalAdmin.sessions.selectUser')) + '</option>';
                responses.forEach(function (userRes) {
                    if (!userRes || userRes.code !== 200) {
                        return;
                    }
                    ((userRes.data && userRes.data.records) || []).forEach(function (user) {
                        if (seen[user.id]) {
                            return;
                        }
                        seen[user.id] = true;
                        options += '<option value="' + user.id + '">' + esc(user.email || ('#' + user.id)) + '</option>';
                    });
                });
                $('#sessionUserId').html(options);
            });
        });
    }

    // ===== session =====
    function loadSessions() {
        const userId = $('#sessionUserId').val();
        if (!userId) {
            return;
        }
        SES.api.get('/api/portal-admin/users/' + userId + '/sessions').done(function (res) {
            const rows = (res.data || []).map(function (s) {
                return '<tr><td>' + s.id + '</td><td>' + esc(s.issuedAt) + '</td><td>' + esc(s.lastSeenAt)
                    + '</td><td>' + esc(s.expiresAt) + '</td><td>' + esc(s.userAgent || '') + '</td>'
                    + '<td><button class="btn btn-sm btn-outline-danger" data-session-revoke="' + s.id
                    + '">' + t('portalAdmin.sessions.revoke') + '</button></td></tr>';
            }).join('');
            $('#sessionTableBody').html(rows || '<tr><td colspan="6" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
            $('[data-session-revoke]').on('click', function () {
                SES.api.post('/api/portal-admin/users/' + userId + '/sessions/revoke',
                    JSON.stringify({sessionId: $(this).data('session-revoke')})).done(function () {
                    Toast.success(t('common.msg.saved'));
                    loadSessions();
                });
            });
        });
    }

    // ===== アクセスログ =====
    function loadLogs() {
        SES.api.get('/api/portal-admin/access-logs?current=1&size=100').done(function (res) {
            const rows = (res.data.records || []).map(function (log) {
                return '<tr><td>' + esc(log.createdAt) + '</td><td>' + esc(log.email)
                    + '</td><td>' + esc(log.orgType) + '</td><td>' + esc(log.action)
                    + '</td><td>' + esc((log.targetType || '') + ':' + (log.targetId || '')) + '</td></tr>';
            }).join('');
            $('#logTableBody').html(rows || '<tr><td colspan="5" class="text-center text-muted">'
                + t('portalAdmin.empty') + '</td></tr>');
        });
    }

    // ===== 利用規約 =====
    function loadTerms() {
        SES.api.get('/api/portal-admin/terms').done(function (res) {
            $('#termsCurrent').text(t('portalAdmin.terms.current') + ': ' + res.data.version);
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
                SES.api.post('/api/portal-admin/orgs', JSON.stringify(body)).done(function () {
                    Toast.success(t('common.msg.saved'));
                    loadOrgs();
                    loadOrgOptions();
                });
            });
        });

        $('#invitationForm').on('submit', function (event) {
            event.preventDefault();
            SES.api.post('/api/portal-admin/orgs/' + $('#invitationOrgId').val() + '/invitations',
                JSON.stringify({
                    email: $('#invitationEmail').val().trim(),
                    role: $('#invitationRole').val()
                })).done(function () {
                Toast.success(t('common.msg.saved'));
                $('#invitationEmail').val('');
                loadInvitations();
            });
        });

        $('#sessionLoadButton').on('click', loadSessions);

        $('#termsPublishButton').on('click', function () {
            SES.api.put('/api/portal-admin/terms',
                JSON.stringify({version: $('#termsVersion').val()})).done(function () {
                Toast.success(t('common.msg.saved'));
                $('#termsVersion').val('');
                loadTerms();
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
