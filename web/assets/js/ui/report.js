// Сведения для отчёта (Diagnostics.kt, Report.kt). Никуда сами не уходят:
// человек смотрит, копирует и отправляет сам.

import { h, dialog, closeDialog, copyText, snackbar, standalone } from './dom.js';
import { VERSION, build } from '../version.js';
import { CHANNEL, persistent } from '../store.js';
import { browserZone, formatShort, parseIso } from '../time.js';

export function reportText(app) {
  var lines = [];
  lines.push('Когда пара? ' + VERSION + (CHANNEL === 'tested' ? ' tested' : '') + ' (' + build() + ')');
  lines.push('Браузер: ' + navigator.userAgent);
  lines.push('Экран: ' + window.innerWidth + '×' + window.innerHeight +
    (standalone() ? ', открыт значком' : ', вкладка браузера'));
  var own = app.chosen();
  if (app.isTeacher()) {
    lines.push('Преподаватель: ' + (own ? own.name + ' [' + own.id + ']' : 'не выбран [—]'));
  } else {
    lines.push('Группа: ' + (own ? own.name + ' [' + own.id + ']' : 'не выбрана [—]'));
    var extras = app.extras();
    if (extras.length) {
      lines.push('Ещё группы: ' + extras.map(function (g) {
        return g.name + ' [' + g.id + ']' + (g.gone ? ', нет в таблице' : '');
      }).join('; '));
    }
  }
  var schedule = app.saved();
  if (!schedule) {
    lines.push('Расписание: не загружено');
  } else {
    var fetched = app.fetchedAt();
    var gen = parseIso(schedule.gen);
    lines.push('Расписание: браузер проверял ' + (fetched ? formatShort(fetched) : 'никогда') +
      ', снимок сервера от ' + (isNaN(gen) ? '—' : formatShort(gen)));
    if (schedule.src) lines.push('Лист: ' + schedule.src);
    if (schedule.cov && schedule.cov.length === 2) lines.push('Дни группы: ' + schedule.cov[0] + ' — ' + schedule.cov[1]);
    lines.push('Дней в браузере: ' + (schedule.days || []).length);
  }
  lines.push('Сервер: ' + (app.server().status || 'ok') + (app.gone() ? ', выбранного нет в таблице' : ''));
  if (!persistent()) lines.push('Хранилище браузера: запрещено, выбор забудется');
  lines.push('Часы: ' + formatShort(Date.now()) + ', ' + browserZone());
  return lines.join('\n');
}

export function showReport(app) {
  var text = reportText(app);
  dialog('Сведения для отчёта', [
    h('p', null, 'Пришлите это автору в Telegram: @toomonn — вместе с жалобой. ' +
      'Всё, что уйдёт, — ниже: посмотрите перед отправкой.'),
    h('pre', { class: 'report' }, text),
  ], [
    {
      label: 'Скопировать',
      onClick: function () {
        copyText(text).then(function (ok) { snackbar(ok ? 'Скопировано' : 'Скопировать не вышло — выделите текст вручную'); });
      },
    },
    { label: 'Закрыть', onClick: closeDialog },
  ]);
}
