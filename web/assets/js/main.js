// Сайт «Когда пара?»: состояние, переходы, обновление и часы.
//
// Экран строится заново на каждое изменение — их немного: переход, начало и
// конец обновления, звонок, полночь. Поиск и прокрутка при этом сохраняются.

import * as repo from './repo.js';
import * as store from './store.js';
import { h, clear, closeDialog, dialog, snackbar, externalLink } from './ui/dom.js';
import { HttpError } from './api.js';
import { collegeNow, parseIso } from './time.js';
import { currentLessonNumber, dayIndex, parseTime } from './schedule.js';
import { mainScreen } from './ui/today.js';
import { pickerScreen } from './ui/lists.js';
import { settingsScreen } from './ui/settings.js';
import { welcomeScreen } from './ui/welcome.js';
import { showReport } from './ui/report.js';
import { build } from './version.js';

var root = document.getElementById('app');

var state = {
  refreshing: false,
  flash: false,
  refreshFailed: false,
  groups: null,
  teachers: null,
  other: null,
  queries: {},
  /** День, к которому надо прокрутить, когда он появится; null — человек листает сам. */
  wanted: null,
  lastRefresh: 0,
  /** Когда начал крутиться ⟳: новый значок после перестройки продолжает с той же фазы. */
  spinStart: 0,
  /** Запрос кончился, значок доводит оборот. */
  spinningDown: false,
  flashAt: 0,
};

/** Въезд экрана: каким классом и когда начался — перестройка его продолжает. */
var nav = { cls: null, at: 0, depth: null, appear: false };
var appearAt = 0;

var installEvent = null;
var depth = 0;

// ——— переходы ———

/**
 * Адрес — после «#»: «settings», «teachers/<id>», «pick/self». Кнопка «назад»
 * браузера и жест назад на телефоне работают без своего кода.
 */
function parseRoute() {
  var hash = decodeURIComponentSafe(location.hash.replace(/^#\/?/, ''));
  var parts = hash.split('/');
  var head = parts[0];
  if (head === 'settings') return { screen: 'settings' };
  if (head === 'pick') return { screen: 'pick', mode: parts[1] === 'self' ? 'self' : parts[1] === 'second' ? 'second' : 'group' };
  if (head === 'teachers' || head === 'groups') {
    return { screen: 'main', kind: head, id: parts.slice(1).join('/') || null };
  }
  return { screen: 'main', kind: null, id: null };
}

function decodeURIComponentSafe(text) {
  try { return decodeURIComponent(text); } catch (e) { return text; }
}

function go(path, replace) {
  var hash = path ? '#' + path : '';
  var url = location.pathname + location.search + hash;
  if (replace) {
    history.replaceState(null, '', url);
  } else {
    history.pushState(null, '', url);
    depth++;
  }
  onRoute();
}

function back() {
  if (depth > 0) {
    depth--;
    history.back();
  } else {
    go('', true);
  }
}

var app = {
  state: state,
  route: parseRoute(),
  go: go,
  back: back,
  canGoBack: function () { return !!repo.chosen(); },
  isTeacher: repo.isTeacher,
  chosen: repo.chosen,
  second: repo.second,
  saved: repo.saved,
  fetchedAt: repo.fetchedAt,
  server: repo.serverState,
  serverBroken: repo.serverBroken,
  gone: repo.gone,
  secondGone: repo.secondGone,
  pinned: repo.pinned,
  now: function () { return collegeNow(); },
  scrollTarget: function () { return collegeNow().date; },
  togglePin: function (kind, id) { repo.togglePin(kind, id); },
  theme: function () { return store.get('theme') || 'system'; },
  setTheme: function (theme) {
    store.set('theme', theme);
    // Без перестройки экрана: тогда цвета перетекают, а не щёлкают.
    var html = document.documentElement;
    html.classList.add('theme-anim');
    applyTheme();
    setTimeout(function () { html.classList.remove('theme-anim'); }, 400);
  },
  installPrompt: function () { return installEvent; },
  install: function () {
    if (!installEvent) return;
    installEvent.prompt();
    installEvent = null;
    render();
  },
  showReport: function () { showReport(app); },
  finishWelcome: function () {
    store.set('welcome', true);
    go('pick', true);
  },
  loadLists: loadLists,
  refresh: refresh,
  pick: pick,
  tally: function () { return store.get('tally') || { opens: 0, since: 0 }; },
};

function pick(mode, row) {
  if (mode === 'second') {
    repo.selectSecond(row);
    go('settings', true);
  } else {
    if (mode === 'self') repo.selectSelf(row);
    else repo.selectGroup(row);
    state.wanted = collegeNow().date;
    go('', true);
  }
  refresh(true, false);
}

function onRoute() {
  var route = parseRoute();
  var chosen = repo.chosen();
  // Приветствие — один раз; без выбора — сразу к выбору.
  if (!chosen && route.screen !== 'pick') {
    route = { screen: 'pick', mode: 'group' };
    history.replaceState(null, '', location.pathname + location.search + '#pick');
  }
  if (route.screen === 'pick' && route.mode === 'second' && repo.isTeacher()) {
    route = { screen: 'settings' };
  }
  var wasOwn = app.route.screen === 'main' && !app.route.kind;
  var isOwn = route.screen === 'main' && !route.kind;
  var enteredList = route.screen === 'main' && (route.kind !== app.route.kind || route.id !== app.route.id);
  var oldRoute = app.route;
  app.route = route;
  // Глубже — справа, назад — слева. Внутри главного экрана шапка и вкладки
  // стоят на месте, едет только содержимое: иначе смена вкладки выглядела как
  // перезагрузка страницы (Tomon, 28.09).
  var depth = depthOf(route);
  var oldTab = nav.depth == null ? null : tabOf(oldRoute);
  var newTab = tabOf(route);
  if (oldTab && newTab && oldTab !== newTab) {
    nav.cls = tabIndex(newTab) > tabIndex(oldTab) ? 'tab-right' : 'tab-left';
  } else if (oldTab && newTab) {
    nav.cls = depth >= nav.depth ? 'content-forward' : 'content-back';
  } else {
    nav.cls = nav.depth == null || depth === nav.depth ? 'enter-fade'
      : depth > nav.depth ? 'enter-forward' : 'enter-back';
  }
  // Дни всплывают после выбора группы; при переходах внутри экрана хватает сдвига.
  nav.appear = nav.depth != null && nav.depth < 2 && depth >= 2;
  nav.depth = depth;
  if ((isOwn && !wasOwn) || (route.id && enteredList)) state.wanted = collegeNow().date;
  if (route.id) loadOther(route.kind, route.id);
  closeDialog();
  render(true);
}

// ——— отрисовка ———

/** Вкладка главного экрана, где этот адрес; null — не главный экран. */
function tabOf(route) {
  if (route.screen !== 'main') return null;
  if (repo.isTeacher()) return route.kind === 'groups' ? 'students' : 'teachers';
  return route.kind === 'teachers' ? 'teachers' : 'students';
}

/** Порядок вкладок: у преподавателя его раздел первым. */
function tabIndex(tab) {
  return (repo.isTeacher() ? ['teachers', 'students'] : ['students', 'teachers']).indexOf(tab);
}

function depthOf(route) {
  if (!store.get('welcome') && !repo.chosen()) return 0;
  if (route.screen === 'pick') return 1;
  if (route.screen === 'settings' || route.id) return 3;
  return 2;
}

/**
 * Продолжить анимацию, начатую до перестройки экрана: тот же класс и сдвиг
 * назад на прошедшее время. Иначе каждый ответ сервера обрывал бы въезд.
 */
function continueAnimation(el, cls, startedAt, longest) {
  var since = Date.now() - startedAt;
  if (!cls || since >= longest) return;
  el.classList.add(cls);
  if (since > 0) el.style.setProperty('--shift', -since + 'ms');
}

function screenFor(route) {
  if (!store.get('welcome') && !repo.chosen()) return welcomeScreen(app);
  if (route.screen === 'pick') return pickerScreen(app, route.mode);
  if (route.screen === 'settings') return settingsScreen(app);
  return mainScreen(app);
}

/**
 * Перестроить экран. `navigated` — сменился адрес: тогда прокрутка своя, а не
 * прежняя.
 */
function render(navigated) {
  var active = document.activeElement;
  var focusKey = active && active.getAttribute && active.getAttribute('data-query');
  var anchor = navigated ? null : scrollAnchor();
  var scrollY = window.pageYOffset;
  var hadCards = !!root.querySelector('[data-day]');

  var screen = screenFor(app.route);
  if (navigated) nav.at = Date.now();
  continueAnimation(screen, nav.cls, nav.at, 400);
  // Дни всплывают, когда их только что не было: после выбора группы, при
  // переходе, при первом ответе сервера.
  var days = screen.querySelector('.days');
  if (days && ((navigated && nav.appear) || (!navigated && !hadCards))) appearAt = Date.now();
  if (days) continueAnimation(days, 'appear', appearAt, 700);
  // Человек набирает в поиске: поле остаётся прежним узлом, иначе телефон
  // прячет клавиатуру на каждом ответе сервера.
  if (focusKey && !navigated) {
    var fresh = screen.querySelector('[data-query="' + focusKey + '"]');
    if (fresh) {
      active.__onChange = fresh.__onChange;
      fresh.parentNode.replaceChild(active, fresh);
      active.__onChange();
    }
  }
  clear(root);
  root.appendChild(screen);
  if (focusKey && !navigated && document.activeElement !== active && root.contains(active)) active.focus();

  if (!scrollToWanted()) {
    if (anchor) restoreAnchor(anchor);
    else if (navigated) window.scrollTo(0, 0);
    else window.scrollTo(0, scrollY);
  }
}

function headHeight() {
  var head = root.querySelector('.head') || root.querySelector('.topbar');
  return head ? head.getBoundingClientRect().height : 0;
}

/** Первая карточка дня под шапкой и её сдвиг — чтобы после перестройки остаться там же. */
function scrollAnchor() {
  var top = headHeight();
  var cards = root.querySelectorAll('[data-day]');
  for (var i = 0; i < cards.length; i++) {
    var rect = cards[i].getBoundingClientRect();
    if (rect.bottom > top) return { day: cards[i].getAttribute('data-day'), offset: rect.top };
  }
  return null;
}

function restoreAnchor(anchor) {
  var card = root.querySelector('[data-day="' + anchor.day + '"]');
  if (!card) return;
  window.scrollBy(0, card.getBoundingClientRect().top - anchor.offset);
}

/**
 * Довести до нужного дня: первый не раньше него, а если все раньше — последний.
 * Дня ещё нет в данных — ждём, пока обновление его принесёт.
 */
function scrollToWanted() {
  if (!state.wanted) return false;
  var cards = root.querySelectorAll('[data-day]');
  if (!cards.length) return false;
  var days = [];
  for (var i = 0; i < cards.length; i++) days.push({ d: cards[i].getAttribute('data-day') });
  var index = dayIndex(days, state.wanted);
  var card = cards[index];
  // Плашки сбоя стоят перед сегодняшним днём — показать и их.
  var prev = card.previousElementSibling;
  var target = prev && prev.className === 'plates' ? prev : card;
  var y = target.getBoundingClientRect().top + window.pageYOffset - headHeight() - 8;
  // Первый день — с самого верха, вместе со вкладками.
  window.scrollTo(0, index === 0 && target === card ? 0 : Math.max(0, y));
  if (days.some(function (d) { return d.d >= state.wanted; })) state.wanted = null;
  return true;
}

// Человек сам повёл страницу — ждать обещанного дня больше не надо.
function stopWanting() { state.wanted = null; }
window.addEventListener('touchstart', stopWanting, { passive: true });
window.addEventListener('wheel', stopWanting, { passive: true });
window.addEventListener('keydown', function (e) {
  if (/^(Arrow|Page|Home|End|Space| )/.test(e.key || '')) stopWanting();
});

// ——— данные ———

function loadLists(retry) {
  // Сохранённый список — сразу, свежий — следом. null — крутилка, [] — не загрузился.
  var cachedGroups = repo.cachedGroups();
  var cachedTeachers = repo.cachedTeachers();
  if (cachedGroups.length) state.groups = cachedGroups;
  else if (retry) state.groups = null;
  if (cachedTeachers.length) state.teachers = cachedTeachers;
  else if (retry) state.teachers = null;
  if (retry) render();
  Promise.all([repo.freshGroups(), repo.freshTeachers()]).then(function (fresh) {
    state.groups = fresh[0] || cachedGroups;
    state.teachers = fresh[1] || cachedTeachers;
    render();
    if (fresh[0] && fresh[1]) repo.followRenamedPins(fresh[0], fresh[1]);
  });
}

function loadOther(kind, id) {
  var other = state.other;
  if (other && other.kind === kind && other.id === id && !other.failed) return;
  state.other = { kind: kind, id: id, loading: true, schedule: null };
  repo.otherSchedule(kind, id).then(function (schedule) {
    if (!state.other || state.other.kind !== kind || state.other.id !== id) return;
    state.other = { kind: kind, id: id, loading: false, schedule: schedule, failed: !schedule };
    render();
  });
}

function failText(result) {
  if (result.kind === 'gone') {
    return repo.isTeacher() ? 'Вас больше нет в таблице — выберите себя заново'
      : 'Группы больше нет в таблице — выберите заново';
  }
  var error = result.error;
  if (error instanceof HttpError) {
    if (error.status === 429) return 'Сервер занят, попробуйте через минуту';
    if (error.status === 503) {
      return 'Сервер сейчас не отдаёт это расписание, на экране прежнее. ' +
        'Не пройдёт за час — напишите автору в Telegram: @toomonn';
    }
    return 'Не удалось обновить: сервер ответил ' + error.status;
  }
  return 'Не удалось обновить: нет связи с сервером';
}

var flashTimer = null;
/** Оборот ⟳ — как в app.css и в приложении. */
var SPIN_MS = 450;

/**
 * Обновить своё расписание. `manual` — нажали ⟳: тогда неудача — плашкой, и
 * заодно перечитывается открытое чужое расписание.
 */
function refresh(force, manual) {
  if (!repo.chosen()) return;
  if (!state.refreshing && !state.spinningDown) state.spinStart = Date.now();
  state.refreshing = true;
  state.flash = false;
  if (manual) state.refreshFailed = false;
  state.lastRefresh = Date.now();
  if (manual && app.route.id) {
    state.other = null;
    loadOther(app.route.kind, app.route.id);
  }
  render();
  repo.refresh(force).then(function (result) {
    if (manual && (result.kind === 'failed' || result.kind === 'gone')) {
      state.refreshFailed = result.kind === 'failed';
      snackbar(failText(result));
    }
    state.refreshing = repo.isRefreshing();
    if (state.refreshing) {
      render();
      return;
    }
    // Значок доводит оборот до конца — хотя бы один целый: замерший на
    // полповороте выглядит зависшим. Потом галочка или крестик.
    state.spinningDown = true;
    render();
    var turns = Math.max(1, Math.ceil((Date.now() - state.spinStart) / SPIN_MS));
    setTimeout(function () {
      state.spinningDown = false;
      if (state.refreshing) return;
      state.flash = true;
      state.flashAt = Date.now();
      render();
      clearTimeout(flashTimer);
      flashTimer = setTimeout(function () {
        state.flash = false;
        render();
      }, 900);
    }, Math.max(0, state.spinStart + turns * SPIN_MS - Date.now()));
  });
}

// ——— часы ———

/**
 * Что на экране меняется со временем: дата колледжа, звонок, а при сбое —
 * минуты «уже N минут». Сверяемся каждые 10 секунд: таймер к звонку браузер
 * в фоне всё равно придержит.
 */
function clockKey() {
  var now = collegeNow();
  var schedule = repo.saved();
  var bells = (schedule && schedule.bells) || (state.other && state.other.schedule && state.other.schedule.bells) || {};
  var passed = 0;
  Object.keys(bells).forEach(function (n) {
    (bells[n] || []).forEach(function (t) {
      var sec = parseTime(t);
      if (sec != null && sec < now.sec) passed++;
    });
  });
  var key = now.date + '|' + passed + '|' + currentLessonNumber(bells, now.date, now) +
    '|' + new Date().toDateString();
  var server = repo.serverState();
  if (repo.serverBroken() && server.since) {
    key += '|' + Math.floor((Date.now() - parseIso(server.since)) / 60000);
  }
  return key;
}

var lastKey = clockKey();
var lastDate = collegeNow().date;

function tick() {
  var key = clockKey();
  if (key === lastKey) return;
  lastKey = key;
  var date = collegeNow().date;
  if (date !== lastDate) {
    // Новые сутки — снова к сегодняшнему дню и за свежим окном.
    lastDate = date;
    state.wanted = date;
    refresh(false, false);
  }
  render();
}

setInterval(tick, 10000);

// Раз в час, пока страница открыта, — как фоновое обновление у приложения.
setInterval(function () {
  if (document.visibilityState !== 'hidden') refresh(false, false);
}, 60 * 60 * 1000);

document.addEventListener('visibilitychange', function () {
  if (document.visibilityState === 'hidden') return;
  tick();
  // Вернулись на страницу — сверить, не прошло ли и минуты.
  if (Date.now() - state.lastRefresh > 60000) refresh(false, false);
});

window.addEventListener('popstate', function () {
  if (depth > 0) depth--;
  onRoute();
});

// ——— оформление, значок, тестовый режим ———

function applyTheme() {
  var theme = app.theme();
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
}

window.addEventListener('beforeinstallprompt', function (e) {
  e.preventDefault();
  installEvent = e;
});

function testNotice() {
  var key = 'wc-test-notice';
  try {
    if (sessionStorage.getItem(key)) return;
    sessionStorage.setItem(key, '1');
  } catch (e) { /* без хранилища — показывать каждый раз */ }
  dialog('Тестовый режим', [
    h('p', null, 'Сайт работает в тестовом режиме: что-то может показываться не так. ' +
      'Сверяйтесь с таблицей колледжа, когда это важно.'),
    h('p', null, 'Нашли ошибку — напишите автору в Telegram: ',
      externalLink('@toomonn', 'https://t.me/toomonn'), '.'),
  ], [{ label: 'Понятно', onClick: closeDialog }]);
}

function countOpen() {
  var tally = app.tally();
  store.set('tally', { opens: tally.opens + 1, since: tally.since || Date.now() });
}

function registerWorker() {
  // В разработке файлы меняются на каждом сохранении — кэш только мешал бы.
  if (build() === 'разработка' || !('serviceWorker' in navigator) || !window.isSecureContext) return;
  navigator.serviceWorker.register('sw.js').then(null, function () { /* без него работает, только без сети — нет */ });
}

// ——— запуск ———

window.__whensclassStarted = true;
applyTheme();
countOpen();
onRoute();
loadLists(false);
testNotice();
if (repo.chosen()) refresh(false, false);
registerWorker();
