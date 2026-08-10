package io.github.loganalyzer.cli.ui;

/**
 * Страница локального интерфейса: разметка, стили и скрипт в одном файле.
 *
 * <p>Инциденты рисуются на самой странице по JSON-отчёту, который отдаёт
 * {@code POST /api/analyze?format=json}. Благодаря этому шапка со сводкой, поиск по событиям
 * и раскрытие стеков живут в одном документе, а не в отдельном отчёте.
 * HTML-отчёт при этом никуда не делся — он скачивается кнопкой «Скачать отчёт».
 */
final class UiPage {

    private UiPage() {
    }

    static String html() {
        return PAGE;
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="ru">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>log-analyzer</title>
            <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            :root {
              --bg: #f4f5f7; --panel: #ffffff; --border: #dfe2e6; --line: #eceef1; --line-soft: #f2f3f5;
              --fg: #1f2328; --fg-2: #3a4149; --muted: #57606a; --muted-2: #6b7280; --muted-3: #8c959f;
              --accent: #0b5fbe; --err: #c0242e; --err-bg: #fdf0f1; --err-chip: #fbe9ea;
              --warn: #9a6a00; --warn-bg: #fdf7e7; --warn-dot: #d4a017;
              --mono: ui-monospace, Consolas, "SF Mono", monospace;
            }
            body { background: var(--bg); color: var(--fg); height: 100vh; display: flex; flex-direction: column;
                   font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; }
            a { color: var(--accent); text-decoration: none; }
            a:hover { text-decoration: underline; }
            code, .mono { font-family: var(--mono); }

            /* ── Верхняя панель ─────────────────────────────────────────── */
            .topbar { display: flex; align-items: center; gap: 24px; padding: 0 20px; height: 48px;
                      background: var(--panel); border-bottom: 1px solid var(--border); flex-shrink: 0; }
            .brand { font-weight: 600; font-size: 14px; letter-spacing: -0.01em; }
            .topbar nav { display: flex; gap: 2px; }
            .topbar nav a { padding: 6px 12px; border-radius: 6px; font-size: 14px; color: var(--muted); }
            .topbar nav a:hover { background: var(--line); text-decoration: none; }
            .topbar nav a.active { color: var(--fg); background: var(--line); font-weight: 500; }
            .addr { margin-left: auto; font-size: 12px; color: var(--muted-2); font-family: var(--mono); }

            /* ── Раскладка ──────────────────────────────────────────────── */
            .layout { flex: 1; display: flex; min-height: 0; }
            .side { width: 400px; flex-shrink: 0; background: var(--panel); border-right: 1px solid var(--border);
                    display: flex; flex-direction: column; min-height: 0; overflow-y: auto; }
            .main { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
            .step { font-size: 12px; color: var(--muted-2); text-transform: uppercase;
                    letter-spacing: 0.06em; margin-bottom: 10px; }
            .side-top { padding: 18px 20px 0; }
            .side-mid { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 0 20px; }
            .side-foot { margin-top: auto; padding: 16px 20px; border-top: 1px solid var(--border);
                         display: flex; align-items: center; gap: 10px; }

            /* ── Элементы формы ─────────────────────────────────────────── */
            .segmented { display: flex; border: 1px solid var(--border); border-radius: 6px;
                         overflow: hidden; margin-bottom: 12px; }
            .segmented button { flex: 1; padding: 8px 12px; border: 0; background: var(--panel);
                         color: var(--muted); font-size: 14px; cursor: pointer; }
            .segmented button + button { border-left: 1px solid var(--border); }
            .segmented button.active { background: var(--line); color: var(--fg); font-weight: 500; }
            textarea { flex: 1 1 180px; min-height: 90px; width: 100%; resize: none; padding: 12px;
                       border: 1px solid var(--border); border-radius: 6px; background: #fbfbfc;
                       color: var(--fg); font-family: var(--mono); font-size: 12px; line-height: 1.6; }
            textarea.drag { border-color: var(--accent); border-style: dashed; }
            input[type=text], input[type=search], select {
                       width: 100%; padding: 7px 8px; border: 1px solid var(--border); border-radius: 6px;
                       background: var(--panel); color: var(--fg); font-size: 14px; font-family: inherit; }
            .hint { margin-top: 8px; font-size: 12px; color: var(--muted-2); }
            .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 8px; }
            .lbl { display: block; font-size: 12px; color: var(--muted-2); margin-bottom: 4px; }
            .check { display: flex; align-items: center; gap: 8px; font-size: 14px; padding: 6px 0; }
            button.primary { background: var(--accent); border: 1px solid var(--accent); color: #fff;
                       padding: 9px 18px; border-radius: 6px; cursor: pointer; font-size: 14px;
                       font-weight: 500; white-space: nowrap; }
            button.ghost { background: var(--panel); border: 1px solid var(--border); color: var(--muted);
                       padding: 9px 14px; border-radius: 6px; cursor: pointer; font-size: 14px; }
            button:disabled { opacity: .55; cursor: default; }
            .status { margin-left: auto; font-size: 12px; color: var(--muted-2); }
            .status.error { color: var(--err); }

            /* ── Полоса метрик ──────────────────────────────────────────── */
            .metrics { display: flex; align-items: center; gap: 10px 20px; padding: 14px 24px;
                       background: var(--panel); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
            .metric { display: flex; align-items: baseline; gap: 8px; white-space: nowrap; }
            .metric .value { font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; }
            .metric .label { font-size: 13px; color: var(--muted); }
            .metric.err .value { color: var(--err); }
            .metric.warn .value { color: var(--warn); }
            .metrics .tools { margin-left: auto; display: flex; gap: 8px; align-items: center;
                       flex: 0 1 auto; min-width: 0; }
            .metrics .tools input { width: 220px; min-width: 0; }
            /* Когда инструменты не помещаются в строку, они занимают её целиком — так поиск
               не оказывается «висящим» посреди пустого места. */
            @media (max-width: 1180px) {
              .metrics .tools { flex: 1 0 100%; margin-left: 0; }
              .metrics .tools input { flex: 1 1 auto; width: auto; max-width: 360px; }
            }
            .metrics .tools button { padding: 7px 12px; border-radius: 6px; border: 1px solid var(--border);
                       background: var(--panel); color: var(--fg); cursor: pointer; font-size: 14px; }

            /* ── Содержимое ─────────────────────────────────────────────── */
            .content { flex: 1; overflow: auto; padding: 20px 24px 40px; min-height: 0; }
            .empty { color: var(--muted-2); font-size: 14px; max-width: 60ch; }
            .card { background: var(--panel); border: 1px solid var(--border); border-radius: 8px;
                    margin-bottom: 16px; overflow-x: auto; min-width: 0; }
            .card-head { display: flex; align-items: center; gap: 6px 12px; padding: 14px 18px;
                    border-bottom: 1px solid var(--line); flex-wrap: wrap; }
            .card-head code { overflow-wrap: anywhere; }
            .chip { font-size: 12px; padding: 2px 8px; border-radius: 4px; background: var(--line);
                    color: var(--muted); font-weight: 600; white-space: nowrap; }
            .chip.err { background: var(--err-chip); color: var(--err); }
            .chip.ok { background: #e8f3ec; color: #1a7f37; }
            .card-title { font-size: 15px; font-weight: 600; }
            .card-meta { margin-left: auto; font-size: 13px; color: var(--muted); }
            .cause { padding: 16px 18px; border-bottom: 1px solid var(--line); }
            .cause-head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; flex-wrap: wrap; }
            .bar { display: inline-block; width: 90px; height: 6px; border-radius: 3px;
                   background: var(--line); overflow: hidden; }
            .bar > span { display: block; height: 100%; background: var(--accent); }
            .cause h3 { margin: 0 0 8px; font-size: 17px; font-weight: 600; line-height: 1.35; }
            .cause p { margin: 0 0 10px; font-size: 14px; color: var(--fg-2); max-width: 76ch; }
            .cause .lead { font-size: 14px; color: var(--fg); font-weight: 500; }
            .cause .refs { display: flex; gap: 16px; align-items: center; flex-wrap: wrap;
                   font-size: 13px; color: var(--muted); }
            .plan { margin: 12px 0; padding: 12px 14px; background: #fbfbfc;
                   border: 1px solid var(--line); border-radius: 6px; }
            .plan-title { font-size: 12px; color: var(--muted-2); text-transform: uppercase;
                   letter-spacing: 0.06em; margin-bottom: 8px; }
            .plan ol { margin: 0; padding-left: 20px; }
            .plan li { font-size: 14px; color: var(--fg-2); margin-bottom: 6px; max-width: 88ch; }
            .plan li:last-child { margin-bottom: 0; }
            .plan p { margin: 0; }
            .plan code { background: var(--line); padding: 1px 4px; border-radius: 4px; }
            .scale { padding: 18px 18px 8px; }
            /* Отступы по краям: крайние отметки центрируются по границам шкалы
               и не вылезают за пределы карточки. */
            .track { position: relative; height: 22px; margin: 0 7px 6px; }
            .track .line { position: absolute; left: 0; right: 0; top: 10px; height: 2px; background: var(--line); }
            .dot { position: absolute; top: 6px; width: 9px; height: 9px; border-radius: 50%;
                   transform: translateX(-50%); background: var(--muted-3); cursor: pointer; }
            .dot.warn { background: var(--warn-dot); }
            .dot.err { background: var(--err); width: 13px; height: 13px; top: 4px; }
            .scale-ends { display: flex; justify-content: space-between; font-size: 12px;
                   color: var(--muted-2); font-family: var(--mono); }
            /* Фиксированная раскладка: длинное сообщение или стек не растягивают таблицу
               за пределы карточки, а переносятся внутри своей колонки. */
            table { width: 100%; border-collapse: collapse; font-size: 13px; table-layout: fixed; }
            th { text-align: left; color: var(--muted-2); font-weight: 500; font-size: 12px;
                 border-bottom: 1px solid var(--line); padding: 8px 10px; white-space: nowrap; }
            th:first-child { padding-left: 18px; }
            th:last-child { padding-right: 18px; white-space: normal; }
            td { padding: 9px 10px; border-bottom: 1px solid var(--line-soft); vertical-align: top;
                 overflow-wrap: anywhere; }
            td:first-child { padding-left: 18px; color: var(--muted-3); font-family: var(--mono); }
            td:last-child { padding-right: 18px; }
            .c-id { width: 52px; }
            .c-time { width: 152px; }
            .c-level { width: 74px; }
            .c-logger { width: 168px; }
            @media (max-width: 1100px) {
              .c-time { width: 118px; }
              .c-logger { width: 120px; }
              .t-time .t-offset { display: block; }
              .t-logger { white-space: normal; }
            }
            /* На узком экране колонка логгера уступает место сообщению — имя целиком
               остаётся во всплывающей подсказке строки. */
            @media (max-width: 900px) {
              .c-logger { display: none; }
            }
            tr:last-child td { border-bottom: 0; }
            tr.warn { background: var(--warn-bg); }
            tr.err { background: var(--err-bg); }
            tr.highlight td { box-shadow: inset 2px 0 0 var(--accent); }
            .t-time, .t-logger { color: var(--muted); white-space: nowrap; }
            .t-time { font-family: var(--mono); }
            .t-offset { color: var(--muted-3); }
            .lvl { font-size: 12px; color: var(--muted); }
            .lvl.warn { color: var(--warn); font-weight: 600; }
            .lvl.err { color: var(--err); font-weight: 600; }
            /* Пометки бывают длинными («первопричина: SQLTransientConnectionException»),
               поэтому они переносятся, а не выезжают за пределы карточки. */
            .tag { display: inline-block; font-size: 11px; padding: 1px 6px; margin: 2px 0 0 6px;
                   border-radius: 4px; background: var(--line); color: var(--muted);
                   max-width: 100%; overflow-wrap: anywhere; vertical-align: baseline; }
            .tag.err { background: var(--err-chip); color: var(--err); }
            .tag.warn { background: var(--warn-bg); color: var(--warn); }
            /* Совпадение с искомым реквизитом: должно быть видно с первого взгляда. */
            .tag.hit { background: #e3edfb; color: var(--accent); font-weight: 600; }
            details summary { cursor: pointer; color: var(--err); font-size: 12px; font-family: var(--mono); }
            /* width:100% удерживает стек внутри своей колонки: он прокручивается сам,
               а не растягивает таблицу и карточку. */
            details pre { overflow-x: auto; background: #fbfbfc; border: 1px solid var(--line);
                   padding: 10px; border-radius: 6px; font-size: 12px; margin: 6px 0 0;
                   width: 100%; max-width: 100%; box-sizing: border-box; }
            .note { font-size: 13px; color: var(--muted-2); }

            /* ── Оценка ответа ──────────────────────────────────────────── */
            .verdict { display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
                   margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--line); }
            .verdict .q { font-size: 13px; color: var(--muted); }
            .verdict button { padding: 6px 14px; border-radius: 6px; border: 1px solid var(--border);
                   background: var(--panel); color: var(--fg); cursor: pointer; font-size: 13px; }
            .verdict button:hover:not(:disabled) { background: var(--line); }
            .verdict .done { font-size: 13px; color: #1a7f37; }
            .chip.learn { background: #eaf1fb; color: var(--accent); }
            .teach { margin-top: 10px; padding: 12px 14px; background: #fbfbfc;
                   border: 1px solid var(--line); border-radius: 6px; max-width: 88ch; }
            .teach .opt { display: flex; gap: 8px; align-items: flex-start; padding: 5px 0;
                   font-size: 14px; color: var(--fg-2); cursor: pointer; }
            .teach .opt input { margin-top: 4px; }
            .teach .own { display: grid; gap: 8px; margin: 8px 0 12px 24px; }
            .teach .own textarea { min-height: 62px; flex: 0 0 auto; }
            .mem-row td { vertical-align: top; }
            .mem-title { font-weight: 500; }
            .mem-sample { color: var(--muted-2); font-size: 12px; font-family: var(--mono);
                   overflow-wrap: anywhere; }
            .mem-forget { border: 1px solid var(--border); background: var(--panel); color: var(--muted);
                   border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; }

            /* ── Разделы «Правила», «Форматы», «Справка» ─────────────────── */
            .doc { background: var(--panel); border: 1px solid var(--border); border-radius: 8px;
                   padding: 18px; max-width: 1100px; }
            .doc h2 { font-size: 16px; margin-bottom: 6px; }
            .doc p { color: var(--muted); font-size: 13px; margin-bottom: 14px; max-width: 80ch; }
            .doc pre { background: #fbfbfc; border: 1px solid var(--line); border-radius: 6px;
                   padding: 10px; font-size: 12px; overflow-x: auto; margin: 8px 0; }
            .rule-cond { color: var(--muted); font-family: var(--mono); font-size: 12px;
                   overflow-wrap: anywhere; }
            .hidden { display: none !important; }
            @media (max-width: 1000px) {
              .layout { flex-direction: column; }
              .side { width: 100%; max-height: 58vh; }
              .card-meta { margin-left: 0; width: 100%; }
            }
            @media (max-width: 720px) {
              .addr { display: none; }
              .topbar { gap: 12px; }
              .topbar nav a { padding: 6px 8px; }
            }
            </style>
            </head>
            <body>

            <header class="topbar">
              <span class="brand">log-analyzer</span>
              <nav>
                <a href="#" data-view="analyze" class="active">Анализ</a>
                <a href="#" data-view="rules">Правила</a>
                <a href="#" data-view="memory">Память</a>
                <a href="#" data-view="patterns">Форматы логов</a>
                <a href="#" data-view="help">Справка</a>
              </nav>
              <span class="addr" id="addr"></span>
            </header>

            <div class="layout">
              <aside class="side" id="side">
                <div class="side-top">
                  <div class="step">Шаг 1 · Откуда взять лог</div>
                  <div class="segmented">
                    <button type="button" class="active" data-src="text">Вставить текст</button>
                    <button type="button" data-src="path">Файл на диске</button>
                  </div>
                </div>

                <div class="side-mid" id="src-text">
                  <textarea id="text" spellcheck="false"
                    placeholder="Вставьте фрагмент лога или стек-трейс, либо перетащите сюда файл."></textarea>
                  <p class="hint" id="text-hint">Метки времени и traceId не обязательны. Ctrl+Enter — разобрать.</p>
                </div>

                <div class="side-top hidden" id="src-path">
                  <label class="lbl" for="path">Путь к файлу или каталогу</label>
                  <input type="text" id="path" spellcheck="false" placeholder="C:\\logs\\app.log или /var/log/myapp">
                  <label class="check"><input type="checkbox" id="recursive"> обходить подкаталоги</label>
                  <p class="hint">Файлы читает сам анализатор — подходит для больших логов.</p>
                </div>

                <div class="side-top">
                  <div class="step">Шаг 2 · Что показать</div>
                  <label style="display:block; margin-bottom:10px;">
                    <span class="lbl">Реквизит: ИНН, id платежа, телефон, ФИО</span>
                    <input type="text" id="find" placeholder="например 173497766">
                    <span class="hint">Соберёт цепочку событий по этому значению —
                      вместе со связанными операциями.</span>
                  </label>
                  <label class="check"><input type="checkbox" id="onlyFailed" checked>
                    Только инциденты с ошибками</label>
                  <div class="grid-2">
                    <label>
                      <span class="lbl">Уровень не ниже</span>
                      <select id="minLevel">
                        <option value="">любой</option>
                        <option value="INFO">INFO</option>
                        <option value="WARN">WARN</option>
                        <option value="ERROR">ERROR</option>
                      </select>
                    </label>
                    <label>
                      <span class="lbl">Показать инцидентов</span>
                      <select id="top">
                        <option value="0">все</option>
                        <option value="3">3 самых тяжёлых</option>
                        <option value="10">10 самых тяжёлых</option>
                      </select>
                    </label>
                  </div>
                  <label style="display:block; margin-top:10px;">
                    <span class="lbl">Конкретный traceId (не обязательно)</span>
                    <input type="text" id="trace" placeholder="часть идентификатора">
                  </label>
                </div>

                <div class="side-foot">
                  <button type="button" class="primary" id="run">Разобрать лог</button>
                  <button type="button" class="ghost" id="clear">Очистить</button>
                  <span class="status" id="status"></span>
                </div>
              </aside>

              <main class="main">
                <div class="metrics" id="metrics">
                  <div class="metric"><span class="value" id="m-incidents">—</span>
                    <span class="label">инцидентов</span></div>
                  <div class="metric err"><span class="value" id="m-errors">—</span>
                    <span class="label">ошибок</span></div>
                  <div class="metric warn"><span class="value" id="m-warnings">—</span>
                    <span class="label">предупреждений</span></div>
                  <div class="metric"><span class="value" id="m-events">—</span>
                    <span class="label">событий</span></div>
                  <div class="tools">
                    <input type="search" id="search" placeholder="Поиск по событиям">
                    <button type="button" id="download">Скачать отчёт</button>
                  </div>
                </div>
                <div class="content" id="content">
                  <p class="empty">Вставьте фрагмент лога слева или укажите файл и нажмите «Разобрать лог».
                     Инструмент соберёт события одного запроса в таймлайн и назовёт вероятную причину сбоя.</p>
                </div>
              </main>
            </div>

            <script>
            (function () {
              const el = (id) => document.getElementById(id);
              const content = el('content'), statusEl = el('status');
              let source = 'text';
              let view = 'analyze';
              let lastReport = null;
              // Что анализатор уже знает от пользователя: список нужен разделу «Память»,
              // а флаг enabled — чтобы не предлагать оценку, когда обучение выключено.
              let memory = { enabled: true, records: [] };

              el('addr').textContent = location.host;

              // ── Навигация ────────────────────────────────────────────────
              document.querySelectorAll('.topbar nav a').forEach(function (link) {
                link.addEventListener('click', function (e) {
                  e.preventDefault();
                  document.querySelectorAll('.topbar nav a').forEach((a) => a.classList.remove('active'));
                  link.classList.add('active');
                  view = link.dataset.view;
                  el('side').classList.toggle('hidden', view !== 'analyze');
                  el('metrics').classList.toggle('hidden', view !== 'analyze');
                  if (view === 'analyze') renderReport();
                  else if (view === 'rules') loadRules();
                  else if (view === 'memory') loadMemory();
                  else if (view === 'patterns') loadPatterns();
                  else renderHelp();
                });
              });

              // ── Переключатель источника ──────────────────────────────────
              document.querySelectorAll('.segmented button').forEach(function (button) {
                button.addEventListener('click', function () {
                  document.querySelectorAll('.segmented button').forEach((b) => b.classList.remove('active'));
                  button.classList.add('active');
                  source = button.dataset.src;
                  el('src-text').classList.toggle('hidden', source !== 'text');
                  el('src-text').style.display = source === 'text' ? 'flex' : 'none';
                  el('src-path').classList.toggle('hidden', source !== 'path');
                });
              });

              // ── Запрос к анализатору ─────────────────────────────────────
              function params(format) {
                const q = new URLSearchParams();
                q.set('format', format);
                if (el('onlyFailed').checked) q.set('onlyFailed', 'true');
                if (el('find').value.trim()) q.set('find', el('find').value.trim());
                if (el('trace').value.trim()) q.set('trace', el('trace').value.trim());
                if (el('minLevel').value) q.set('minLevel', el('minLevel').value);
                if (Number(el('top').value) > 0) q.set('top', el('top').value);
                if (source === 'path') {
                  q.set('path', el('path').value.trim());
                  if (el('recursive').checked) q.set('recursive', 'true');
                }
                return q;
              }

              function setStatus(message, isError) {
                statusEl.textContent = message || '';
                statusEl.classList.toggle('error', !!isError);
              }

              async function request(format) {
                if (source === 'text' && !el('text').value.trim()) {
                  setStatus('Вставьте фрагмент лога', true); el('text').focus(); return null;
                }
                if (source === 'path' && !el('path').value.trim()) {
                  setStatus('Укажите путь', true); el('path').focus(); return null;
                }
                el('run').disabled = true;
                setStatus('Разбираю…');
                const started = performance.now();
                try {
                  const response = await fetch('/api/analyze?' + params(format).toString(), {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
                    body: source === 'text' ? el('text').value : ''
                  });
                  const body = await response.text();
                  if (!response.ok) { setStatus(body || ('Ошибка ' + response.status), true); return null; }
                  setStatus('Готово за ' + Math.round(performance.now() - started) + ' мс');
                  return body;
                } catch (e) {
                  setStatus('Сервер недоступен: ' + e.message, true);
                  return null;
                } finally {
                  el('run').disabled = false;
                }
              }

              async function analyse() {
                const body = await request('json');
                if (body === null) return;
                lastReport = JSON.parse(body);
                renderReport();
              }

              async function download() {
                const body = await request('html');
                if (body === null) return;
                const url = URL.createObjectURL(new Blob([body], { type: 'text/html;charset=utf-8' }));
                const link = document.createElement('a');
                link.href = url; link.download = 'log-report.html'; link.click();
                URL.revokeObjectURL(url);
              }

              // ── Рендер отчёта ────────────────────────────────────────────
              const esc = (s) => String(s === null || s === undefined ? '' : s)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

              function shortLogger(name) {
                if (!name) return '';
                const parts = name.split('.');
                return parts[parts.length - 1];
              }

              function time(iso) {
                if (!iso) return '—';
                const d = new Date(iso);
                if (isNaN(d)) return '—';
                const pad = (n, w) => String(n).padStart(w, '0');
                return pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':'
                     + pad(d.getSeconds(), 2) + '.' + pad(d.getMilliseconds(), 3);
              }

              function duration(ms) {
                if (ms === null || ms === undefined) return '';
                if (ms < 1000) return Math.round(ms) + ' мс';
                if (ms < 60000) return (ms / 1000).toFixed(1) + ' с';
                return Math.floor(ms / 60000) + ' мин ' + Math.round((ms % 60000) / 1000) + ' с';
              }

              function levelClass(level, hasException) {
                if (level === 'ERROR' || level === 'FATAL' || hasException) return 'err';
                if (level === 'WARN') return 'warn';
                return '';
              }

              function stackText(exception) {
                let out = '', current = exception, first = true, guard = 0;
                while (current && guard++ < 32) {
                  out += (first ? '' : 'Caused by: ') + current.type
                       + (current.message ? ': ' + current.message : '') + String.fromCharCode(10);
                  (current.frames || []).forEach(function (f) {
                    out += '    at ' + f.declaringClass + '.' + f.methodName
                         + '(' + (f.fileName || 'Unknown Source')
                         + (f.lineNumber ? ':' + f.lineNumber : '') + ')' + String.fromCharCode(10);
                  });
                  if (current.framesOmitted) out += '    ... ' + current.framesOmitted + ' more'
                       + String.fromCharCode(10);
                  current = current.cause; first = false;
                }
                return out;
              }

              function renderScale(timeline) {
                const start = timeline.start ? Date.parse(timeline.start) : null;
                const end = timeline.end ? Date.parse(timeline.end) : null;
                if (start === null || end === null || end <= start) return '';
                let dots = '';
                timeline.entries.forEach(function (entry) {
                  const at = entry.event.timestamp ? Date.parse(entry.event.timestamp) : null;
                  if (at === null) return;
                  const left = ((at - start) / (end - start) * 100).toFixed(2);
                  const cls = levelClass(entry.event.level, !!entry.event.exception);
                  dots += '<span class="dot ' + cls + '" style="left:' + left + '%" data-ref="'
                       + esc(entry.id) + '" title="' + esc(oneLine(entry.event)) + '"></span>';
                });
                return '<div class="scale"><div class="track"><div class="line"></div>' + dots + '</div>'
                     + '<div class="scale-ends"><span>' + time(timeline.start) + '</span><span>'
                     + time(timeline.end) + '</span></div></div>';
              }

              function oneLine(event) {
                let text = event.message || '';
                if (!text && event.http) text = (event.http.method || '') + ' ' + (event.http.url || '');
                if (!text && event.exception) text = event.exception.type;
                return text.split(String.fromCharCode(10))[0];
              }

              function renderRows(timeline) {
                const start = timeline.start ? Date.parse(timeline.start) : null;
                return timeline.entries.map(function (entry) {
                  const event = entry.event;
                  const cls = levelClass(event.level, !!event.exception);
                  const at = event.timestamp ? Date.parse(event.timestamp) : null;
                  const offset = (at !== null && start !== null) ? duration(at - start) : '';
                  let message = '<div>' + esc(oneLine(event));
                  if (entry.repeatCount > 1) message += ' <span class="tag">×' + entry.repeatCount + '</span>';
                  (entry.annotations || []).forEach(function (a) {
                    const tagCls = a.type === 'MATCH' ? 'hit'
                      : (a.type === 'EXCEPTION' || a.type === 'ERROR' || a.type === 'HTTP_SERVER_ERROR'
                      ? 'err' : (a.type === 'WARNING' || a.type === 'TIMEOUT' || a.type === 'RETRY'
                      || a.type === 'SLOW' ? 'warn' : ''));
                    const full = a.label || a.type;
                    const short = full.length > 44 ? full.slice(0, 41) + '…' : full;
                    message += ' <span class="tag ' + tagCls + '" title="'
                             + esc(full + (a.rule ? ' (правило ' + a.rule + ')' : '')) + '">'
                             + esc(short) + '</span>';
                  });
                  message += '</div>';
                  if (event.exception) {
                    message += '<details><summary>' + esc(event.exception.type.split('.').pop())
                             + ' — показать стек</summary><pre>' + esc(stackText(event.exception))
                             + '</pre></details>';
                  }
                  return '<tr id="' + esc(entry.id) + '" class="' + cls + '">'
                       + '<td class="c-id">' + esc(entry.id) + '</td>'
                       + '<td class="c-time t-time">' + time(event.timestamp)
                       + (offset ? ' <span class="t-offset">+' + offset + '</span>' : '') + '</td>'
                       + '<td class="c-level"><span class="lvl ' + cls + '">'
                       + esc(event.level === 'UNKNOWN' ? '' : event.level) + '</span></td>'
                       + '<td class="c-logger t-logger" title="' + esc(event.logger || '') + '">'
                       + esc(shortLogger(event.logger)) + '</td>'
                       + '<td class="c-msg">' + message + '</td></tr>';
                }).join('');
              }

              // ── Оценка ответа: как пользователь говорит «верно» или «нет» ──
              function learnBadge(cause) {
                const mark = cause.learned;
                if (!mark) return '';
                let label = '';
                if (mark.taught) label = 'ваша формулировка';
                else {
                  const parts = [];
                  if (mark.confirmations) parts.push('подтверждено ' + mark.confirmations + '×');
                  if (mark.rejections) parts.push('отвергалось ' + mark.rejections + '×');
                  label = parts.join(', ');
                }
                return label ? '<span class="chip learn">' + esc(label) + '</span>' : '';
              }

              // Строка образца инцидента: по ней запись узнаётся в разделе «Память».
              function sampleOf(timeline) {
                const entries = timeline.entries || [];
                for (let i = 0; i < entries.length; i++) {
                  const event = entries[i].event;
                  if (event.exception) {
                    let deepest = event.exception, guard = 0;
                    while (deepest.cause && guard++ < 32) deepest = deepest.cause;
                    return deepest.type + (deepest.message ? ': ' + deepest.message : '');
                  }
                  if (event.level === 'ERROR' || event.level === 'FATAL') return oneLine(event);
                }
                return entries.length ? oneLine(entries[0].event) : '';
              }

              function causeRef(cause) {
                return cause ? { title: cause.title, rule: cause.rule || '', source: cause.source || '' } : null;
              }

              /*
               * Форма обратной связи. Разбор ценен ровно настолько, насколько ему доверяют,
               * поэтому вопрос задаётся прямо под ответом: «верно» — версия закрепляется,
               * «нет» — открывается выбор правильной версии среди остальных либо своя
               * формулировка. И то, и другое запоминается для следующих таких инцидентов.
               */
              function renderVerdict(timeline, index) {
                if (!memory.enabled || !timeline.signature) return '';
                const cause = timeline.rootCause;
                const options = (timeline.alternatives || []).map(function (alt, i) {
                  return '<label class="opt"><input type="radio" name="fix' + index + '" value="' + i + '">'
                       + '<span>' + esc(alt.title) + '</span></label>';
                }).join('');
                const head = cause
                  ? '<span class="q">Причина названа верно?</span>'
                    + '<button type="button" class="v-yes">Да, это оно</button>'
                    + '<button type="button" class="v-no">Нет</button>'
                  : '<span class="q">Причина не определена.</span>'
                    + '<button type="button" class="v-no">Указать причину</button>';
                return '<div class="verdict" data-tl="' + index + '">' + head
                     + '<span class="done hidden"></span></div>'
                     + '<div class="teach hidden">'
                     + '<div class="plan-title">Что было причиной на самом деле?</div>'
                     + options
                     + '<label class="opt"><input type="radio" name="fix' + index + '" value="own" checked>'
                     + '<span>Своя формулировка</span></label>'
                     + '<div class="own">'
                     + '<input type="text" class="f-title" placeholder="Причина одной строкой">'
                     + '<input type="text" class="f-rec" placeholder="Что делать — одной строкой">'
                     + '<textarea class="f-steps" spellcheck="false" '
                     + 'placeholder="Шаги разбора — по одному в строке (не обязательно)"></textarea>'
                     + '</div>'
                     + '<button type="button" class="primary f-save">Запомнить</button>'
                     + '</div>';
              }

              async function sendFeedback(payload, bar, okMessage) {
                try {
                  const response = await fetch('/api/feedback', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json; charset=utf-8' },
                    body: JSON.stringify(payload)
                  });
                  const body = await response.text();
                  if (!response.ok) { setStatus(body || 'Отзыв не сохранён', true); return; }
                  const done = bar.querySelector('.done');
                  done.classList.remove('hidden');
                  done.innerHTML = esc(okMessage) + ' <a href="#" class="re-run">Разобрать заново</a>';
                  done.querySelector('.re-run').addEventListener('click', function (e) {
                    e.preventDefault(); analyse();
                  });
                  bar.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
                  setStatus('Запомнено');
                } catch (e) {
                  setStatus('Сервер недоступен: ' + e.message, true);
                }
              }

              function bindVerdict(bar) {
                const index = Number(bar.dataset.tl);
                const timeline = (lastReport.timelines || [])[index];
                if (!timeline) return;
                const teach = bar.nextElementSibling;
                const yes = bar.querySelector('.v-yes');
                if (yes) {
                  yes.addEventListener('click', function () {
                    sendFeedback({
                      signature: timeline.signature,
                      verdict: 'correct',
                      cause: causeRef(timeline.rootCause),
                      sample: sampleOf(timeline)
                    }, bar, 'Запомнил: причина названа верно.');
                  });
                }
                bar.querySelector('.v-no').addEventListener('click', function () {
                  teach.classList.toggle('hidden');
                  const field = teach.querySelector('.f-title');
                  if (!teach.classList.contains('hidden')) field.focus();
                });
                teach.querySelectorAll('input[type=radio]').forEach(function (radio) {
                  radio.addEventListener('change', function () {
                    teach.querySelector('.own').classList.toggle('hidden', radio.value !== 'own');
                  });
                });
                teach.querySelector('.f-save').addEventListener('click', function () {
                  const payload = { signature: timeline.signature, sample: sampleOf(timeline) };
                  if (timeline.rootCause) {
                    payload.verdict = 'wrong';
                    payload.cause = causeRef(timeline.rootCause);
                  }
                  const picked = teach.querySelector('input[type=radio]:checked');
                  if (picked && picked.value !== 'own') {
                    payload.correct = causeRef((timeline.alternatives || [])[Number(picked.value)]);
                  } else {
                    const title = teach.querySelector('.f-title').value.trim();
                    if (!title) {
                      setStatus('Напишите, что было причиной', true);
                      teach.querySelector('.f-title').focus();
                      return;
                    }
                    payload.taught = {
                      title: title,
                      recommendation: teach.querySelector('.f-rec').value.trim(),
                      steps: teach.querySelector('.f-steps').value
                    };
                  }
                  teach.classList.add('hidden');
                  sendFeedback(payload, bar, 'Запомнил: при следующем таком инциденте покажу это.');
                });
              }

              function renderCause(timeline, index) {
                const cause = timeline.rootCause;
                if (!cause) {
                  return timeline.failed
                    ? '<div class="cause"><p class="note">Причина не определена автоматически.</p>'
                      + renderVerdict(timeline, index) + '</div>'
                    : '';
                }
                const percent = Math.round((cause.confidence || 0) * 100);
                const evidence = (cause.evidence || [])
                  .map((e) => '<a href="#" class="ref" data-ref="' + esc(e.entryId) + '">' + esc(e.entryId) + '</a>')
                  .join(', ');
                let refs = '';
                if (evidence) refs += '<span>Основание: ' + evidence + '</span>';
                if (cause.rule) refs += '<span>Правило: <code>' + esc(cause.rule) + '</code></span>';
                if ((timeline.alternatives || []).length) {
                  refs += '<a href="#" class="alts">Другие версии (' + timeline.alternatives.length + ')</a>';
                }
                const alts = (timeline.alternatives || []).map(function (a) {
                  return '<p class="note">' + Math.round((a.confidence || 0) * 100) + '% — ' + esc(a.title) + '</p>';
                }).join('');
                const steps = (cause.steps || []).length
                  ? '<div class="plan"><div class="plan-title">Что делать</div><ol>'
                    + cause.steps.map((s) => '<li>' + esc(s) + '</li>').join('') + '</ol></div>'
                  : (cause.recommendation
                     ? '<div class="plan"><div class="plan-title">Что делать</div><p>'
                       + esc(cause.recommendation) + '</p></div>'
                     : '');
                const summaryLine = cause.recommendation && (cause.steps || []).length
                  ? '<p class="lead">' + esc(cause.recommendation) + '</p>' : '';
                return '<div class="cause">'
                     + '<div class="cause-head"><span class="step" style="margin:0">Вероятная причина</span>'
                     + '<span class="bar"><span style="width:' + percent + '%"></span></span>'
                     + '<span class="note">уверенность ' + percent + '%</span>'
                     + learnBadge(cause) + '</div>'
                     + '<h3>' + esc(cause.title) + '</h3>'
                     + summaryLine
                     + (cause.description ? '<p>' + esc(cause.description) + '</p>' : '')
                     + steps
                     + '<div class="refs">' + refs + '</div>'
                     + '<div class="alt-list hidden">' + alts + '</div>'
                     + renderVerdict(timeline, index) + '</div>';
              }

              function renderReport() {
                if (view !== 'analyze') return;
                if (!lastReport) return;
                const summary = lastReport.summary || {};
                el('m-incidents').textContent = summary.timelines || 0;
                el('m-errors').textContent = summary.errorEvents || 0;
                el('m-warnings').textContent = summary.warningEvents || 0;
                el('m-events').textContent = summary.totalEvents || 0;

                const timelines = lastReport.timelines || [];
                const requisite = el('find').value.trim();
                if (!timelines.length) {
                  content.innerHTML = requisite
                    ? '<p class="empty">По реквизиту <b>' + esc(requisite) + '</b> цепочек не найдено.'
                      + ' Проверьте написание — поиск идёт по подстроке. Если значение точно есть в логе,'
                      + ' снимите фильтр «только инциденты с ошибками»: в этой цепочке ошибок может не быть.</p>'
                    : '<p class="empty">Инцидентов не найдено. '
                      + 'Попробуйте снять фильтр «только с ошибками» или проверьте формат логов '
                      + 'в разделе «Форматы логов».</p>';
                  return;
                }
                // Шапка поиска: сколько цепочек связано с реквизитом и сколько событий его содержат.
                let searchNote = '';
                if (requisite) {
                  let hits = 0;
                  timelines.forEach(function (timeline) {
                    (timeline.entries || []).forEach(function (entry) {
                      if ((entry.annotations || []).some((a) => a.type === 'MATCH')) hits++;
                    });
                  });
                  searchNote = '<p class="note" style="margin-bottom:14px">Реквизит <b>'
                    + esc(requisite) + '</b>: цепочек — ' + timelines.length
                    + ', событий с совпадением — ' + hits
                    + '. Совпадения помечены в таблице.</p>';
                }

                content.innerHTML = searchNote + timelines.map(function (timeline, index) {
                  const kind = { TRACE: 'traceId', REQUEST: 'requestId', SESSION: 'sessionId',
                                 THREAD: 'поток', FILE: 'источник' }[timeline.correlationKind] || '';
                  const services = (timeline.services || []).join(', ');
                  const meta = [services, (timeline.eventCount || 0) + ' событий',
                                duration(timeline.durationMs)].filter(Boolean).join(' · ');
                  return '<section class="card">'
                       + '<div class="card-head">'
                       + '<span class="chip ' + (timeline.failed ? 'err' : 'ok') + '">'
                       + (timeline.failed ? 'С ошибкой' : 'Без ошибок') + '</span>'
                       + '<span class="card-title">Инцидент ' + (index + 1) + '</span>'
                       + '<code class="note">' + esc(kind) + ' ' + esc(timeline.correlationId) + '</code>'
                       + '<span class="card-meta">' + esc(meta) + '</span></div>'
                       + renderCause(timeline, index)
                       + renderScale(timeline)
                       + '<table><thead><tr><th class="c-id">№</th><th class="c-time">время</th>'
                       + '<th class="c-level">уровень</th><th class="c-logger">логгер</th>'
                       + '<th class="c-msg">событие</th></tr></thead>'
                       + '<tbody>' + renderRows(timeline) + '</tbody></table>'
                       + '</section>';
                }).join('') + (el('onlyFailed').checked
                  ? '<p class="note">Инциденты без ошибок скрыты фильтром «только с ошибками».</p>' : '');

                bindResultHandlers();
                applySearch();
              }

              function bindResultHandlers() {
                content.querySelectorAll('[data-ref]').forEach(function (node) {
                  node.addEventListener('click', function (e) {
                    e.preventDefault();
                    const row = document.getElementById(node.dataset.ref);
                    if (!row) return;
                    content.querySelectorAll('tr.highlight').forEach((r) => r.classList.remove('highlight'));
                    row.classList.add('highlight');
                    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
                  });
                });
                content.querySelectorAll('.alts').forEach(function (link) {
                  link.addEventListener('click', function (e) {
                    e.preventDefault();
                    const list = link.closest('.cause').querySelector('.alt-list');
                    list.classList.toggle('hidden');
                  });
                });
                content.querySelectorAll('.verdict').forEach(bindVerdict);
              }

              function applySearch() {
                const q = el('search').value.trim().toLowerCase();
                content.querySelectorAll('section.card').forEach(function (card) {
                  let visible = 0;
                  card.querySelectorAll('tbody tr').forEach(function (row) {
                    const match = !q || row.textContent.toLowerCase().indexOf(q) >= 0;
                    row.style.display = match ? '' : 'none';
                    if (match) visible++;
                  });
                  card.style.display = visible ? '' : 'none';
                });
              }

              // ── Разделы «Правила» и «Форматы логов» ──────────────────────
              async function loadRules() {
                content.innerHTML = '<p class="empty">Загружаю правила…</p>';
                const rules = await (await fetch('/api/rules')).json();
                content.innerHTML = '<div class="doc"><h2>Правила анализа (' + rules.length + ')</h2>'
                  + '<p>Правило помечает событие и предлагает формулировку причины. Свои правила '
                  + 'подключаются ключом <code>--rules my-rules.yaml</code>; за основу удобно взять '
                  + 'встроенные: <code>log-analyzer rules --export my-rules.yaml</code>.</p>'
                  + '<table><thead><tr><th>правило</th><th>условие</th><th>причина</th>'
                  + '<th>уверенность</th></tr></thead><tbody>'
                  + rules.map(function (rule) {
                      return '<tr><td class="mono">' + esc(rule.name) + '</td>'
                           + '<td class="rule-cond">' + esc(rule.condition) + '</td>'
                           + '<td>' + esc(rule.cause || rule.description || '') + '</td>'
                           + '<td>' + (rule.confidence ? Math.round(rule.confidence * 100) + '%' : '') + '</td></tr>';
                    }).join('')
                  + '</tbody></table></div>';
              }

              // ── Раздел «Память» ─────────────────────────────────────────
              async function fetchMemory() {
                try {
                  memory = await (await fetch('/api/feedback')).json();
                } catch (e) {
                  memory = { enabled: false, records: [] };
                }
                return memory;
              }

              async function loadMemory() {
                content.innerHTML = '<p class="empty">Читаю память…</p>';
                await fetchMemory();
                const records = memory.records || [];
                const kind = { TAUGHT: 'ваша формулировка', CONFIRMED: 'подтверждено',
                               REJECTED: 'отвергнуто' };
                const head = '<div class="doc"><h2>Что анализатор запомнил (' + records.length + ')</h2>'
                  + '<p>Здесь копятся ваши оценки: подтверждённая причина в следующий раз '
                  + 'показывается первой, отвергнутая — опускается, а ваша формулировка '
                  + 'подставляется как готовый ответ. Инцидент опознаётся по сигнатуре — '
                  + 'отпечатку исключения, логгера и сообщения без номеров и идентификаторов, '
                  + 'поэтому урок применяется и к завтрашнему такому же сбою.</p>'
                  + (memory.enabled ? '' : '<p><b>Обучение выключено в конфигурации '
                     + '(learning.enabled: false)</b> — новые оценки не сохраняются.</p>')
                  + (memory.warning ? '<p><b>' + esc(memory.warning) + '</b></p>' : '')
                  + (memory.file ? '<p>Файл памяти: <code>' + esc(memory.file) + '</code></p>' : '');
                if (!records.length) {
                  content.innerHTML = head + '<p>Пока пусто. Разберите лог и на карточке инцидента '
                    + 'ответьте, верно ли названа причина.</p></div>';
                  return;
                }
                content.innerHTML = head
                  + '<table><thead><tr><th class="c-id">инцидент</th><th>оценка</th>'
                  + '<th>причина</th><th class="c-level"></th></tr></thead><tbody>'
                  + records.map(function (r) {
                      const counters = [];
                      if (r.confirmations) counters.push('+' + r.confirmations);
                      if (r.rejections) counters.push('−' + r.rejections);
                      return '<tr class="mem-row"><td class="mono">' + esc(r.signature) + '</td>'
                           + '<td>' + esc(kind[r.kind] || r.kind)
                           + (counters.length ? ' <span class="tag">' + counters.join(' / ') + '</span>' : '')
                           + '</td>'
                           + '<td><div class="mem-title">' + esc(r.title || '') + '</div>'
                           + (r.recommendation ? '<div class="note">' + esc(r.recommendation) + '</div>' : '')
                           + (r.sample ? '<div class="mem-sample">' + esc(r.sample) + '</div>' : '')
                           + '</td>'
                           + '<td><button type="button" class="mem-forget" data-sig="' + esc(r.signature)
                           + '" data-key="' + esc(r.causeKey) + '">забыть</button></td></tr>';
                    }).join('')
                  + '</tbody></table></div>';

                content.querySelectorAll('.mem-forget').forEach(function (button) {
                  button.addEventListener('click', async function () {
                    await fetch('/api/feedback/forget', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json; charset=utf-8' },
                      body: JSON.stringify({ signature: button.dataset.sig, causeKey: button.dataset.key })
                    });
                    loadMemory();
                  });
                });
              }

              async function loadPatterns() {
                const patterns = await (await fetch('/api/patterns')).json();
                content.innerHTML = '<div class="doc"><h2>Форматы логов</h2>'
                  + '<p>Строки разбираются шаблонами по порядку — от специфичных к общим. '
                  + 'Если ваш формат не распознаётся, проверьте строку и добавьте свой шаблон '
                  + 'в конфигурацию (<code>parse.patterns</code>).</p>'
                  + '<label class="lbl">Проверить строку лога</label>'
                  + '<input type="text" id="probe" placeholder="вставьте одну строку лога">'
                  + '<div id="probe-result"></div>'
                  + '<table><thead><tr><th>шаблон</th><th>регулярное выражение</th></tr></thead><tbody>'
                  + patterns.map((p) => '<tr><td class="mono">' + esc(p.name) + '</td>'
                      + '<td class="rule-cond">' + esc(p.regex) + '</td></tr>').join('')
                  + '</tbody></table></div>';

                el('probe').addEventListener('keydown', async function (e) {
                  if (e.key !== 'Enter') return;
                  const result = await (await fetch('/api/patterns', {
                    method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8' },
                    body: el('probe').value
                  })).json();
                  el('probe-result').innerHTML = result.matched
                    ? '<pre>шаблон:   ' + esc(result.pattern) + String.fromCharCode(10)
                      + 'время:    ' + esc(result.timestamp) + String.fromCharCode(10)
                      + 'уровень:  ' + esc(result.level) + String.fromCharCode(10)
                      + 'логгер:   ' + esc(result.logger) + String.fromCharCode(10)
                      + 'поток:    ' + esc(result.thread) + String.fromCharCode(10)
                      + 'traceId:  ' + esc(result.traceId) + String.fromCharCode(10)
                      + 'сообщение: ' + esc(result.message) + '</pre>'
                    : '<pre>Ни один шаблон не подошёл. Строка будет считаться продолжением предыдущей '
                      + 'записи или нераспознанной.</pre>';
                });
              }

              function renderHelp() {
                content.innerHTML = '<div class="doc"><h2>Как этим пользоваться</h2>'
                  + '<p>Слева выберите источник: вставьте отрывок лога (метки времени и traceId не обязательны — '
                  + 'голый стек-трейс тоже разбирается) или укажите путь к файлу либо каталогу. '
                  + 'Нажмите «Разобрать лог».</p>'
                  + '<p>Инструмент соберёт события одного запроса в цепочку по traceId (или по потоку, если '
                  + 'трассировки нет), схлопнет повторы, пометит таймауты, ретраи и внешние вызовы, '
                  + 'развернёт цепочку <code>Caused by</code> до первопричины и назовёт вероятную причину '
                  + 'с оценкой уверенности.</p>'
                  + '<h2>Поиск по реквизиту</h2>'
                  + '<p>Поле «Реквизит» в шаге 2 собирает историю конкретной операции: введите ИНН, '
                  + 'идентификатор платежа, номер телефона или ФИО — и в отчёт попадут цепочки, '
                  + 'где это значение встретилось, целиком: со всеми шагами обработки, ответами '
                  + 'провайдера и выводом о причине. Сами совпадения помечены в таблице.</p>'
                  + '<p>Если в найденной записи назван идентификатор соседней операции — скажем, '
                  + 'телефон абонента и номер платежа стоят в одной строке, — её цепочка тоже '
                  + 'попадёт в отчёт. Именно так по жалобе клиента находится вся история платежа.</p>'
                  + '<p>Большие файлы читаются в два прохода и отбираются по тексту до разбора, '
                  + 'поэтому поиск в дневном логе на десятки мегабайт занимает секунды. '
                  + 'То же самое из консоли: <code>log-analyzer find 173497766 -i optima.log</code>.</p>'
                  + '<h2>Как он учится</h2>'
                  + '<p>Под каждой названной причиной есть вопрос «Причина названа верно?». '
                  + 'Ответ «да» закрепляет версию: в следующий раз такой же сбой начнётся сразу с неё. '
                  + 'Ответ «нет» открывает выбор — можно указать верную версию среди остальных '
                  + 'или написать свою формулировку с планом действий; она станет ответом для всех '
                  + 'следующих таких инцидентов.</p>'
                  + '<p>Инцидент опознаётся по сигнатуре — отпечатку цепочки исключений, места в коде, '
                  + 'логгера и сообщения, из которого убраны идентификаторы, числа и время. Поэтому '
                  + 'урок переносится на такой же сбой с другим traceId и в другом логе. Накопленное '
                  + 'лежит в разделе «Память», а на диске — в одном JSON-файле: его можно положить '
                  + 'в репозиторий команды и раздать коллегам '
                  + '(<code>log-analyzer feedback --export team.json</code>).</p>'
                  + '<h2>То же самое из консоли</h2>'
                  + '<pre>log-analyzer analyze -i app.log --only-failed'
                  + String.fromCharCode(10) + 'log-analyzer analyze -i logs/ -r -f html -o report.html'
                  + String.fromCharCode(10) + 'log-analyzer analyze --clipboard'
                  + String.fromCharCode(10) + 'log-analyzer analyze -i build/logs -f json --fail-on-error</pre>'
                  + '<p>Последняя команда возвращает код 3, если найдены инциденты с ошибками — удобно для CI.</p>'
                  + '</div>';
              }

              // ── События ──────────────────────────────────────────────────
              el('run').addEventListener('click', analyse);
              el('download').addEventListener('click', download);
              el('search').addEventListener('input', applySearch);
              el('clear').addEventListener('click', function () {
                el('text').value = ''; lastReport = null; setStatus('');
                content.innerHTML = '<p class="empty">Вставьте фрагмент лога слева или укажите файл '
                  + 'и нажмите «Разобрать лог».</p>';
                ['m-incidents', 'm-errors', 'm-warnings', 'm-events'].forEach((id) => el(id).textContent = '—');
                el('text').focus();
              });
              el('text').addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); analyse(); }
              });

              ['dragenter', 'dragover'].forEach((type) => el('text').addEventListener(type, function (e) {
                e.preventDefault(); el('text').classList.add('drag');
              }));
              ['dragleave', 'drop'].forEach((type) => el('text').addEventListener(type, function () {
                el('text').classList.remove('drag');
              }));
              el('text').addEventListener('drop', function (e) {
                e.preventDefault();
                const file = e.dataTransfer.files && e.dataTransfer.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = function () {
                  el('text').value = reader.result;
                  el('text-hint').textContent = 'Загружен ' + file.name + ' ('
                    + Math.round(file.size / 1024) + ' КБ). Ctrl+Enter — разобрать.';
                };
                reader.readAsText(file, 'UTF-8');
              });

              fetchMemory();
              el('text').focus();
            })();
            </script>
            </body>
            </html>
            """;
}
