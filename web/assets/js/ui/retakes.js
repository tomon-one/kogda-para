// Вкладка «Пересдачи»: лист пересдач колледжа списком с поиском. Лист ведётся
// по преподавателям, без групп и предметов; даты служба разбирает по дням, а что
// преподаватель ведёт сейчас, берёт из расписания (docs/api.md, /v1/retakes).

import { h, clear, actionButton } from './dom.js';
import { matchesQuery } from '../search.js';
import { dayRange, plural } from '../time.js';

var KEY = 'retakes';

/**
 * Пересдачи идут: лист есть и в нём есть дни не раньше сегодняшнего (или даты,
 * которые не разобрались, — про них не знаем, что прошли).
 */
export function retakesActive(data, today) {
  if (!data || data === 'failed' || !data.rows || !data.rows.length) return false;
  return data.rows.some(function (row) {
    if (!row.slots || !row.slots.length) return true;
    return row.slots.some(function (s) { return s.to >= today; });
  });
}

function spinner() {
  return h('div', { class: 'centered' }, h('div', { class: 'spinner', role: 'progressbar', 'aria-label': 'Загрузка' }));
}

function line(label, text, cls) {
  return text ? h('div', { class: 'retake-line' + (cls ? ' ' + cls : '') }, h('span', { class: 'muted' }, label + ' '), text) : null;
}

function slotLines(row, today) {
  if (!row.slots || !row.slots.length) {
    // Даты не разобрались — как в таблице.
    return [line('Дата:', row.dates), line('Время:', row.time)];
  }
  return row.slots.map(function (s) {
    var past = s.to < today;
    return h('div', { class: 'retake-slot' + (past ? ' past' : '') },
      h('span', { class: 'retake-day' }, dayRange(s.from, s.to)),
      s.time ? h('span', { class: 'retake-time' }, s.time) : null,
      past ? h('span', { class: 'retake-past' }, 'прошло') : null);
  });
}

/**
 * Имя полностью — из списка преподавателей по id: в листе пересдач колледж
 * пишет «Фамилия И.О.», и инициалы читаются хуже. Нет в списке — как в листе.
 */
function fullName(app, row) {
  var list = Array.isArray(app.state.teachers) ? app.state.teachers : [];
  for (var i = 0; i < list.length; i++) if (row.teacher_id && list[i].id === row.teacher_id) return list[i].name;
  return row.teacher;
}

function retakeCard(app, row, today) {
  var full = fullName(app, row);
  var name = row.teacher_id
    ? h('button', {
      type: 'button', class: 'retake-name link', title: 'Расписание преподавателя',
      onclick: function () { app.state.viaRetakes = row.teacher_id; app.go('teachers/' + row.teacher_id); },
    }, full)
    : h('div', { class: 'retake-name' }, full);
  return h('div', { class: 'retake' },
    name,
    slotLines(row, today),
    line('Кабинет:', row.room),
    row.subjects && row.subjects.length ? line('Ведёт сейчас:', row.subjects.slice(0, 3).join('; '), 'subjects') : null);
}

function redirectCard(name, to) {
  return h('div', { class: 'retake' },
    h('div', { class: 'retake-name' }, name),
    h('div', { class: 'retake-line' }, h('span', { class: 'muted' }, 'Долги принимает '), to));
}

function memo() {
  return h('div', { class: 'retake-memo' },
    h('p', null, 'График составлен по преподавателям: найдите того, у кого долг, и приходите строго в указанное время.'),
    h('p', null, 'По прошедшим датам колледж договаривался с каждым отдельно.'),
    h('p', null, 'Вопросы — куратору, декану или руководителю направления, в учебный офис (каб. 267).'));
}

export function retakesView(app) {
  var wrap = h('div', { class: 'retakes' });
  var results = h('div', { class: 'results' });
  var data = app.state.retakes;
  var today = app.now().date;

  if (data === null || data === undefined) {
    app.loadRetakes(false);
    wrap.appendChild(spinner());
    return wrap;
  }
  if (data === 'failed') {
    wrap.appendChild(h('div', { class: 'load-failed' },
      h('p', null, 'Пересдачи не загрузились. ' + failureText(app.state.failedBy.retakes)),
      h('div', null, actionButton('Повторить', function () { app.loadRetakes(true); }))));
    return wrap;
  }
  app.loadRetakes(false);
  if (!data.rows || !data.rows.length) {
    wrap.appendChild(h('p', { class: 'nothing' }, 'Графика пересдач в таблице колледжа сейчас нет.'));
    return wrap;
  }

  // Ищут того, у кого долг: если его долги принимает другой, найдётся и строка
  // «долги принимает», и сам принимающий.
  var redirects = [];
  (data.redirects || []).forEach(function (r) {
    r.teachers.forEach(function (name) { redirects.push({ name: name, to: r.to }); });
  });

  function matches(row, query) {
    return matchesQuery(row.teacher, query) || matchesQuery(fullName(app, row), query) ||
      (row.subjects || []).some(function (s) { return matchesQuery(s, query); });
  }

  function fill() {
    clear(results);
    var query = (app.state.queries[KEY] || '').trim();
    var rows = data.rows;
    var moved = [];
    if (query) {
      moved = redirects.filter(function (r) { return matchesQuery(r.name, query); });
      rows = rows.filter(function (r) {
        return matches(r, query) || moved.some(function (m) { return matchesQuery(r.teacher, m.to); });
      });
    }
    if (!rows.length && !moved.length) {
      results.appendChild(h('p', { class: 'nothing' }, 'Ничего не нашлось. Вопросы — куратору, декану или руководителю направления, в учебный офис (каб. 267).'));
      return;
    }
    var cards = moved.map(function (m) { return redirectCard(m.name, m.to); })
      .concat(rows.map(function (r) { return retakeCard(app, r, today); }));
    results.appendChild(h('div', { class: 'list-card' }, cards));
    if (!query) {
      results.appendChild(h('p', { class: 'list-end' }, 'Всё. ' +
        plural(data.rows.length, 'преподаватель', 'преподавателя', 'преподавателей') + '.'));
      if (redirects.length) {
        results.appendChild(h('h3', { class: 'section-title' }, 'Долги принимает другой преподаватель'));
        results.appendChild(h('div', { class: 'list-card' },
          redirects.map(function (m) { return redirectCard(m.name, m.to); })));
      }
    }
  }

  var input = h('input', {
    type: 'search', class: 'search', placeholder: 'Фамилия или предмет', 'aria-label': 'Поиск по фамилии или предмету',
    autocomplete: 'off', autocorrect: 'off', autocapitalize: 'off', spellcheck: 'false', 'data-query': KEY,
  });
  input.value = app.state.queries[KEY] || '';
  input.__onChange = fill;
  input.addEventListener('input', function () {
    app.state.queries[KEY] = input.value;
    input.__onChange();
  });

  wrap.appendChild(h('h2', { class: 'retakes-title' }, data.title || 'Пересдачи'));
  wrap.appendChild(memo());
  wrap.appendChild(h('div', { class: 'search-wrap' }, input));
  wrap.appendChild(results);
  fill();
  return wrap;
}

/** Почему раздел не загрузился (data.js, failedBy) — те же слова, что в приложении. */
export function failureText(why) {
  return why === 'server' ? 'Сервер не смог их отдать. Повторите позже.' : 'Нет связи с сервером. Проверьте интернет.';
}
