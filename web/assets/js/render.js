// Отрисовка: экран строится заново на каждое изменение — их немного: переход,
// начало и конец обновления, звонок, полночь. Фокус, поиск и прокрутка при
// этом сохраняются.

import * as repo from './repo.js';
import * as store from './store.js';
import { h, clear } from './ui/dom.js';
import { dayIndex } from './schedule.js';
import { mainScreen } from './ui/today.js';
import { pickerScreen } from './ui/lists.js';
import { settingsScreen } from './ui/settings.js';
import { welcomeScreen } from './ui/welcome.js';
import { root, state, nav, app } from './state.js';

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
