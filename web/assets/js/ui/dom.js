// Разметка без библиотек: h('div', {class: 'x', onclick: f}, детей сколько угодно).

export function h(tag, attrs) {
  var el = document.createElement(tag);
  if (attrs) {
    Object.keys(attrs).forEach(function (key) {
      var value = attrs[key];
      if (value == null || value === false) return;
      // Текст — только детьми, через createTextNode: ключа для innerHTML нет
      // нарочно (аудит сайта, W1, W3).
      if (key === 'class') el.className = value;
      else if (key.indexOf('on') === 0) el.addEventListener(key.slice(2), value);
      else el.setAttribute(key, value === true ? '' : value);
    });
  }
  for (var i = 2; i < arguments.length; i++) append(el, arguments[i]);
  return el;
}

function append(el, child) {
  if (child == null || child === false) return;
  if (Array.isArray(child)) {
    child.forEach(function (c) { append(el, c); });
    return;
  }
  el.appendChild(typeof child === 'string' || typeof child === 'number'
    ? document.createTextNode(String(child)) : child);
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
}

// Значки Material (Apache 2.0) — те же, что в приложении.
var PATHS = {
  refresh: 'M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
};

export function icon(name, cls) {
  var span = document.createElement('span');
  span.className = 'icon' + (cls ? ' ' + cls : '');
  span.setAttribute('aria-hidden', 'true');
  span.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24" focusable="false">' +
    '<path fill="currentColor" d="' + PATHS[name] + '"/></svg>';
  return span;
}

/** Кнопка на подложке — как ActionButton приложения. */
/** `key` — для фокуса после перестройки экрана (data-key). */
/** `spoken` — подпись для чтеца вместо надписи: «Убрать ИСП-924/2», а не «Убрать». */
export function actionButton(label, onClick, cls, key, spoken) {
  return h('button', {
    type: 'button', class: 'action' + (cls ? ' ' + cls : ''), 'data-key': key || null,
    'aria-label': spoken || null, onclick: onClick,
  }, label);
}

/** Ссылка, которая выглядит как кнопка: переход — настоящей ссылкой, без window.open. */
export function actionLink(label, href, cls) {
  return h('a', {
    class: 'action' + (cls ? ' ' + cls : ''), href: href,
    target: '_blank', rel: 'noopener noreferrer',
  }, label);
}

export function externalLink(label, href, cls) {
  return h('a', { class: cls || 'link', href: href, target: '_blank', rel: 'noopener noreferrer' }, label);
}

// ——— плашка внизу экрана ———

var snackTimer = null;

export function snackbar(text) {
  var old = document.querySelector('.snackbar');
  if (old) old.parentNode.removeChild(old);
  var el = h('div', { class: 'snackbar', role: 'status' }, text);
  document.body.appendChild(el);
  clearTimeout(snackTimer);
  snackTimer = setTimeout(function () {
    el.classList.add('hide');
    setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, 200);
  }, 4000);
}

// ——— окно поверх страницы ———

var openDialog = null;

/** Страница под окном — не для чтеца экрана, пока окно открыто. */
function setBackground(hidden) {
  var app = document.getElementById('app');
  if (!app) return;
  if (hidden) app.setAttribute('aria-hidden', 'true');
  else app.removeAttribute('aria-hidden');
}

export function closeDialog() {
  if (!openDialog) return;
  var d = openDialog;
  openDialog = null;
  document.removeEventListener('keydown', d.onKey);
  d.el.classList.add('closing');
  setTimeout(function () {
    if (d.el.parentNode) d.el.parentNode.removeChild(d.el);
  }, 150);
  document.body.classList.remove('dialog-open');
  setBackground(false);
  var target = d.restore && document.body.contains(d.restore) ? d.restore
    : d.restoreKey ? document.querySelector('[data-key="' + d.restoreKey + '"]') : null;
  if (target && target.focus) target.focus();
}

/**
 * Окно с заголовком, телом и кнопками [{label, onClick}]. `opts.close` —
 * крестик справа от заголовка (окно выбора минут): тогда кнопок внизу может
 * не быть вовсе.
 */
export function dialog(title, body, buttons, opts) {
  closeDialog();
  var heading = h('h2', { class: 'dialog-title' }, title);
  var card = h('div', { class: 'dialog' + (opts && opts.cls ? ' ' + opts.cls : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    opts && opts.close
      ? h('div', { class: 'dialog-head' }, heading,
        h('button', { type: 'button', class: 'icon-button dialog-close', 'aria-label': 'Закрыть', onclick: closeDialog }, icon('close')))
      : heading,
    h('div', { class: 'dialog-body' }, body),
    buttons.length ? h('div', { class: 'dialog-buttons' }, buttons.map(function (b) {
      return h('button', { type: 'button', class: 'text-button', onclick: b.onClick || closeDialog }, b.label);
    })) : null);
  var overlay = h('div', { class: 'overlay' }, card);
  overlay.addEventListener('click', function (e) {
    if (e.target === overlay) closeDialog();
  });
  // Окно держит фокус: Tab ходит по его кнопкам и ссылкам, не уходя на
  // страницу под ним (аудит сайта, W7).
  var onKey = function (e) {
    if (e.key === 'Escape' || e.key === 'Esc') {
      closeDialog();
      return;
    }
    if (e.key !== 'Tab') return;
    var items = card.querySelectorAll('button:not([disabled]), a[href], input');
    if (!items.length) return;
    var first = items[0];
    var last = items[items.length - 1];
    // Фокус ушёл из окна (щелчок по тексту окна) — вернуть внутрь.
    if (!card.contains(document.activeElement)) {
      e.preventDefault();
      (e.shiftKey ? last : first).focus();
    } else if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };
  document.addEventListener('keydown', onKey);
  // Кнопку, с которой открыли, запомнить ключом: экран под окном могут
  // перестроить, и сам узел пропадёт (аудит, прогон 2).
  var from = document.activeElement;
  openDialog = { el: overlay, onKey: onKey, restore: from, restoreKey: from && from.getAttribute && from.getAttribute('data-key') };
  document.body.appendChild(overlay);
  document.body.classList.add('dialog-open');
  setBackground(true);
  var first = card.querySelector('button');
  if (first) first.focus();
  return card;
}

// ——— буфер обмена ———

/** Скопировать текст; обещание true — вышло. */
export function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(text).then(function () { return true; }, function () {
      return copyFallback(text);
    });
  }
  return Promise.resolve(copyFallback(text));
}

function copyFallback(text) {
  var area = h('textarea', { readonly: true });
  // Свойствами, не атрибутом style: атрибут запрещает политика безопасности страницы.
  area.style.position = 'fixed';
  area.style.top = '0';
  area.style.left = '0';
  area.style.opacity = '0';
  area.value = text;
  document.body.appendChild(area);
  area.focus();
  area.select();
  try { area.setSelectionRange(0, text.length); } catch (e) { /* старый браузер */ }
  var ok = false;
  try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  document.body.removeChild(area);
  return ok;
}

/** Сайт открыт значком с домашнего экрана, а не во вкладке браузера. */
export function standalone() {
  return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) ||
    navigator.standalone === true;
}

/** iPhone и iPad; iPadOS называет себя Mac, но с касаниями. */
export function isIos() {
  var ua = navigator.userAgent || '';
  return /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
}

export function isAndroid() {
  return /Android/.test(navigator.userAgent || '');
}

/** Firefox: у него своя служба рассылки уведомлений — Mozilla, а не Google. */
export function isFirefox() {
  return /Firefox\//.test(navigator.userAgent || '');
}
