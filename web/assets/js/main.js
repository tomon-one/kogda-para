// Сайт «Когда пара?»: запуск, действия экранов, уведомления и часы.
//
// Состояние — state.js, отрисовка — render.js, переходы — route.js, списки и
// обновление — data.js, тема и сервис-воркер — site.js.

import * as repo from './repo.js';
import * as store from './store.js';
import * as push from './push.js';
import { fitLabels, snackbar } from './ui/dom.js';
import { collegeNow, parseIso } from './time.js';
import { bellsOn, currentLessonNumber, parseTime } from './schedule.js';
import { showReport } from './ui/report.js';
import { state, app } from './state.js';
import { render, focusKey } from './render.js';
import { parseRoute, go, back, home, onRoute, stepsNow } from './route.js';
import { emptyList, loadLists, refresh } from './data.js';
import { applyTheme, checkWorkerUpdate, countOpen, registerWorker, testNotice, workerUpdated } from './site.js';

var installEvent = null;

// Объект app — из state.js: его берут и модули, которые main.js сам
// импортирует, поэтому здесь он только получает методы.
Object.assign(app, {
  state: state,
  route: parseRoute(),
  go: go,
  back: back,
  canGoBack: function () { return !!repo.chosen(); },
  isTeacher: repo.isTeacher,
  chosen: repo.chosen,
  extras: repo.extras,
  saved: repo.saved,
  /** Своё расписание вместе с парами остальных выбранных групп — для экрана. */
  shown: repo.shown,
  fetchedAt: repo.fetchedAt,
  server: repo.serverState,
  serverBroken: repo.serverBroken,
  gone: repo.gone,
  goneSuspected: repo.goneSuspected,
  pinned: repo.pinned,
  now: function () { return collegeNow(); },
  scrollTarget: function () { return collegeNow().date; },
  togglePin: function (kind, id) { repo.togglePin(kind, id); },
  theme: function () { return store.get('theme') || 'system'; },
  /** Подписи выбранных групп у пар: названиями (по умолчанию) или номерами. */
  groupsByName: function () { return store.get('groupLabels') !== 'numbers'; },
  setGroupsByName: function (byName) {
    store.set('groupLabels', byName ? 'names' : 'numbers');
    render();
  },
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
  /** Подгруппа своей группы из строки «Добавить» — остаёмся в настройках. */
  addExtras: function (groups) {
    repo.addExtras(groups);
    render();
    // Фокус — на «Убрать» той же группы: кнопка «Добавить» исчезла, и фокус
    // упал бы на body.
    if (groups.length) focusKey('remove:' + groups[0].id);
    refresh(true, false);
  },
  removeExtra: function (id) {
    var list = repo.extras();
    var at = list.map(function (g) { return g.id; }).indexOf(id);
    var next = list[at + 1] || list[at - 1];
    repo.removeExtra(id);
    render();
    // На соседнюю «Убрать» или на «Добавить другую группу».
    focusKey(next ? 'remove:' + next.id : 'add-group');
  },
  tally: function () { return store.get('tally') || { opens: 0, since: 0 }; },
  render: function () { render(); },
  pushBlocker: push.blocker,
  pushBehind: function () { return push.behind(pushSubject()); },
  pushChoice: push.choice,
  pushIntended: push.intended,
  pushEnabled: push.enabled,
  /** Включить, поменять или выключить уведомления; ответ — снаружи, строкой внизу. */
  setPush: function (next) {
    var who = pushSubject();
    if (!who) return;
    push.update(next, who).then(function () {
      render();
    }, function (error) {
      snackbar(PUSH_FAILS[error && error.reason] || 'Браузер не дал подписаться на уведомления');
      render();
    });
  },
});

var PUSH_FAILS = {
  denied: 'Браузер не разрешил уведомления',
  network: 'Нет связи с сервером — уведомления не включились',
  timeout: 'Сервер не ответил — уведомления не включились',
  server: 'Сервер не принял подписку',
  busy: 'Сервер занят, попробуйте через минуту',
  off: 'На сервере уведомления ещё не настроены',
};

var pushRetry = null;
var pushTries = 0;

/**
 * Служба должна знать нынешнюю подписку и группу. Не вышло — повторить через
 * минуту, до пяти раз, а не ждать перезагрузки: всё это время приходило бы о
 * прежней группе.
 */
function syncPush(again) {
  clearTimeout(pushRetry);
  if (again) pushTries = 0;
  push.sync(pushSubject()).then(function (result) {
    if (result !== 'failed') {
      pushTries = 0;
      // Строка «сервер ещё не знает» в настройках — убрать.
      if (result === true && app.route.screen === 'settings') render();
      return;
    }
    if (++pushTries > 5) return;
    pushRetry = setTimeout(syncPush, 60 * 1000);
  });
}

/** Чьё расписание присылать: своя группа или сам преподаватель. */
function pushSubject() {
  var own = repo.chosen();
  return own ? { kind: repo.isTeacher() ? 'teacher' : 'group', id: own.id } : null;
}

function pick(mode, row) {
  if (mode === 'extra') {
    repo.addExtras([row]);
    // В настройки — шагом назад, а не новой записью: иначе «назад» оттуда
    // снова открыл бы настройки.
    if (app.route.screen === 'pick' && stepsNow() > 0) back();
    else if (app.route.screen !== 'settings') go('settings', true);
    else render();
  } else {
    if (mode === 'self') repo.selectSelf(row);
    else repo.selectGroup(row);
    state.wanted = collegeNow().date;
    // Уведомления — о новой своей группе, а не о прежней.
    pushTries = 0;
    syncPush();
    home();
  }
  refresh(true, false);
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
  var bells = bellsOn(schedule || (state.other && state.other.schedule), now.date);
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
  // Смена группы так и не дошла до службы — пробовать снова.
  if (push.behind(pushSubject())) syncPush(true);
}, 60 * 60 * 1000);

document.addEventListener('visibilitychange', function () {
  if (document.visibilityState === 'hidden') return;
  // Сайт обновился, пока вкладка была открыта, — показать новый, пока человек
  // ничего не делает на странице.
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
  if (push.behind(pushSubject())) syncPush(true);
});

// Поворот, увеличение текста в браузере — надписи кнопок подгоняются заново.
var fitPending = false;
window.addEventListener('resize', function () {
  if (fitPending) return;
  fitPending = true;
  window.requestAnimationFrame(function () {
    fitPending = false;
    fitLabels(document.body);
  });
});

window.addEventListener('beforeinstallprompt', function (e) {
  e.preventDefault();
  installEvent = e;
});

// ——— запуск ———

window.__whensclassStarted = true;
// Открыли по уведомлению «выберите заново» (sw.js, notificationclick).
if (/[?&]gone=1(&|$)/.test(location.search)) {
  repo.expectGone();
  history.replaceState(history.state, '', location.pathname + location.hash);
}
// Снимок до веб-0.2.0 склеен с парами соседней подгруппы — до первого показа.
repo.migrateGroups();
applyTheme();
countOpen();
// К сегодняшнему дню и на первом заходе, а не только при смене экрана, иначе
// наверху стоял бы понедельник.
state.wanted = collegeNow().date;
if (repo.cachedGroups().length) state.groups = repo.cachedGroups();
if (repo.cachedTeachers().length) state.teachers = repo.cachedTeachers();
onRoute();
loadLists(false);
testNotice();
if (repo.chosen()) refresh(false, false);
registerWorker();
// Служба должна знать нынешнюю подписку: браузер мог сменить её сам.
syncPush();
// Нажатие на уведомление по открытой странице — к своему расписанию, а не к
// открытому чужому (сервис-воркер, notificationclick).
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.addEventListener('message', function (event) {
    if (event.data && event.data.t === 'own' && repo.chosen()) {
      state.wanted = collegeNow().date;
      home();
      if (event.data.gone) {
        repo.expectGone();
        refresh(true, false);
      }
    }
  });
}
