package io.github.loganalyzer.cli.ui;

/**
 * Страница локального интерфейса: разметка, стили и скрипт в одном файле.
 *
 * <p>Сам отчёт страница не рисует — она запрашивает у сервера готовый HTML-отчёт
 * и показывает его во фрейме. Так интерфейс и отчёт остаются одним и тем же кодом,
 * а стили отчёта не смешиваются со стилями страницы.
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
            <title>Анализатор логов</title>
            <style>
            :root {
              --bg: #f7f8fa; --fg: #1f2328; --muted: #6b7280; --card: #ffffff;
              --border: #e3e6ea; --accent: #0969da; --err: #d1242f; --ok: #1a7f37;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #0d1117; --fg: #e6edf3; --muted: #8b949e; --card: #161b22;
                --border: #30363d; --accent: #58a6ff; --err: #ff7b72; --ok: #3fb950;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--fg); height: 100vh;
                   display: flex; flex-direction: column;
                   font: 14px/1.5 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
            header { padding: 14px 20px 0; }
            h1 { font-size: 18px; margin: 0 0 2px; }
            .sub { color: var(--muted); font-size: 12px; margin: 0 0 12px; }
            main { flex: 1; display: flex; gap: 16px; padding: 0 20px 16px; min-height: 0; }
            .panel { background: var(--card); border: 1px solid var(--border); border-radius: 10px;
                     padding: 14px; display: flex; flex-direction: column; min-height: 0; }
            .input-panel { width: 42%; min-width: 340px; }
            .result-panel { flex: 1; min-width: 0; padding: 0; overflow: hidden; }
            .tabs { display: flex; gap: 6px; margin-bottom: 10px; }
            .tab { padding: 6px 12px; border-radius: 6px; border: 1px solid var(--border);
                   background: transparent; color: var(--fg); cursor: pointer; font-size: 13px; }
            .tab.active { background: var(--accent); border-color: var(--accent); color: #fff; }
            textarea { flex: 1; min-height: 200px; width: 100%; resize: none; padding: 10px;
                       border: 1px solid var(--border); border-radius: 8px; background: var(--bg);
                       color: var(--fg); font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
            textarea.drag { border-color: var(--accent); border-style: dashed; }
            input[type=text], input[type=number], select {
                       padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px;
                       background: var(--bg); color: var(--fg); font-size: 13px; }
            input[type=text] { width: 100%; }
            label { font-size: 13px; }
            .field { margin-bottom: 10px; }
            .field > label { display: block; color: var(--muted); font-size: 12px; margin-bottom: 4px; }
            .options { display: flex; flex-wrap: wrap; gap: 10px 14px; align-items: center;
                       margin: 12px 0; font-size: 13px; }
            .options .grow { flex: 1 1 130px; }
            .actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
            button.primary { background: var(--accent); border: 1px solid var(--accent); color: #fff;
                       padding: 8px 16px; border-radius: 6px; cursor: pointer; font-size: 14px; }
            button.secondary { background: transparent; border: 1px solid var(--border); color: var(--fg);
                       padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; }
            button:disabled { opacity: .55; cursor: default; }
            .status { font-size: 12px; color: var(--muted); margin-left: auto; }
            .status.error { color: var(--err); }
            .hint { color: var(--muted); font-size: 12px; margin-top: 8px; }
            .hint code { background: var(--bg); padding: 1px 4px; border-radius: 4px; }
            iframe { width: 100%; height: 100%; border: 0; background: var(--card); }
            .placeholder { height: 100%; display: flex; align-items: center; justify-content: center;
                       color: var(--muted); text-align: center; padding: 24px; }
            .hidden { display: none !important; }
            @media (max-width: 900px) {
              main { flex-direction: column; }
              .input-panel { width: 100%; }
              .result-panel { min-height: 420px; }
            }
            </style>
            </head>
            <body>
            <header>
              <h1>Анализатор логов Java/Spring</h1>
              <p class="sub">Вставьте отрывок лога или укажите файл — инструмент соберёт таймлайн инцидента
                 и назовёт вероятную причину.</p>
            </header>

            <main>
              <section class="panel input-panel">
                <div class="tabs">
                  <button class="tab active" data-tab="text" type="button">Отрывок</button>
                  <button class="tab" data-tab="path" type="button">Файл или каталог</button>
                </div>

                <div id="tab-text" style="display:flex; flex-direction:column; flex:1; min-height:0;">
                  <textarea id="text" spellcheck="false"
                    placeholder="Вставьте сюда фрагмент лога или стек-трейс (Ctrl+V), либо перетащите файл.&#10;&#10;Метки времени и traceId не обязательны — голый стек-трейс тоже разбирается."></textarea>
                  <p class="hint" id="text-hint">Ctrl+Enter — анализировать. Перетаскивание файла подставит его содержимое.</p>
                </div>

                <div id="tab-path" class="hidden">
                  <div class="field">
                    <label for="path">Путь к файлу или каталогу с логами</label>
                    <input type="text" id="path" spellcheck="false" placeholder="C:\\logs\\app.log или /var/log/myapp">
                  </div>
                  <label><input type="checkbox" id="recursive"> обходить подкаталоги</label>
                  <p class="hint">Читает файлы прямо с диска — подходит для больших логов, которые не стоит
                     копировать в браузер. Сервер работает только на этом компьютере.</p>
                </div>

                <div class="options">
                  <label><input type="checkbox" id="onlyFailed" checked> только с ошибками</label>
                  <label class="grow">traceId
                    <input type="text" id="trace" placeholder="часть идентификатора" style="width:100%">
                  </label>
                  <label>уровень
                    <select id="minLevel">
                      <option value="">любой</option>
                      <option value="DEBUG">DEBUG+</option>
                      <option value="INFO">INFO+</option>
                      <option value="WARN">WARN+</option>
                      <option value="ERROR">ERROR+</option>
                    </select>
                  </label>
                  <label>топ
                    <input type="number" id="top" min="0" step="1" value="0" style="width:70px">
                  </label>
                </div>

                <div class="actions">
                  <button class="primary" id="run" type="button">Анализировать</button>
                  <button class="secondary" id="download-json" type="button">Скачать JSON</button>
                  <button class="secondary" id="download-html" type="button">Скачать HTML</button>
                  <button class="secondary" id="clear" type="button">Очистить</button>
                  <span class="status" id="status"></span>
                </div>
              </section>

              <section class="panel result-panel">
                <div class="placeholder" id="placeholder">
                  Здесь появится таймлайн инцидента с вероятной причиной.<br>
                  Вставьте отрывок слева и нажмите «Анализировать».
                </div>
                <iframe id="result" class="hidden" title="Отчёт"></iframe>
              </section>
            </main>

            <script>
            (function () {
              const el = (id) => document.getElementById(id);
              const textArea = el('text'), pathInput = el('path'), statusEl = el('status');
              const frame = el('result'), placeholder = el('placeholder');
              let tab = 'text';

              document.querySelectorAll('.tab').forEach(function (button) {
                button.addEventListener('click', function () {
                  document.querySelectorAll('.tab').forEach((b) => b.classList.remove('active'));
                  button.classList.add('active');
                  tab = button.dataset.tab;
                  el('tab-text').classList.toggle('hidden', tab !== 'text');
                  el('tab-text').style.display = tab === 'text' ? 'flex' : 'none';
                  el('tab-path').classList.toggle('hidden', tab !== 'path');
                });
              });

              function params(format) {
                const q = new URLSearchParams();
                q.set('format', format);
                if (el('onlyFailed').checked) q.set('onlyFailed', 'true');
                if (el('trace').value.trim()) q.set('trace', el('trace').value.trim());
                if (el('minLevel').value) q.set('minLevel', el('minLevel').value);
                if (Number(el('top').value) > 0) q.set('top', el('top').value);
                if (tab === 'path') {
                  q.set('path', pathInput.value.trim());
                  if (el('recursive').checked) q.set('recursive', 'true');
                }
                return q;
              }

              function setStatus(message, isError) {
                statusEl.textContent = message || '';
                statusEl.classList.toggle('error', !!isError);
              }

              function busy(on) {
                el('run').disabled = on;
                el('download-json').disabled = on;
                el('download-html').disabled = on;
              }

              async function request(format) {
                if (tab === 'text' && !textArea.value.trim()) {
                  setStatus('Вставьте фрагмент лога', true);
                  textArea.focus();
                  return null;
                }
                if (tab === 'path' && !pathInput.value.trim()) {
                  setStatus('Укажите путь к файлу или каталогу', true);
                  pathInput.focus();
                  return null;
                }
                busy(true);
                setStatus('Анализирую…');
                const started = performance.now();
                try {
                  const response = await fetch('/api/analyze?' + params(format).toString(), {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
                    body: tab === 'text' ? textArea.value : ''
                  });
                  const body = await response.text();
                  if (!response.ok) {
                    setStatus(body || ('Ошибка ' + response.status), true);
                    return null;
                  }
                  setStatus('Готово за ' + Math.round(performance.now() - started) + ' мс');
                  return body;
                } catch (e) {
                  setStatus('Не удалось связаться с сервером: ' + e.message, true);
                  return null;
                } finally {
                  busy(false);
                }
              }

              async function analyse() {
                const html = await request('html');
                if (html === null) return;
                frame.srcdoc = html;
                frame.classList.remove('hidden');
                placeholder.classList.add('hidden');
              }

              async function download(format, filename, mime) {
                const body = await request(format);
                if (body === null) return;
                const url = URL.createObjectURL(new Blob([body], { type: mime + ';charset=utf-8' }));
                const link = document.createElement('a');
                link.href = url;
                link.download = filename;
                link.click();
                URL.revokeObjectURL(url);
              }

              el('run').addEventListener('click', analyse);
              el('download-json').addEventListener('click',
                () => download('json', 'log-report.json', 'application/json'));
              el('download-html').addEventListener('click',
                () => download('html', 'log-report.html', 'text/html'));
              el('clear').addEventListener('click', function () {
                textArea.value = '';
                frame.srcdoc = '';
                frame.classList.add('hidden');
                placeholder.classList.remove('hidden');
                setStatus('');
                textArea.focus();
              });

              textArea.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); analyse(); }
              });

              // Перетаскивание файла прямо в поле ввода
              ['dragenter', 'dragover'].forEach((type) => textArea.addEventListener(type, function (e) {
                e.preventDefault();
                textArea.classList.add('drag');
              }));
              ['dragleave', 'drop'].forEach((type) => textArea.addEventListener(type, function () {
                textArea.classList.remove('drag');
              }));
              textArea.addEventListener('drop', function (e) {
                e.preventDefault();
                const file = e.dataTransfer.files && e.dataTransfer.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = function () {
                  textArea.value = reader.result;
                  el('text-hint').textContent = 'Загружен файл ' + file.name
                    + ' (' + Math.round(file.size / 1024) + ' КБ). Ctrl+Enter — анализировать.';
                };
                reader.readAsText(file, 'UTF-8');
              });

              textArea.focus();
            })();
            </script>
            </body>
            </html>
            """;
}
