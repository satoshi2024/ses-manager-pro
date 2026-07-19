// 要員ログインアカウント紐付けカード（engineer-self-service-timesheet / P1）
(function () {
    const engineerId = new URLSearchParams(window.location.search).get('id');
    if (!engineerId) return;

    document.addEventListener('DOMContentLoaded', loadAccountLink);

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

    function loadCandidates() {
        fetch(`/api/engineers/${engineerId}/account-link/candidates`)
            .then(res => res.json()).then(data => {
                if (data.code !== 200) return;
                const sel = document.getElementById('account-link-select');
                sel.innerHTML = '';
                data.data.forEach(u => {
                    const opt = document.createElement('option');
                    opt.value = u.id;
                    opt.textContent = u.username + (u.realName ? ' (' + u.realName + ')' : '');
                    sel.appendChild(opt);
                });
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
