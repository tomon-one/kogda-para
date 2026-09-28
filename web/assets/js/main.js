// Сайт «Когда пара?»: состояние, переходы, обновление и часы.
//
// Экран строится заново на каждое изменение — их немного: переход, начало и
// конец обновления, звонок, полночь. Поиск и прокрутка при этом сохраняются.

import * as repo from './repo.js';
import * as store from './store.js';
import { h, clear, closeDialog, dialog, snackbar, externalLink } from './ui/dom.js';
import { HttpError, NetError } from './api.js';
import { collegeNow, parseIso } from './time.js';
import { currentLessonNumber, dayIndex, parseTime } from './schedule.js';
import { mainScreen, refreshLabel } from './ui/today.js';
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
/** Адрес, присланный человеку без своего выбора: открыть его после выбора. */
var pendingRoute = null;
var returningHome = false;

/**
 * Сколько шагов сайта над первым в истории вкладки — в history.state, а не в
 * памяти: переживает перезагрузку, и «назад» из одного места не считается
 * дважды (аудит сайта, прогон 2).
 */
function stepsNow() {
  return (history.state && history.state.steps) || 0;
}

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
    // Id строит служба из названия: латиница, цифры, дефис. Прочее из адреса
    // в запрос не пускать: «..%2F» nginx раскодирует в чужой путь (аудит, W1).
    var id = parts.slice(1).join('/');
    return { screen: 'main', kind: head, id: /^[a-z0-9-]+$/.test(id) ? id : null };
  }
  return { screen: 'main', kind: null, id: null };
}

function decodeURIComponentSafe(text) {
  try { return decodeURIComponent(text); } catch (e) { return text; }
}

function go(path, replace) {
  var hash = path ? '#' + path : '';
  var url = location.pathname + location.search + hash;
  if (replace) history.replaceState({ steps: stepsNow() }, '', url);
  else history.pushState({ steps: stepsNow() + 1 }, '', url);
  onRoute();
}

/** Шаг назад по истории сайта; дальше первого — на своё расписание. */
function back() {
  if (stepsNow() > 0) history.back();
  else go('', true);
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
  goneSuspected: repo.goneSuspected,
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
  /** «Вернуться к своему расписанию» — шагом назад, как и «К списку». */
  toOwn: back,
  /** «К списку» — шагом назад, если пришли из списка, иначе заменой адреса. */
  toList: function (kind) {
    if (stepsNow() > 0) back();
    else go(kind, true);
  },
  loadLists: loadLists,
  refresh: refresh,
  pick: pick,
  tally: function () { return store.get('tally') || { opens: 0, since: 0 }; },
};

function pick(mode, row) {
  if (mode === 'second') {
    repo.selectSecond(row);
    // В настройки — шагом назад, а не новой записью: иначе «назад» оттуда
    // снова открывал настройки (аудит сайта, W5, W0). «Убрать» — уже там.
    if (app.route.screen === 'pick' && stepsNow() > 0) back();
    else if (app.route.screen !== 'settings') go('settings', true);
    else render();
  } else {
    if (mode === 'self') repo.selectSelf(row);
    else repo.selectGroup(row);
    state.wanted = collegeNow().date;
    home();
  }
  refresh(true, false);
}

/**
 * На главный экран, свернув пройденное: «назад» оттуда — вон с сайта, как в
 * приложении. Был присланный адрес — к нему.
 */
function home() {
  var steps = stepsNow();
  if (steps > 0) {
    returningHome = true;
    history.go(-steps);
  } else {
    var target = pendingRoute;
    pendingRoute = null;
    go(target || '', true);
  }
}

function onRoute() {
  var route = parseRoute();
  var chosen = repo.chosen();
  // Приветствие — один раз; без выбора — сразу к выбору.
  if (!chosen && route.screen !== 'pick') {
    // Ссылку на чужое расписание из чата не терять: откроется после выбора.
    if (route.kind && route.id) pendingRoute = route.kind + '/' + route.id;
    route = { screen: 'pick', mode: 'group' };
    history.replaceState({ steps: stepsNow() }, '', location.pathname + location.search + '#pick');
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
  // Экран выбора — всегда за свежими (ответ по ETag, без изменений — пустой):
  // после «Выбрать заново» в старом списке нет новой группы (аудит, прогон 2).
  if (route.screen === 'pick') loadLists(true);
  else if (route.kind) loadLists(emptyList(state.groups) || emptyList(state.teachers));
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
/**
 * Перестроить экран; упало — не вешать страницу на «Загрузке…»: забыть
 * сохранённое (дальше его принесёт обновление) и сказать, что делать.
 */
function render(navigated) {
  try {
    renderScreen(navigated);
  } catch (error) {
    if (window.console) console.error(error);
    repo.forgetSchedule();
    try {
      renderScreen(true);
    } catch (again) {
      clear(root);
      root.appendChild(h('p', { class: 'boot' }, 'Расписание не показалось. Обновите страницу; не поможет — ' +
        'напишите автору в Telegram: @toomonn.'));
    }
  }
}

/**
 * Чем узнать ту же кнопку в перестроенном экране: ключ data-key, а где его
 * нет — подпись для чтеца или текст, и только если такая одна. Иначе фокус
 * уезжал на первую звезду, и Enter закреплял не того (аудит, прогон 2).
 */
function focusSignature(el) {
  if (!el || el === document.body || !root.contains(el)) return null;
  var key = el.getAttribute('data-key');
  if (key) return { key: key };
  var label = el.getAttribute('aria-label') || (el.textContent || '').trim().slice(0, 80);
  return label ? { tag: el.tagName, cls: el.className, label: label } : null;
}

function findBySignature(signature) {
  if (!signature) return null;
  if (signature.key) {
    var keyed = root.querySelectorAll('[data-key]');
    for (var k = 0; k < keyed.length; k++) if (keyed[k].getAttribute('data-key') === signature.key) return keyed[k];
    return null;
  }
  var found = [];
  var all = root.querySelectorAll(signature.tag);
  for (var i = 0; i < all.length; i++) {
    var sig = focusSignature(all[i]);
    if (sig && !sig.key && sig.cls === signature.cls && sig.label === signature.label) found.push(all[i]);
  }
  return found.length === 1 ? found[0] : null;
}

function restoreFocus(signature) {
  if (!signature || (document.activeElement && document.activeElement !== document.body)) return;
  var el = findBySignature(signature);
  if (!el) return;
  try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
}

function renderScreen(navigated) {
  var active = document.activeElement;
  var signature = navigated ? null : focusSignature(active);
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
  if (!focusKey) restoreFocus(signature);

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

/**
 * Свежие списки — не при каждом заходе: группа, открывшая ссылку разом с
 * общего адреса, упиралась в 429 на экране выбора (аудит сайта, W4).
 */
var LISTS_FRESH_MS = 12 * 3600 * 1000;
var listsLoading = false;

function emptyList(list) {
  return !list || !list.length;
}

/**
 * Списки групп и преподавателей: сохранённые — сразу; за свежими — если
 * сохранённых нет, им больше полусуток или просят явно (`force`: «Повторить»,
 * ⟳). null — крутилка, [] — не загрузился.
 */
function loadLists(force) {
  var cachedGroups = repo.cachedGroups();
  var cachedTeachers = repo.cachedTeachers();
  if (cachedGroups.length) state.groups = cachedGroups;
  if (cachedTeachers.length) state.teachers = cachedTeachers;
  var old = Date.now() - (store.get('listsAt') || 0) > LISTS_FRESH_MS;
  if (listsLoading || (!force && !old && cachedGroups.length && cachedTeachers.length)) return;
  listsLoading = true;
  if (!cachedGroups.length || !cachedTeachers.length) {
    if (!cachedGroups.length) state.groups = null;
    if (!cachedTeachers.length) state.teachers = null;
    render();
  }
  Promise.all([repo.freshGroups(), repo.freshTeachers()]).then(function (fresh) {
    listsLoading = false;
    state.groups = fresh[0].list || cachedGroups;
    state.teachers = fresh[1].list || cachedTeachers;
    state.listsBusy = !!(fresh[0].busy || fresh[1].busy);
    if (fresh[0].list && fresh[1].list) {
      store.set('listsAt', Date.now());
      repo.followRenamedPins(fresh[0].list, fresh[1].list);
    }
    render();
  });
}

/** Чужое расписание открыто снова — не старше этого, иначе перезапросить. */
var OTHER_FRESH_MS = 5 * 60 * 1000;

function loadOther(kind, id) {
  var other = state.other;
  // Тот же и свежий — не качать; иначе показывался снимок первого открытия,
  // пока жива страница (аудит сайта, W0).
  if (other && other.kind === kind && other.id === id && !other.failed &&
    (other.loading || Date.now() - (other.at || 0) < OTHER_FRESH_MS)) return;
  // Перезапрос того же — прежнее остаётся на экране, пока не придёт новое, и
  // остаётся, если не пришло: без связи оно лучше пустоты (аудит, прогон 2).
  var prev = other && other.kind === kind && other.id === id && other.schedule ? other : null;
  state.other = { kind: kind, id: id, loading: !prev, schedule: prev && prev.schedule, at: prev && prev.at };
  repo.otherSchedule(kind, id).then(function (r) {
    if (!state.other || state.other.kind !== kind || state.other.id !== id) return;
    if (r.schedule || r.notFound || !prev) {
      state.other = {
        kind: kind, id: id, loading: false, schedule: r.schedule || null, at: r.schedule ? Date.now() : null,
        notFound: !!r.notFound, failed: !r.schedule && !r.notFound,
      };
    } else {
      state.other = { kind: kind, id: id, loading: false, schedule: prev.schedule, at: prev.at, failed: true };
    }
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
  if (error instanceof NetError) {
    return error.timeout ? 'Не удалось обновить: сервер не ответил за 30 секунд'
      : 'Не удалось обновить: нет связи с сервером';
  }
  return 'Не удалось обновить: сервер прислал непонятный ответ';
}

var flashTimer = null;

/** Сказать чтецу экрана — через живую область вне перестраиваемого экрана. */
function announce(text) {
  var live = document.getElementById('live');
  if (!live) return;
  live.textContent = '';
  setTimeout(function () { live.textContent = text; }, 50);
}
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
  state.lastRefresh = Date.now();
  if (manual && app.route.id && state.other) {
    state.other.failed = true;
    loadOther(app.route.kind, app.route.id);
  }
  // Списки не загрузились — ⟳ пробует и их: так и обещает текст под ними.
  if (manual && (emptyList(state.groups) || emptyList(state.teachers))) loadLists(true);
  render();
  repo.refresh(force).then(function (result) {
    // Крестик — у любой неудачи, не только ручной, и снимается любой удачей:
    // иначе автообновление без сети зажигало галочку (аудит сайта, W4, W6).
    if (result.kind === 'failed') state.refreshFailed = true;
    else if (result.kind !== 'nogroup') state.refreshFailed = false;
    if (manual && (result.kind === 'failed' || result.kind === 'gone')) snackbar(failText(result));
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
      if (manual) announce(refreshLabel(app) || 'Расписание обновлено');
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
  try {
    return clockKeyOf();
  } catch (e) {
    return String(Date.now());
  }
}

function clockKeyOf() {
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
  // Сайт обновился, пока вкладка была открыта, — показать новый, пока человек
  // ничего не делает на странице (аудит сайта, W4).
  if (workerUpdated && !document.querySelector('.overlay')) {
    location.reload();
    return;
  }
  // Значок и вкладка из памяти сами за новой сборкой не ходят — спросить.
  checkWorkerUpdate();
  tick();
  if (emptyList(state.groups) || emptyList(state.teachers)) loadLists(true);
  // Вернулись на страницу — сверить, не прошло ли и минуты.
  if (Date.now() - state.lastRefresh > 60000) refresh(false, false);
});

window.addEventListener('popstate', function () {
  if (returningHome) {
    returningHome = false;
    var target = pendingRoute;
    pendingRoute = null;
    history.replaceState({ steps: 0 }, '', location.pathname + location.search + (target ? '#' + target : ''));
  }
  onRoute();
});

// ——— оформление, значок, тестовый режим ———

var THEME_COLOR = { light: '#f1f2f4', dark: '#000000' };

function applyTheme() {
  var theme = app.theme();
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
  // Полоса браузера и строка состояния — в цвет выбранной темы, а не системной
  // (аудит сайта, W5, W8): у двух тегов свои media, при ручной теме оба — её.
  var metas = document.querySelectorAll('meta[name="theme-color"]');
  for (var i = 0; i < metas.length; i++) {
    var own = /dark/.test(metas[i].getAttribute('media') || '') ? 'dark' : 'light';
    metas[i].setAttribute('content', THEME_COLOR[theme === 'system' ? own : theme]);
  }
}

window.addEventListener('beforeinstallprompt', function (e) {
  e.preventDefault();
  installEvent = e;
});

function testNotice() {
  var key = store.CHANNEL + ':test-notice';
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

/**
 * Счёт — только настоящее открытие: переход на страницу, а не перезагрузка и
 * не возврат «назад» (как поворот экрана у приложения). По виду перехода, а
 * не раз за сеанс: вкладку держат открытой месяцами (аудит, прогон 2).
 */
function countOpen() {
  var type = 'navigate';
  try {
    var entry = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
    if (entry) type = entry.type;
    else if (performance.navigation) type = ['navigate', 'reload', 'back_forward'][performance.navigation.type] || 'navigate';
  } catch (e) { /* старый браузер — считать */ }
  if (type !== 'navigate') return;
  var tally = app.tally();
  store.set('tally', { opens: tally.opens + 1, since: tally.since || Date.now() });
}

var workerUpdated = false;
var workerCheckedAt = 0;

function checkWorkerUpdate() {
  if (!('serviceWorker' in navigator) || Date.now() - workerCheckedAt < 30 * 60 * 1000) return;
  workerCheckedAt = Date.now();
  navigator.serviceWorker.getRegistration().then(function (registration) {
    if (registration) registration.update().then(null, function () { /* без сети — в другой раз */ });
  }, function () { /* нет — и не надо */ });
}

function registerWorker() {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return;
  // В разработке файлы меняются на каждом сохранении — кэш только мешал бы;
  // воркер, оставшийся от проверки сборки на том же адресе, — снять (аудит, W0).
  if (build() === 'разработка') {
    navigator.serviceWorker.getRegistrations().then(function (all) {
      all.forEach(function (r) { r.unregister(); });
    }, function () { /* нет — и не надо */ });
    return;
  }
  // Сменился воркер при уже работавшем — вышла новая сборка; первый — нет.
  var hadController = !!navigator.serviceWorker.controller;
  navigator.serviceWorker.addEventListener('controllerchange', function () {
    // Первый захват воркером — не новая сборка; следующие — новая.
    if (hadController) workerUpdated = true;
    hadController = true;
  });
  // После первого показа и обновления: файлы для воркера не спорят за сеть с
  // расписанием (аудит сайта, W2).
  var start = function () {
    setTimeout(function () {
      navigator.serviceWorker.register('sw.js').then(null, function () { /* без него работает, только без сети — нет */ });
    }, 3000);
  };
  if (document.readyState === 'complete') start();
  else window.addEventListener('load', start);
}

// ——— запуск ———

window.__whensclassStarted = true;
applyTheme();
countOpen();
// К сегодняшнему дню и на первом заходе, а не только при смене экрана: со
// вторника по субботу наверху стоял понедельник (аудит сайта, W0).
state.wanted = collegeNow().date;
if (repo.cachedGroups().length) state.groups = repo.cachedGroups();
if (repo.cachedTeachers().length) state.teachers = repo.cachedTeachers();
onRoute();
loadLists(false);
testNotice();
if (repo.chosen()) refresh(false, false);
registerWorker();
