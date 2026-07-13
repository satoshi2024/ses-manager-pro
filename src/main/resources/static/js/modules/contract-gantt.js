$(document).ready(function() {
    let ganttChart = null;

    // Load data and init gantt
    $.ajax({
        url: '/api/contracts',
        method: 'GET',
        success: function(res) {
            $('#gantt-loading').hide();
            
            // Format data for Frappe Gantt
            let tasks = [];
            let list = [];
            if (res.code === 200 && res.data) {
                list = res.data.records || res.data;
            }
            if (!list || list.length === 0) list = getMockData();

            
            list.forEach((c, index) => {
                let pClass = 'bar-wrapper';
                if(c.status === '準備中') pClass += ' status-warning';
                if(c.status === '解約') pClass += ' status-danger';
                
                // Frappe Gantt はタスク名をポップアップのinnerHTMLへそのまま挿入するため、
                // ここで事前にHTMLエスケープしておく（要員名・顧客名は自由入力のため）。
                tasks.push({
                    id: 'Task_' + c.id,
                    name: SES.escapeHtml((c.engineerName || 'エンジニア') + ' @ ' + (c.customerName || '顧客')),
                    start: c.startDate || '2026-04-01',
                    end: c.endDate || '2026-09-30',
                    progress: calculateProgress(c.startDate, c.endDate),
                    dependencies: '',
                    custom_class: pClass
                });
            });
            
            if (tasks.length === 0) {
                $('#gantt-target').html('<div class="text-center text-muted p-5">表示できるデータがありません</div>');
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
                            <div class="small">期間: ${task.start} ~ ${task.end}</div>
                            <div class="small mt-1 text-accent-green">経過: ${task.progress}%</div>
                        </div>
                    `;
                }
            });
        },
        error: function(err) {
            $('#gantt-loading').hide();
            $('#gantt-target').html('<div class="text-center text-danger p-5">データの読み込みに失敗しました</div>');
        }
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

function getMockData() {
    return [
        { id: 1, engineerName: '田中 太郎', customerName: 'メガバンク', startDate: '2026-04-01', endDate: '2026-09-30', status: '稼動中' },
        { id: 2, engineerName: '鈴木 花子', customerName: 'ECソリューション', startDate: '2026-06-01', endDate: '2026-11-30', status: '準備中' },
        { id: 3, engineerName: '佐藤 次郎', customerName: 'テックベンチャー', startDate: '2026-05-15', endDate: '2026-08-15', status: '稼動中' }
    ];
}
