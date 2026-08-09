// 要員ログインアカウント紐付けカード（engineer-self-service-timesheet / P1）
(function () {
    const engineerId = new URLSearchParams(window.location.search).get('id');
    if (!engineerId) return;

    window.loadAccountLink = function () {
        const current = document.getElementById('account-link-current');
        if (!current) return;
        fetch(`/api/engineers/${engineerId}/account-link`)
            .then(res => res.json()).then(data => {
                const current = document.getElementById('account-link-current');
                const form = document.getElementById('account-link-form');
                const unlinkBtn = document.getElementById('account-link-unlink');
                if (data.code === 200 && data.data) {
                    current.textContent = '#' + data.data.sysUserId;
                    form.classList.add('d-none');
                    unlinkBtn.classList.remove('d-none');
                } else {
                    current.textContent = '—';
                    form.classList.remove('d-none');
                    unlinkBtn.classList.add('d-none');
                    loadCandidates();
                }
            });
    };

    document.addEventListener('DOMContentLoaded', loadAccountLink);

    function loadCandidates() {
        fetch(`/api/engineers/${engineerId}/account-link/candidates`)
            .then(res => res.json()).then(data => {
                if (data.code !== 200) return;
                const sel = document.getElementById('account-link-select');
                const hint = document.getElementById('account-link-hint');
                const linkBtn = document.getElementById('account-link-button');
                const candidates = (data.data && data.data.candidates) || [];
                sel.innerHTML = '';
                candidates.forEach(u => {
                    const opt = document.createElement('option');
                    opt.value = u.id;
                    opt.textContent = u.username + (u.realName ? ' (' + u.realName + ')' : '');
                    sel.appendChild(opt);
                });

                // 候補0件のとき、以前は空のセレクトだけが残り理由が分からなかった。
                // サーバーが返す emptyReason を具体的な案内文へ対応付ける。
                const empty = candidates.length === 0;
                sel.classList.toggle('d-none', empty);
                if (linkBtn) linkBtn.classList.toggle('d-none', empty);
                if (!hint) return;
                if (!empty) {
                    hint.classList.add('d-none');
                    hint.textContent = '';
                    return;
                }
                const reason = (data.data && data.data.emptyReason) || 'ALL_LINKED';
                hint.textContent = SES.i18n.t('engineer.accountLink.empty.' + reason);
                hint.classList.remove('d-none');
            });
    }

    window.linkAccount = function () {
        const sysUserId = document.getElementById('account-link-select').value;
        if (!sysUserId) return;
        fetch(`/api/engineers/${engineerId}/account-link`, {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
            body: JSON.stringify({ sysUserId: Number(sysUserId) })
        }).then(res => res.json()).then(data => {
            if (data.code === 200) loadAccountLink();
            else alert(data.message);
        });
    };

    window.unlinkAccount = function () {
        fetch(`/api/engineers/${engineerId}/account-link`, {
            method: 'DELETE', headers: SES.csrf.header()
        }).then(res => res.json()).then(data => {
            if (data.code === 200) loadAccountLink();
            else alert(data.message);
        });
    };
})();
