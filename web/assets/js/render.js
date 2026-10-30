// Отрисовка: экран строится заново на каждое изменение — их немного: переход,
// начало и конец обновления, звонок, полночь. Фокус, поиск и прокрутка при
// этом сохраняются.

import * as repo from './repo.js';
import * as store from './store.js';
import { h, clear, fitLabels } from './ui/dom.js';
import { dayIndex } from './schedule.js';
import { mainScreen, refreshButton } from './ui/today.js';
import { forgetPick, rowShift, syncRow } from './ui/week.js';
import { pickerScreen } from './ui/lists.js';
import { settingsScreen } from './ui/settings.js';
import { welcomeScreen } from './ui/welcome.js';
import { root, state, nav, app, OPENING } from './state.js';

var appearAt = 0;

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
 * прежняя. Упало — не вешать страницу на «Загрузке…»: забыть сохранённое
 * (дальше его принесёт обновление) и сказать, что делать.
 */
export function render(navigated) {
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
 * уехал бы на первую звезду, и Enter закрепил бы не того.
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


/** Фокус — на кнопку с этим data-key, если она есть. */
export function focusKey(key) {
  var el = findBySignature({ key: key });
  if (!el) return;
  try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
}

function restoreFocus(signature) {
  if (!signature || (document.activeElement && document.activeElement !== document.body)) return;
  var el = findBySignature(signature);
  if (!el) return;
  try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
}

function renderScreen(navigated) {
  var active = document.activeElement;
  var hadFocus = active && active !== document.body && root.contains(active);
  var signature = focusSignature(active);
  if (navigated && !(signature && signature.key)) signature = null;
  var focusKey = active && active.getAttribute && active.getAttribute('data-query');
  var anchor = navigated ? null : scrollAnchor();
  var scrollY = window.pageYOffset;
  var hadCards = !!root.querySelector('[data-day]');
  var hadPlates = plateTitles();
  var oldPlates = root.querySelector('.plates');
  var platesHigh = platesHeight(oldPlates);

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
  // Имя открытого преподавателя или группы прилипает под шапкой (screens.css, .chosen-head).
  measureHead();
  syncRow(navigated);
  fitLabels(screen);
  if (focusKey && !navigated && document.activeElement !== active && root.contains(active)) active.focus();
  if (!focusKey) restoreFocus(signature);
  // Переход, а той же кнопки на новом экране нет — фокус на заголовок, а не
  // на body: иначе клавиатура и чтец начинают сначала.
  if (navigated && hadFocus && (!document.activeElement || document.activeElement === document.body)) {
    var heading = root.querySelector('h1, h2');
    if (heading) {
      heading.setAttribute('tabindex', '-1');
      try { heading.focus({ preventScroll: true }); } catch (e) { heading.focus(); }
    }
  }

  if (!scrollToWanted()) {
    if (anchor) restoreAnchor(anchor);
    else if (navigated) window.scrollTo(0, 0);
    else window.scrollTo(0, scrollY);
    if (!navigated) {
      if (anchor) showNewPlate(hadPlates, anchor);
      else keepList(platesHigh, scrollY);
    }
  }
}

function plateTitles() {
  return Array.prototype.map.call(root.querySelectorAll('.plate-title'), function (el) { return el.textContent; });
}

/** Высота блока плашек вместе с отступом под ним: отступ — у плашки и схлопывается сквозь блок. */
function platesHeight(el) {
  if (!el) return 0;
  var last = el.lastElementChild;
  return el.getBoundingClientRect().height + (last ? parseFloat(window.getComputedStyle(last).marginBottom || 0) : 0);
}

/** Плашка появилась или ушла над списком без дней: список не прыгает на её высоту. */
function keepList(before, scrollY) {
  if (scrollY <= 0) return;
  var plates = root.querySelector('.plates');
  var now = platesHeight(plates);
  if (now === before) return;
  // Новая плашка на виду — листать незачем.
  if (now > before && plates.getBoundingClientRect().top >= headHeight()) return;
  window.scrollTo(0, Math.max(0, scrollY + now - before));
}

/**
 * Плашка появилась посреди открытого экрана («Нет сети», сбой сервера).
 * Блок плашек стоит перед сегодняшним днём, а перестройка держит на месте
 * карточку под шапкой — плашка уезжала бы под шапку. Стоит человек на этом
 * дне — довести до неё: сбой без плашки хуже сдвига.
 */
function showNewPlate(had, anchor) {
  var plates = root.querySelector('.plates');
  if (!plates || !plateTitles().some(function (t) { return had.indexOf(t) < 0; })) return;
  var top = plates.getBoundingClientRect().top;
  if (top >= headHeight()) return;
  // Только если плашка — прямо над днём на экране: ушедшего вглубь недели
  // назад не тянуть.
  var next = plates.nextElementSibling;
  if (!next || next.getAttribute('data-day') !== anchor.day) return;
  window.scrollTo(0, Math.max(0, top + window.pageYOffset - headHeight() - 8));
}

/** Только ⟳ в шапке — без перестройки экрана (today.js, refreshButton). */
export function redrawRefresh() {
  var old = root.querySelector('[data-key="refresh"]');
  if (!old) return;
  var fresh = refreshButton(app);
  var focused = document.activeElement === old;
  old.parentNode.replaceChild(fresh, old);
  if (focused) fresh.focus();
}

/** Сколько сверху закрыто: шапка и прилипшее имя открытого преподавателя или группы. */
function headHeight() {
  var head = root.querySelector('.head') || root.querySelector('.topbar');
  var chosen = root.querySelector('.chosen-head');
  // Полоска дней прилипает под шапкой — вместе с полями.
  var strip = root.querySelector('.week-strip');
  return stuck(head) + rowShift() + (chosen ? chosen.getBoundingClientRect().height : 0) + (strip ? strip.offsetHeight + 14 : 0);
}

/** Под шапкой прилипает полоска дней: высота шапки меняется и при смене ширины окна. */
export function measureHead() {
  root.style.setProperty('--head-h', stuck(root.querySelector('.head')) + 'px');
}

/** Высота прилипшей шапки; не прилипает (альбомная, app.css) — ноль. */
function stuck(head) {
  if (!head || !/sticky/.test(window.getComputedStyle(head).position)) return 0;
  return head.getBoundingClientRect().height;
}

/**
 * Первая карточка дня под шапкой и её сдвиг от низа шапки — чтобы после
 * перестройки остаться там же. От низа шапки, а не от края окна: шапка между
 * перестройками растёт (загорелся ряд «Пересдачи»), и карточка уехала бы под неё.
 */
function scrollAnchor() {
  var top = headHeight();
  var cards = root.querySelectorAll('[data-day]');
  for (var i = 0; i < cards.length; i++) {
    var rect = cards[i].getBoundingClientRect();
    if (rect.bottom > top) return { day: cards[i].getAttribute('data-day'), offset: rect.top - top };
  }
  return null;
}

function restoreAnchor(anchor) {
  var card = root.querySelector('[data-day="' + anchor.day + '"]');
  if (!card) return;
  window.scrollBy(0, card.getBoundingClientRect().top - headHeight() - anchor.offset);
}

/**
 * Довести до нужного дня: первый не раньше него, а если все раньше — последний.
 * Дня ещё нет в данных — ждём, пока обновление его принесёт.
 */
function scrollToWanted() {
  if (!state.wanted) return false;
  var cards = root.querySelectorAll('[data-day]');
  if (!cards.length) return false;
  // Звонки — пришедшего расписания: до него (выбрали группу) день открытия
  // вечером был бы сегодняшним.
  if (state.wanted === OPENING) state.wanted = repo.openingDate(app.route.id && state.other ? state.other.schedule : null);
  var days = [];
  for (var i = 0; i < cards.length; i++) days.push({ d: cards[i].getAttribute('data-day') });
  var index = dayIndex(days, state.wanted);
  forgetPick();
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
