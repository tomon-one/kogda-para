// Главный экран: шапка, вкладки, своё расписание и чужие (TodayScreen.kt,
// TeacherScreen.kt).

import { h, icon, actionButton, actionLink, dialog, closeDialog, snackbar } from './dom.js';
import { dayCards } from './days.js';
import { othersList } from './lists.js';
import { formatDurationLong, formatFetchedAt, formatSince, plural, tallySince } from '../time.js';
import { sheetLink } from '../format.js';
import { STATUS_UNREACHABLE } from '../repo.js';

/** Разделы. Пересдачи и экзамены колледж публикует отдельными листами. */
var SOON = {
  retakes: ['Пересдачи', 'Будут списком по предметам и преподавателям: групп в таблице колледжа нет'],
  exams: ['Экзамены', 'Появятся к сессии — колледж выкладывает их в декабре'],
};

/** За сколько грузится таблица колледжа. Перемерено 8 сентября 2026 года. */
var SHEET_SECONDS = 8;

export function mainScreen(app) {
  var teacherMode = app.isTeacher();
  var own = app.chosen();
  var route = app.route;
  // Вкладка: у студента своё — «Студентам», у преподавателя — «Преподавателям».
  var tab = route.kind === 'teachers' ? 'teachers'
    : route.kind === 'groups' ? 'students'
    : teacherMode ? 'teachers' : 'students';

  var head = h('div', { class: 'head' }, topbar(app, own), tabs(app, tab, teacherMode));
  var content = h('main', { class: 'content' });
  var screen = h('div', { class: 'screen main' + (teacherMode && !route.kind ? ' with-bottom' : '') },
    head, secondTabs(), content);

  if (route.kind && route.id) {
    content.appendChild(chosenView(app, route.kind, route.id));
  } else if (route.kind === 'teachers') {
    if (teacherMode) {
      content.appendChild(h('div', { class: 'back-row' },
        h('button', { type: 'button', class: 'text-button', onclick: function () { app.go('', true); } },
          'Вернуться к своему расписанию')));
    }
    content.appendChild(othersList(app, 'teachers', teacherMode && own ? own.id : null));
  } else if (route.kind === 'groups') {
    content.appendChild(othersList(app, 'groups', null));
  } else {
    ownView(app, content);
    if (teacherMode) {
      // Своё расписание преподавателя — сразу, остальные — по кнопке.
      screen.appendChild(h('div', { class: 'bottom-bar' },
        h('button', { type: 'button', class: 'text-button', onclick: function () { app.go('teachers'); } },
          'Посмотреть других преподавателей')));
    }
  }
  return screen;
}

function topbar(app, own) {
  var s = app.state;
  var title = h('h1', { class: 'topbar-title name' }, own ? own.name : '');
  onLongPress(title, function () { showTally(app); });

  var broken = null;
  if (s.refreshFailed) broken = 'Не удалось обновить расписание';
  else if (app.gone()) broken = app.isTeacher() ? 'Вас нет в таблице' : 'Группы нет в таблице';
  else if (app.server().status === STATUS_UNREACHABLE) broken = 'Сервер расписания не отвечает';
  else if (app.serverBroken()) broken = 'Сервер не смог обновить расписание';

  var spinning = s.refreshing || s.spinningDown;
  var refreshIcon;
  var label;
  if (s.flash) {
    // Запрос прошёл, но сервер отдал прежнее: галочка обещала бы свежесть,
    // которой нет. Подробности — на плашке.
    refreshIcon = icon(broken ? 'close' : 'check');
    label = broken || 'Расписание обновлено';
    var since = Date.now() - s.flashAt;
    if (since < 320) {
      refreshIcon.classList.add('pop');
      refreshIcon.style.setProperty('--shift', -since + 'ms');
    }
  } else {
    refreshIcon = icon('refresh', spinning ? 'spin' : '');
    if (spinning) refreshIcon.style.setProperty('--spin-shift', -((Date.now() - s.spinStart) % 450) + 'ms');
    label = 'Обновить расписание';
  }
  var refresh = h('button', {
    type: 'button', class: 'icon-button', 'aria-label': label, title: label,
    disabled: spinning, onclick: function () { app.refresh(true, true); },
  }, refreshIcon);

  return h('header', { class: 'topbar' },
    h('div', { class: 'title-block' },
      title,
      h('div', { class: 'fetched' }, formatFetchedAt(app.fetchedAt()))),
    refresh,
    h('button', {
      type: 'button', class: 'icon-button', 'aria-label': 'Настройки', title: 'Настройки',
      onclick: function () { app.go('settings'); },
    }, icon('settings')));
}

function tabs(app, current, teacherMode) {
  var order = teacherMode ? ['teachers', 'students'] : ['students', 'teachers'];
  var names = { students: 'Студентам', teachers: 'Преподавателям' };
  return h('nav', { class: 'tabs', 'aria-label': 'Разделы' }, order.map(function (tab) {
    var on = tab === current;
    return h('button', {
      type: 'button', class: 'tab' + (on ? ' on' : ''), 'aria-current': on ? 'page' : null,
      onclick: function () {
        if (on) return;
        // Своя вкладка — своё расписание; чужая — список.
        var mine = teacherMode ? tab === 'teachers' : tab === 'students';
        app.go(mine ? '' : teacherMode ? 'groups' : 'teachers');
      },
    }, h('span', { class: 'tab-label' }, names[tab]));
  }));
}

function secondTabs() {
  return h('nav', { class: 'tabs soon', 'aria-label': 'Скоро' }, ['retakes', 'exams'].map(function (key) {
    return h('button', {
      type: 'button', class: 'tab muted',
      onclick: function () { snackbar(SOON[key][1]); },
    }, h('span', { class: 'tab-label' }, SOON[key][0]));
  }));
}

/** Плашки: группы нет в таблице, сервер в сбое. */
function plates(app) {
  var out = [];
  var own = app.chosen();
  var teacherMode = app.isTeacher();
  if (app.gone()) {
    out.push(h('div', { class: 'plate' },
      h('div', { class: 'plate-title' }, teacherMode ? 'Вас больше нет в таблице' : 'Группы больше нет в таблице'),
      h('div', { class: 'plate-text' }, (teacherMode
        ? 'Имя «' + own.name + '» в таблице записали иначе или убрали. '
        : 'Группу «' + own.name + '» переименовали, разделили или убрали. ') + 'На экране — последнее, что было.'),
      actionButton('Выбрать заново', function () { app.go(teacherMode ? 'pick/self' : 'pick'); })));
  }
  if (app.serverBroken()) {
    var server = app.server();
    var unreachable = server.status === STATUS_UNREACHABLE;
    var link = sheetLink(app.saved(), app.now().date, server.src_url);
    out.push(h('div', { class: 'plate' },
      h('div', { class: 'plate-title' }, unreachable ? 'Сервер расписания не отвечает' : 'Сбой на сервере'),
      h('div', { class: 'plate-text' },
        (unreachable ? 'Браузер не достучался до сервера. ' : 'Не удалось прочитать таблицу. ') +
        'Пары могли поменяться.'),
      server.since ? h('div', { class: 'plate-since' },
        'Сбой с ' + formatSince(server.since) + '. На экране — таблица, какой она была до сбоя.') : null,
      link ? actionLink('Открыть таблицу колледжа', link) : null));
  }
  return out.length ? h('div', { class: 'plates' }, out) : null;
}

/**
 * Объяснение вместо пустого экрана; null — объяснять нечего. Пустой раздел
 * без единого слова читается как поломка.
 */
function explainMissing(app, schedule, loading, fallback) {
  if (!schedule && loading) {
    return explanation('Расписание загружается', 'Обычно это несколько секунд.', null, true);
  }
  if (!schedule) {
    return explanation('Расписание ещё не загружено',
      'Проверьте интернет и нажмите ⟳ вверху. Не помогает — напишите автору в Telegram: @toomonn.');
  }
  if (!(schedule.days || []).length) {
    return explanation('На эти дни расписания нет',
      'Колледж их ещё не выложил — или расписание застряло на сервере. Проверить можно в таблице колледжа.',
      sheetLink(schedule, app.now().date, fallback));
  }
  return null;
}

function explanation(title, text, link, busy) {
  return h('div', { class: 'explanation' },
    busy ? h('div', { class: 'spinner' }) : null,
    h('h2', null, title),
    h('p', null, text),
    link ? actionLink('Открыть таблицу колледжа', link) : null);
}

function ownView(app, content) {
  var schedule = app.saved();
  var platesEl = plates(app);
  var missing = explainMissing(app, schedule, app.state.refreshing, app.server().src_url);
  if (missing) {
    if (platesEl) content.appendChild(platesEl);
    content.appendChild(missing);
    return;
  }
  content.appendChild(dayCards(schedule, app.now(), platesEl, app.scrollTarget()));
}

function chosenView(app, kind, id) {
  var other = app.state.other;
  var ready = other && other.kind === kind && other.id === id;
  var list = kind === 'teachers' ? app.state.teachers : app.state.groups;
  var known = (list || []).filter(function (x) { return x.id === id; })[0];
  var name = known ? known.name : ready && other.schedule ? other.schedule.gn : '';
  var box = h('div', { class: 'chosen' },
    h('div', { class: 'chosen-head' },
      h('h2', { class: 'chosen-name' }, name),
      h('button', { type: 'button', class: 'text-button', onclick: function () { app.go(kind, true); } }, 'К списку')));
  if (!ready || other.loading) {
    box.appendChild(h('div', { class: 'centered' }, h('div', { class: 'spinner' })));
  } else if (!other.schedule) {
    box.appendChild(h('p', { class: 'nothing' }, 'Расписание не загрузилось. Проверьте интернет и нажмите ⟳ вверху.'));
  } else {
    var missing = explainMissing(app, other.schedule, false, null);
    box.appendChild(missing || dayCards(other.schedule, app.now(), null, null));
  }
  return box;
}

// ——— счёт ответов ———

/**
 * Счёт: сколько раз таблицу открывать не пришлось. Прячется под долгим
 * нажатием на название: ничто на него не указывает, и не должно.
 */
function showTally(app) {
  var tally = app.tally();
  var total = tally.opens;
  var seconds = total * SHEET_SECONDS;
  var spent = seconds < 60 ? plural(seconds, 'секунду', 'секунды', 'секунд')
    : formatDurationLong(Math.floor(seconds / 60));
  dialog('Таблицу вы не открывали', [
    h('p', { class: 'tally-number' }, plural(total, 'раз', 'раза', 'раз')),
    h('p', null, 'Столько раз вместо неё ответил сайт.'),
    h('p', null, 'Она грузится ' + SHEET_SECONDS + ' секунд. Считайте, что ' + spent +
      ' вы потратили на что-то другое.'),
    tally.since ? h('p', { class: 'muted' }, 'Счёт идёт ' + tallySince(tally.since, app.now().date) + '.') : null,
  ], [{ label: 'Ладно', onClick: closeDialog }]);
}

/** Долгое нажатие — пальцем или мышью; правый щелчок тоже. */
function onLongPress(el, fire) {
  var timer = null;
  var firedAt = 0;
  function once() {
    cancel();
    // Долгое касание на Android даёт ещё и contextmenu — второй раз не открывать.
    if (Date.now() - firedAt < 1000) return;
    firedAt = Date.now();
    fire();
  }
  function start() {
    cancel();
    timer = setTimeout(once, 550);
  }
  function cancel() {
    if (timer) clearTimeout(timer);
    timer = null;
  }
  el.addEventListener('touchstart', start, { passive: true });
  el.addEventListener('touchend', cancel);
  el.addEventListener('touchmove', cancel, { passive: true });
  el.addEventListener('touchcancel', cancel);
  el.addEventListener('mousedown', function (e) { if (e.button === 0) start(); });
  el.addEventListener('mouseup', cancel);
  el.addEventListener('mouseleave', cancel);
  el.addEventListener('contextmenu', function (e) {
    e.preventDefault();
    once();
  });
}
