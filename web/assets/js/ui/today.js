// Главный экран: шапка, вкладки, своё расписание и чужие (TodayScreen.kt,
// TeacherScreen.kt).

import { h, icon, actionButton, actionLink, dialog, closeDialog, spinner } from './dom.js';
import { dayCards } from './days.js';
import { othersList } from './lists.js';
import { retakesActive, retakesView } from './retakes.js';
import { roomsView } from './rooms.js';
import { weekStrip } from './week.js';
import { formatDurationLong, formatFetchedAt, formatSince, plural, tallySince } from '../time.js';
import { sheetLink } from '../format.js';
import { STATUS_UNREACHABLE, STATUS_EDITING, openingDate } from '../repo.js';
import { SECTIONS, isSection, tabOrder } from '../state.js';

var CHANNEL_URL = 'https://t.me/kogda_para_news';

/** За сколько грузится таблица колледжа. Перемерено 8 сентября 2026 года. */
var SHEET_SECONDS = 8;

export function mainScreen(app) {
  var teacherMode = app.isTeacher();
  var own = app.chosen();
  var route = app.route;
  // Вкладка: у студента своё — «Студентам», у преподавателя — «Преподавателям».
  var tab = isSection(route.kind) ? route.kind
    : route.kind === 'teachers' ? 'teachers'
    : route.kind === 'groups' ? 'students'
    : teacherMode ? 'teachers' : 'students';
  // С ролью — своя память: своя вкладка преподавателя — не то, что смотрел студент.
  // Открытый из «Пересдач» — не память вкладки: её нажатие ведёт к тому, что
  // открывали на ней самой.
  var viaRetakes = route.kind === 'teachers' && route.id && app.state.viaRetakes === route.id;
  if (!viaRetakes) app.state.viaRetakes = null;
  if (!viaRetakes) app.state.opened[teacherMode + tab] = route.kind ? route.kind + (route.id ? '/' + route.id : '') : '';

  // Пересдачи идут — их ряд не уезжает при листании и горит: о них должны
  // узнать и те, кто вкладку не открывал.
  app.loadRetakes(false);
  var live = retakesActive(app.state.retakes, app.now().date);
  // Полоска дней — у своего расписания, когда оно есть.
  // Дней нет (воскресенье до выкладки) — без полоски, как в приложении: нажимать некуда.
  var ownDays = (app.shown() || app.saved() || {}).days || [];
  var strip = !route.kind && ownDays.length ? weekStrip(app.scrollTarget(), app.now().date) : null;
  var head = h('div', { class: 'head' }, topbar(app, own), channelPlate(app), tabs(app, tab, teacherMode),
    live ? secondTabs(app, tab, true) : null);
  var content = h('main', { class: 'content' });
  // Страница открывается у сегодняшнего дня: над полоской ряд был бы выше экрана,
  // поэтому он прилипает и уходит при листании вниз, как в приложении. На своих
  // разделах ряд стоит: уходить некуда, он и есть переключатель.
  var row = live ? null : secondTabs(app, tab, false);
  // Открытый преподаватель или группа: ряд разделов — над именем, в шапке, как
  // в приложении и как при идущих пересдачах.
  if (row && route.kind && route.id) {
    head.appendChild(row);
    row = null;
  }
  var rowStays = isSection(tab);
  if (row && (strip || rowStays)) row.classList.add('float');
  if (row && rowStays) row.classList.add('stays');
  // Полоска — под вторым рядом вкладок, как в приложении, и прилипает под шапкой.
  var screen = h('div', { class: 'screen main' + (teacherMode && !route.kind ? ' with-bottom' : '') },
    head, row, strip, content);

  // Сбой и пропажа — на любой вкладке, как в приложении: иначе чужое
  // расписание во время сбоя выглядело бы свежим. На расписании — у сегодняшнего
  // дня, куда страница прокручивается.
  if (route.kind && !route.id) {
    var top = plates(app);
    if (top) content.appendChild(top);
  }
  if (route.kind && route.id) {
    // Имя — часть шапки: прилипает с ней и размывается с ней одним слоем.
    var box = chosenView(app, route.kind, route.id);
    head.appendChild(box.querySelector('.chosen-head'));
    if (box.querySelector('[data-day]')) screen.insertBefore(weekStrip(app.now().date, app.now().date), content);
    content.appendChild(box);
  } else if (route.kind === 'teachers') {
    if (teacherMode) {
      content.appendChild(h('div', { class: 'back-row' },
        h('button', { type: 'button', class: 'text-button', onclick: app.toOwn },
          'Вернуться к своему расписанию')));
    }
    content.appendChild(othersList(app, 'teachers', teacherMode && own ? own.id : null));
  } else if (route.kind === 'groups') {
    content.appendChild(othersList(app, 'groups', null));
  } else if (route.kind === 'retakes') {
    content.appendChild(retakesView(app));
  } else if (route.kind === 'rooms') {
    content.appendChild(roomsView(app));
  } else if (route.kind === 'exams') {
    // Листа экзаменов в таблице ещё нет: говорим, когда будет, а не показываем пустоту.
    content.appendChild(explanation('Экзаменов нет', 'Расписание экзаменов колледж выкладывает к сессии, в декабре.'));
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

/**
 * Почему у ⟳ крестик, а не галочка; null — всё хорошо. Та же строка уходит
 * чтецу экрана, чтобы он не сказал «обновлено» при крестике.
 */
export function refreshLabel(app) {
  if (app.state.refreshFailed) return 'Не удалось обновить расписание';
  if (app.state.partial) {
    return app.state.partial.length === 1 ? 'Не обновилась группа ' + app.state.partial[0]
      : 'Не обновились группы ' + app.state.partial.join(', ');
  }
  if (app.gone()) return app.isTeacher() ? 'Вас нет в таблице' : 'Группы нет в таблице';
  if (app.server().status === STATUS_UNREACHABLE) return 'Сервер расписания не отвечает';
  if (app.server().status === STATUS_EDITING) return 'Колледж правит таблицу';
  if (app.serverBroken()) return 'Сервер не смог обновить расписание';
  return null;
}

function topbar(app, own) {
  var title = h('h1', { class: 'topbar-title name' }, own ? own.name : '');
  onLongPress(title, function () { showTally(app); });
  var refresh = refreshButton(app);


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

/**
 * ⟳ в шапке. Отдельно от шапки: докрутка, галочка и её угасание меняют только
 * её (render.js, redrawRefresh) — перестройка всего экрана в эти полторы секунды
 * съедала нажатия, пришедшиеся на неё.
 */
export function refreshButton(app) {
  var s = app.state;
  var broken = refreshLabel(app);
  var spinning = s.spinning || s.spinningDown;
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
  // Пока крутится — не disabled, а aria-disabled и пропуск нажатий:
  // выключенная кнопка теряет фокус клавиатуры.
  var refresh = h('button', {
    type: 'button', class: 'icon-button', 'aria-label': label, title: label, 'data-key': 'refresh',
    'aria-disabled': spinning ? 'true' : null,
    onclick: function () { if (!spinning) app.refresh(true, true); },
  }, refreshIcon);
  return refresh;
}

var TAB_NAMES = {
  students: 'Студентам', teachers: 'Преподавателям', retakes: 'Пересдачи', rooms: 'Аудитории', exams: 'Экзамены',
};

/** Кнопка вкладки; [extra] — свои атрибуты поверх (подсветка идущих пересдач). */
function tabButton(tab, on, onclick, extra) {
  return h('button', Object.assign({
    type: 'button', class: 'tab' + (on ? ' on' : ''), 'aria-current': on ? 'page' : null, 'data-key': 'tab:' + tab,
    onclick: function () { if (!on) onclick(); },
  }, extra || {}), h('span', { class: 'tab-label' }, TAB_NAMES[tab]));
}

function tabs(app, current, teacherMode) {
  return h('nav', { class: 'tabs', 'aria-label': 'Разделы' }, tabOrder(teacherMode).map(function (tab) {
    return tabButton(tab, tab === current, function () {
      // Вкладка — к тому, что на ней было открыто (TodayScreen.kt: вкладки
      // помнят своё); в первый раз своя — своё расписание, чужая — список.
      var mine = teacherMode ? tab === 'teachers' : tab === 'students';
      var opened = app.state.opened[teacherMode + tab];
      var path = opened != null ? opened : mine ? '' : teacherMode ? 'groups' : 'teachers';
      // К открытому из списка — через список, как шли в первый раз: «К списку»
      // и «назад» ведут к нему, а не к своему расписанию.
      if (path.indexOf('/') > 0) {
        app.state.restoreTop = app.state.openedTop[path] || null;
        app.go(path.split('/')[0]);
      }
      app.go(path);
    });
  }));
}

function secondTabs(app, current, live) {
  return h('nav', { class: 'tabs soon', 'aria-label': 'Другие разделы' }, SECTIONS.map(function (tab) {
    // Пересдачи идут — вкладка горит и говорит об этом чтецу.
    var extra = tab === 'retakes' && live
      ? { class: 'tab' + (current === tab ? ' on' : '') + ' live', title: 'Пересдачи идут', 'aria-label': 'Пересдачи идут' } : null;
    return tabButton(tab, tab === current, function () { app.go(tab); }, extra);
  }));
}

/**
 * Один раз, когда расписание уже выбрано: у сайта есть канал новостей. Стоит в
 * шапке и прилипает с ней, как в приложении, а не среди дней.
 * И кнопка, и крестик убирают плашку насовсем.
 */
function channelPlate(app) {
  if (!app.chosen() || app.channelSeen()) return null;
  var channel = actionLink('Открыть канал', CHANNEL_URL);
  channel.addEventListener('click', function () { app.setChannelSeen(); });
  return h('div', { class: 'plate' },
    h('div', { class: 'plate-head' },
      h('div', { class: 'plate-title' }, 'Канал новостей'),
      h('button', { type: 'button', class: 'icon-button dialog-close', 'aria-label': 'Закрыть',
        onclick: function () { app.setChannelSeen(); } }, icon('close'))),
    h('div', { class: 'plate-text' }, 'У «Когда пара?» есть канал в Telegram: обновления, сбои и их починка.'),
    channel);
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
        : 'Группу «' + own.name + '» переименовали, разделили или убрали. ') +
        (app.saved() ? 'На экране — последнее, что было.' : 'Выберите заново.')),
      actionButton('Выбрать заново', function () { app.go(teacherMode ? 'pick/self' : 'pick'); })));
  }
  // Другая группа пропала из таблицы — сказать об этом, а не убирать её пары
  // молча (приложение тут присылает уведомление).
  var goneExtras = teacherMode ? [] : app.extras().filter(function (g) { return g.gone; });
  if (goneExtras.length) {
    var names = goneExtras.map(function (g) { return g.name; });
    var one = names.length === 1;
    out.push(h('div', { class: 'plate' },
      h('div', { class: 'plate-title' }, (one ? 'Группы ' : 'Групп ') + names.join(', ') + ' сейчас нет в таблице'),
      h('div', { class: 'plate-text' }, one
        ? 'Её пары не показываются и вернутся сами, когда она появится. Если её переименовали — выберите заново в настройках.'
        : 'Их пары не показываются и вернутся сами, когда они появятся. Если их переименовали — выберите заново в настройках.'),
      actionButton('Настройки', function () { app.go('settings'); })));
  }
  // Без сети — сказать, что на экране не обновится: сам сбой сервера тут ни при чём.
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    out.push(h('div', { class: 'plate' },
      h('div', { class: 'plate-title' }, 'Нет сети'),
      h('div', { class: 'plate-text' }, 'Расписание не обновляется. На экране — последнее, что было скачано.')));
  }
  if (app.server().status === STATUS_EDITING) {
    // Автор сказал: таблицу правит колледж — не сбой сервера, а ожидание.
    var edited = app.server();
    var source = sheetLink(app.saved(), app.now().date, edited.src_url);
    out.push(h('div', { class: 'plate' },
      h('div', { class: 'plate-title plate-quiet' }, 'Колледж правит таблицу'),
      h('div', { class: 'plate-text' }, 'Расписание обновится само, когда правка закончится. Пары могли поменяться.'),
      edited.since ? h('div', { class: 'plate-since' },
        'С ' + formatSince(edited.since) + '. На экране — таблица, какой она была до правки.') : null,
      source ? actionLink('Открыть таблицу колледжа', source) : null));
  } else if (app.serverBroken()) {
    var server = app.server();
    var unreachable = server.status === STATUS_UNREACHABLE;
    var link = sheetLink(app.saved(), app.now().date, server.src_url);
    out.push(h('div', { class: 'plate' },
      h('div', { class: 'plate-title' }, unreachable ? 'Сервер расписания не отвечает' : 'Сбой на сервере'),
      h('div', { class: 'plate-text' },
        (unreachable ? 'Браузер не достучался до сервера. ' : 'Не удалось прочитать таблицу. ') +
        'Пары могли поменяться.'),
      server.since ? h('div', { class: 'plate-since' }, unreachable
        ? 'Последний ответ сервера — ' + formatSince(server.since) + '. На экране — то, что пришло тогда.'
        : 'Сбой с ' + formatSince(server.since) + '. На экране — таблица, какой она была до сбоя.') : null,
      link ? actionLink('Открыть таблицу колледжа', link) : null));
  }
  return out.length ? h('div', { class: 'plates' }, out) : null;
}

/**
 * Объяснение вместо пустого экрана; null — объяснять нечего. Пустой раздел
 * без единого слова читается как поломка.
 */
function explainMissing(app, schedule, loading, fallback) {
  // Своё — 404 от здорового сервера, а сохранённого нет: сказать сразу, а не
  // «проверьте интернет». Час подтверждения нужен, только чтобы не прятать
  // сохранённое.
  if (!schedule && !loading && fallback !== null && app.goneSuspected() && !app.gone()) {
    var teacherMode = app.isTeacher();
    return explanation(teacherMode ? 'Вас с таким именем нет в таблице' : 'Группы с таким названием нет в таблице',
      teacherMode ? 'Имя в таблице записали иначе или убрали. Найдите себя заново.'
        : 'Её переименовали, разделили или убрали. Выберите заново.', null, false,
      actionButton('Выбрать заново', function () { app.go(teacherMode ? 'pick/self' : 'pick'); }));
  }
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

function explanation(title, text, link, busy, action) {
  return h('div', { class: 'explanation' },
    busy ? h('div', { class: 'spinner', role: 'progressbar', 'aria-label': 'Загрузка' }) : null,
    h('h2', null, title),
    h('p', null, text),
    link ? actionLink('Открыть таблицу колледжа', link) : null,
    action || null);
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
  // Вместе с парами остальных выбранных групп — только на экране.
  content.appendChild(dayCards(app.shown() || schedule, app.now(), platesEl, app.scrollTarget(), app.groupsByName()));
}

function chosenView(app, kind, id) {
  var other = app.state.other;
  var platesEl = plates(app);
  var ready = other && other.kind === kind && other.id === id;
  var list = kind === 'teachers' ? app.state.teachers : app.state.groups;
  var known = (list || []).filter(function (x) { return x.id === id; })[0];
  var name = known ? known.name : ready && other.schedule ? other.schedule.gn : '';
  var box = h('div', { class: 'chosen' },
    h('div', { class: 'chosen-head' },
      h('div', { class: 'chosen-title' },
        h('h2', { class: 'chosen-name' }, name)),
      h('button', { type: 'button', class: 'text-button', onclick: function () { app.toList(kind); } }, 'К списку')));
  if (!ready || other.loading) {
    box.appendChild(spinner());
  } else if (other.notFound) {
    // Сервер ответил: такого нет. Не «проверьте интернет» — ссылка устарела.
    box.appendChild(explanation(kind === 'teachers' ? 'Такого преподавателя нет в таблице' : 'Такой группы нет в таблице',
      'Ссылка устарела или название в таблице поменяли. Найдите в списке.'));
  } else if (!other.schedule) {
    box.appendChild(h('p', { class: 'nothing' }, other.answered
      ? 'Расписание не загрузилось. Сервер не смог его отдать. Повторите позже.'
      : 'Расписание не загрузилось. Проверьте интернет и нажмите ⟳ вверху.'));
  } else {
    var missing = explainMissing(app, other.schedule, false, null);
    if (other.failed && other.schedule) box.appendChild(h('p', { class: 'nothing' }, 'Не обновилось — на экране прежнее.'));
    // Плашки — перед днём, на котором список откроется: вечером это завтра.
    box.appendChild(missing || dayCards(other.schedule, app.now(), platesEl, openingDate(other.schedule)));
  }
  if (platesEl && !platesEl.parentNode) box.insertBefore(platesEl, box.children[1] || null);
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
