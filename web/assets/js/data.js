// Данные: списки групп и преподавателей, чужое расписание и обновление
// своего — с крутилкой ⟳, галочкой и строкой о неудаче.

import * as repo from './repo.js';
import * as store from './store.js';
import { snackbar } from './ui/dom.js';
import { HttpError, NetError } from './api.js';
import { refreshLabel } from './ui/today.js';
import { state, app } from './state.js';
import { render } from './render.js';

/**
 * Свежие списки — не при каждом заходе: группа, открывшая ссылку разом с
 * общего адреса, упрётся в 429 на экране выбора.
 */
var LISTS_FRESH_MS = 12 * 3600 * 1000;
var listsLoading = false;

export function emptyList(list) {
  return !list || !list.length;
}

/**
 * Списки групп и преподавателей: сохранённые — сразу; за свежими — если
 * сохранённых нет, им больше полусуток или просят явно (`force`: «Повторить»,
 * ⟳). null — крутилка, [] — не загрузился.
 */
export function loadLists(force) {
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

var otherTicket = 0;

/** Чужое расписание открыто снова — не старше этого, иначе перезапросить. */
var OTHER_FRESH_MS = 5 * 60 * 1000;

export function loadOther(kind, id) {
  var other = state.other;
  // Тот же и свежий или уже грузится — не качать.
  if (other && other.kind === kind && other.id === id && !other.failed &&
    (other.loading || Date.now() - (other.at || 0) < OTHER_FRESH_MS)) return;
  // Перезапрос того же — прежнее остаётся на экране, пока не придёт новое, и
  // остаётся, если не пришло: без связи оно лучше пустоты.
  var prev = other && other.kind === kind && other.id === id && other.schedule ? other : null;
  // Номер запроса: поздний ответ прежнего не затирает свежий.
  var ticket = ++otherTicket;
  state.other = { kind: kind, id: id, loading: !prev, schedule: prev && prev.schedule, at: prev && prev.at, ticket: ticket };
  repo.otherSchedule(kind, id).then(function (r) {
    if (!state.other || state.other.ticket !== ticket) return;
    if (r.schedule || r.notFound || !prev) {
      state.other = {
        kind: kind, id: id, loading: false, schedule: r.schedule || null, at: r.schedule ? Date.now() : null,
        notFound: !!r.notFound, failed: !r.schedule && !r.notFound, ticket: ticket,
      };
    } else {
      state.other = { kind: kind, id: id, loading: false, schedule: prev.schedule, at: prev.at, failed: true, ticket: ticket };
    }
    render();
  });
}

function failText(result) {
  if (result.kind === 'partial') return partialText(result.missed, result.fresh || []);
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

/**
 * Своё пришло, другие группы — не все. Про прежние пары — только если они
 * есть: у только что добавленной группы их нет (partialText в App.kt).
 */
function partialText(missed, fresh) {
  var one = missed.length === 1;
  var names = missed.join(', ');
  var isNew = function (name) { return fresh.indexOf(name) >= 0; };
  if (missed.every(isNew)) {
    return one ? 'Не загрузилась группа ' + names + ': её пары придут со следующим обновлением'
      : 'Не загрузились группы ' + names + ': их пары придут со следующим обновлением';
  }
  if (!missed.some(isNew)) {
    return one ? 'Не обновилась группа ' + names + ': на экране её прежние пары'
      : 'Не обновились группы ' + names + ': на экране их прежние пары';
  }
  return 'Не обновились группы ' + names + ': пары придут со следующим обновлением';
}

var flashTimer = null;
var retryTimer = null;

/** Не удалось — ещё раз через минуту: одна неудача ещё не «сервер не отвечает». */
function retrySoon() {
  if (retryTimer) return;
  retryTimer = setTimeout(function () {
    retryTimer = null;
    if (document.visibilityState !== 'hidden') refresh(false, false);
  }, 60000);
}

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
export function refresh(force, manual) {
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
    // иначе автообновление без сети зажгло бы галочку.
    if (result.kind === 'failed') {
      state.refreshFailed = true;
      retrySoon();
    } else if (result.kind !== 'nogroup') {
      state.refreshFailed = false;
    }
    // Другие группы не все: крестик с их именами и повтор через минуту.
    state.partial = result.kind === 'partial' ? result.missed : null;
    if (state.partial) retrySoon();
    if (manual && (result.kind === 'failed' || result.kind === 'gone' || result.kind === 'partial')) {
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
