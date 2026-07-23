$(document).ready(function() {
    let ganttChart = null;

    // Set initial dates to current fiscal year (e.g. April 1 to March 31)
    const now = new Date();
    const currentYear = now.getMonth() < 3 ? now.getFullYear() - 1 : now.getFullYear();
    $('#filter-period-from').val(`${currentYear}-04-01`);
    $('#filter-period-to').val(`${currentYear + 1}-03-31`);

    function loadGantt() {
        $('#gantt-loading').show();
        $('#gantt-target').empty();
        
        const startParam = $('#filter-period-from').val() || `${currentYear}-04-01`;
        const endParam = $('#filter-period-to').val() || `${currentYear + 1}-03-31`;

        $.ajax({
            url: `/api/contracts?size=1000&periodFrom=${startParam}&periodTo=${endParam}`,
            method: 'GET',
            success: function(res) {
                $('#gantt-loading').hide();
                
                let tasks = [];
                let list = [];
                let missingDateCount = 0;
                $('.gantt-info-msg').remove();
                
                if (res.code === 200 && res.data) {
                    list = res.data.records || res.data;
                    if (res.data.total && res.data.total > 1000) {
                        if (window.SES && SES.toast) {
                            SES.toast.warning(SES.i18n.t('js.gantt.maxLimitReached', '上限1000件に達しました。期間を絞り込んでください。'));
                        }
                    }
                }
                
                list.forEach((c, index) => {
                if (!c.startDate) {
                    missingDateCount++;
                    return; // 開始日がない場合は除外
                }
                
                let pClass = 'bar-wrapper';
                if(c.status === '準備中') pClass += ' status-warning';
                if(c.status === '解約') pClass += ' status-danger';
                
                let resolvedEndDate = c.endDate;
                if (!resolvedEndDate) {
                    resolvedEndDate = endParam; // 終了日未定の場合は表示範囲末尾まで
                    pClass += ' open-ended'; // 凡例でオープンバーとして示す用
                }
                
                // Frappe Gantt はタスク名をポップアップのinnerHTMLへそのまま挿入するため、
                // ここで事前にHTMLエスケープしておく（要員名・顧客名は自由入力のため）。
                tasks.push({
                    id: 'Task_' + c.id,
                    name: SES.escapeHtml((c.engineerName || SES.i18n.t('js.gantt.engineer')) + ' @ ' + (c.customerName || SES.i18n.t('js.gantt.customer'))),
                    start: c.startDate,
                    end: resolvedEndDate,
                    progress: calculateProgress(c.startDate, resolvedEndDate),
                    dependencies: '',
                    custom_class: pClass
                });
            });
            
                if (missingDateCount > 0) {
                    $('#gantt-target').before(`<div class="alert alert-info py-2 small mb-3 gantt-info-msg">開始日未設定のため表示から除外された契約が ${missingDateCount} 件あります。</div>`);
                }
                
                if (tasks.length === 0) {
                    $('#gantt-target').html('<div class="text-center text-muted p-5">' + SES.i18n.t('js.gantt.empty_data') + '</div>');
                    return;
                }

            ganttChart = new Gantt("#gantt-target", tasks, {
                header_height: 50,
                column_width: 30,
                step: 24,
                view_modes: ['Day', 'Week', 'Month'],
                bar_height: 25,
                bar_corner_radius: 3,
                arrow_curve: 5,
                padding: 18,
                view_mode: 'Day',   
                date_format: 'YYYY-MM-DD',
                language: 'ja',
                custom_popup_html: function(task) {
                        return `
                            <div class="p-2">
                                <div class="fw-bold mb-1">${task.name}</div>
                                <div class="small">${SES.i18n.t('js.gantt.period')}: ${task.start} ~ ${task.end}</div>
                                <div class="small mt-1 text-accent-green">${SES.i18n.t('js.gantt.progress')}: ${task.progress}%</div>
                            </div>
                        `;
                    }
                });
            },
            error: function(err) {
                $('#gantt-loading').hide();
                $('#gantt-target').html('<div class="text-center text-danger p-5">' + SES.i18n.t('js.gantt.error_fetch') + '</div>');
            }
        });
    }

    loadGantt();

    $('#btn-reload-gantt').on('click', function() {
        loadGantt();
    });

    // View mode buttons
    $('#view-mode-buttons button').on('click', function() {
        $('#view-mode-buttons button').removeClass('active');
        $(this).addClass('active');
        
        const mode = $(this).data('mode');
        if (ganttChart) {
            ganttChart.change_view_mode(mode);
        }
    });
});

function calculateProgress(startStr, endStr) {
    if (!startStr || !endStr) return 0;
    const start = new Date(startStr);
    const end = new Date(endStr);
    const now = new Date();
    
    if (now < start) return 0;
    if (now > end) return 100;
    
    const total = end.getTime() - start.getTime();
    const passed = now.getTime() - start.getTime();
    return Math.round((passed / total) * 100);
}

