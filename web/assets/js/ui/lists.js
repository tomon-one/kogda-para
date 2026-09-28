// Списки групп и преподавателей: выбор себя (GroupPickerScreen.kt,
// SelfPickerScreen.kt) и чужие расписания во вкладке (TeacherScreen.kt).

import { h, clear, icon, actionButton, externalLink } from './dom.js';
import { lettered, matchesQuery } from '../search.js';
import { plural } from '../time.js';

var TELEGRAM = 'https://t.me/toomonn';

function searchField(app, key, label, onChange) {
  var input = h('input', {
    type: 'search', class: 'search', placeholder: label, 'aria-label': label,
    autocomplete: 'off', autocorrect: 'off', autocapitalize: 'off', spellcheck: 'false',
    'data-query': key,
  });
  input.value = app.state.queries[key] || '';
  // Через свойство поля: при перестройке экрана поле переезжает в новый, а
  // список — уже другой узел.
  input.__onChange = onChange;
  input.addEventListener('input', function () {
    app.state.queries[key] = input.value;
    input.__onChange();
  });
  return input;
}

function listEnd(text) {
  return h('p', { class: 'list-end' }, text);
}

function spinner() {
  return h('div', { class: 'centered' }, h('div', { class: 'spinner', role: 'progressbar', 'aria-label': 'Загрузка' }));
}

/** Список не загрузился — куда написать, повтор и сведения для отчёта. */
function loadFailed(app, what) {
  return h('div', { class: 'load-failed' },
    h('p', null, what + (app.state.listsBusy ? ' не загрузился: сервер занят. Попробуйте через минуту или напишите автору в Telegram:'
      : ' не загрузился. Проверьте интернет или напишите автору в Telegram:')),
    externalLink('@toomonn', TELEGRAM, 'link strong'),
    h('div', null, actionButton('Повторить', function () { app.loadLists(true); })),
    h('button', { type: 'button', class: 'text-link', 'data-key': 'report', onclick: app.showReport }, 'Сведения для отчёта'));
}

/** Строка смены роли — первой строкой списка, такой же заметной, как остальные. */
function roleRow(text, onClick) {
  return h('button', { type: 'button', class: 'role-row', onclick: onClick },
    h('span', null, text), h('span', { class: 'chevron', 'aria-hidden': 'true' }, '›'));
}

function letteredList(rows, onPick) {
  return lettered(rows, function (r) { return r.name; }).map(function (section) {
    return h('div', { class: 'letter-section' },
      h('h3', { class: 'letter' }, section.letter),
      h('div', { class: 'list-card' }, section.rows.map(function (row) {
        return h('button', { type: 'button', class: 'list-row', 'data-key': 'pick:' + row.id, onclick: function () { onPick(row); } }, row.name);
      })));
  });
}

/**
 * Экран выбора: группы (`mode` = 'group' или 'second') или себя ('self').
 */
export function pickerScreen(app, mode) {
  var self = mode === 'self';
  var title = self ? 'Найдите себя в списке'
    : mode === 'second' ? 'Выберите соседнюю подгруппу' : 'Выберите группу';
  var key = 'pick-' + mode;
  var results = h('div', { class: 'results' });

  function fill() {
    clear(results);
    var list = self ? app.state.teachers : app.state.groups;
    if (list == null) {
      results.appendChild(spinner());
      return;
    }
    if (!list.length) {
      results.appendChild(loadFailed(app, self ? 'Список преподавателей' : 'Список групп'));
      return;
    }
    var query = app.state.queries[key] || '';
    var found = query.trim() ? list.filter(function (r) { return matchesQuery(r.name, query); }) : list;
    if (!found.length) results.appendChild(h('p', { class: 'nothing' }, 'Ничего не нашлось'));
    // Приложением пользуются и преподаватели: им нужна не группа, а своё расписание.
    if (mode === 'group') results.appendChild(roleRow('Я преподаватель', function () { app.go('pick/self', true); }));
    if (self) results.appendChild(roleRow('Я студент', function () { app.go('pick', true); }));
    letteredList(found, function (row) { app.pick(mode, row); }).forEach(function (el) { results.appendChild(el); });
    if (!query.trim()) {
      results.appendChild(listEnd('Всё. ' + (self
        ? plural(list.length, 'преподаватель', 'преподавателя', 'преподавателей')
        : plural(list.length, 'группа', 'группы', 'групп')) + '.'));
    }
  }
  fill();

  return h('div', { class: 'screen picker' },
    h('header', { class: 'topbar' },
      app.canGoBack() ? h('button', { type: 'button', class: 'icon-button', 'aria-label': 'Назад', onclick: app.back }, icon('back')) : null,
      h('h1', { class: 'topbar-title' }, title)),
    h('div', { class: 'search-wrap' }, searchField(app, key, self ? 'Поиск по фамилии' : 'Поиск по названию', fill)),
    results);
}

/**
 * Чужие расписания во вкладке: преподаватели (`kind` = 'teachers') или группы
 * ('groups'). Закреплённые — отдельно сверху, сам преподаватель — над всеми.
 */
export function othersList(app, kind, selfId) {
  var teachers = kind === 'teachers';
  var key = 'others-' + kind;
  var results = h('div', { class: 'results' });
  var popped = null;

  function fill() {
    clear(results);
    var list = teachers ? app.state.teachers : app.state.groups;
    if (list == null) {
      results.appendChild(spinner());
      return;
    }
    if (!list.length) {
      results.appendChild(h('p', { class: 'nothing' },
        (teachers ? 'Список преподавателей' : 'Список групп') + (app.state.listsBusy
          ? ' не загрузился: сервер занят. Через минуту нажмите ⟳ вверху.'
          : ' не загрузился. Проверьте интернет и нажмите ⟳ вверху.')));
      return;
    }
    var query = app.state.queries[key] || '';
    var found = query.trim() ? list.filter(function (r) { return matchesQuery(r.name, query); }) : list;
    var pins = app.pinned(kind);
    var self = selfId ? found.filter(function (r) { return r.id === selfId; })[0] : null;
    var favourites = found.filter(function (r) { return pins.indexOf(r.id) >= 0 && r.id !== selfId; });
    var others = found.filter(function (r) { return pins.indexOf(r.id) < 0 && r.id !== selfId; });

    function row(item, isSelf) {
      var on = pins.indexOf(item.id) >= 0;
      return h('div', { class: 'pin-row' },
        h('button', { type: 'button', class: 'pin-open', 'data-key': 'open:' + item.id, onclick: function () { app.go(kind + '/' + encodeURIComponent(item.id)); } },
          h('span', null, item.name),
          isSelf ? h('span', { class: 'self-mark' }, 'это вы') : null),
        h('button', {
          type: 'button', class: 'pin-star' + (on ? ' on' : '') + (item.id === popped ? ' pop' : ''),
          'aria-pressed': on ? 'true' : 'false', 'aria-label': 'Закрепить наверху списка: ' + item.name,
          'data-key': 'star:' + item.id,
          onclick: function () {
            app.togglePin(kind, item.id);
            popped = item.id;
            fill();
            popped = null;
            // Список перестроен — фокус на ту же звезду, а не на body.
            // Без прокрутки: снятая звезда переезжает вниз списка, и страница
            // уезжала за ней (аудит сайта, прогон 3).
            var star = results.querySelector('[data-key="star:' + item.id + '"]');
            if (star) {
              try { star.focus({ preventScroll: true }); } catch (e) { star.focus(); }
            }
          },
        }, on ? '★' : '☆'));
    }

    if (self) {
      results.appendChild(h('h3', { class: 'section-title' }, 'Ваше расписание'));
      results.appendChild(row(self, true));
    }
    if (favourites.length) {
      results.appendChild(h('h3', { class: 'section-title' }, 'Закреплённые'));
      favourites.forEach(function (r) { results.appendChild(row(r, false)); });
    }
    if (!found.length) results.appendChild(h('p', { class: 'nothing' }, 'Ничего не нашлось'));
    if (others.length && (self || favourites.length)) {
      results.appendChild(h('h3', { class: 'section-title' }, teachers ? 'Другие преподаватели' : 'Другие группы'));
    }
    others.forEach(function (r) { results.appendChild(row(r, false)); });
    if (!query.trim()) {
      results.appendChild(listEnd('Всё. ' + (teachers
        ? plural(list.length, 'преподаватель', 'преподавателя', 'преподавателей')
        : plural(list.length, 'группа', 'группы', 'групп')) + '.'));
    }
  }
  fill();

  return h('div', { class: 'others' },
    h('div', { class: 'search-wrap' },
      searchField(app, key, teachers ? 'Поиск по фамилии' : 'Поиск по названию группы', fill)),
    results);
}
