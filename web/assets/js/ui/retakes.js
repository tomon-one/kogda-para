// Вкладка «Пересдачи»: лист пересдач колледжа списком с поиском по фамилии.
// Лист ведётся по преподавателям, без групп и предметов: даты, время и
// кабинет показываются как записаны в таблице.

import { h, clear, actionButton } from './dom.js';
import { matchesQuery } from '../search.js';
import { plural } from '../time.js';

var KEY = 'retakes';

function spinner() {
  return h('div', { class: 'centered' }, h('div', { class: 'spinner', role: 'progressbar', 'aria-label': 'Загрузка' }));
}

function line(label, text) {
  return text ? h('div', { class: 'retake-line' }, h('span', { class: 'muted' }, label + ' '), text) : null;
}

function retakeCard(row) {
  return h('div', { class: 'retake' },
    h('div', { class: 'retake-name' }, row.teacher),
    line('Дата:', row.dates),
    line('Время:', row.time),
    line('Кабинет:', row.room));
}

function redirectCard(name, to) {
  return h('div', { class: 'retake' },
    h('div', { class: 'retake-name' }, name),
    h('div', { class: 'retake-line' }, h('span', { class: 'muted' }, 'Долги принимает '), to));
}

export function retakesView(app) {
  var wrap = h('div', { class: 'retakes' });
  var results = h('div', { class: 'results' });
  var data = app.state.retakes;

  if (data === null || data === undefined) {
    app.loadRetakes(false);
    wrap.appendChild(spinner());
    return wrap;
  }
  if (data === 'failed') {
    wrap.appendChild(h('div', { class: 'load-failed' },
      h('p', null, 'Пересдачи не загрузились. Проверьте интернет.'),
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

  function fill() {
    clear(results);
    var query = (app.state.queries[KEY] || '').trim();
    var rows = data.rows;
    var moved = [];
    if (query) {
      moved = redirects.filter(function (r) { return matchesQuery(r.name, query); });
      rows = rows.filter(function (r) {
        return matchesQuery(r.teacher, query) || moved.some(function (m) { return matchesQuery(r.teacher, m.to); });
      });
    }
    if (!rows.length && !moved.length) {
      results.appendChild(h('p', { class: 'nothing' }, 'Ничего не нашлось. Вопросы о задолженностях — куратору или в учебный офис, каб. 267.'));
      return;
    }
    var cards = moved.map(function (m) { return redirectCard(m.name, m.to); })
      .concat(rows.map(retakeCard));
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
    type: 'search', class: 'search', placeholder: 'Поиск по фамилии', 'aria-label': 'Поиск по фамилии',
    autocomplete: 'off', autocorrect: 'off', autocapitalize: 'off', spellcheck: 'false', 'data-query': KEY,
  });
  input.value = app.state.queries[KEY] || '';
  input.__onChange = fill;
  input.addEventListener('input', function () {
    app.state.queries[KEY] = input.value;
    input.__onChange();
  });

  wrap.appendChild(h('h2', { class: 'retakes-title' }, data.title || 'Пересдачи'));
  wrap.appendChild(h('div', { class: 'search-wrap' }, input));
  wrap.appendChild(results);
  fill();
  return wrap;
}
