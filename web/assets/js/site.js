// Вокруг расписания: тема, окно тестового режима, счёт открытий и
// сервис-воркер.

import * as store from './store.js';
import { h, closeDialog, dialog, externalLink } from './ui/dom.js';
import { build } from './version.js';
import { app } from './state.js';

var THEME_COLOR = { light: '#f1f2f4', dark: '#000000' };

export function applyTheme() {
  var theme = app.theme();
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
  // Полоса браузера и строка состояния — в цвет выбранной темы, а не системной:
  // у двух тегов свои media, при ручной теме оба — её.
  var metas = document.querySelectorAll('meta[name="theme-color"]');
  for (var i = 0; i < metas.length; i++) {
    var own = /dark/.test(metas[i].getAttribute('media') || '') ? 'dark' : 'light';
    metas[i].setAttribute('content', THEME_COLOR[theme === 'system' ? own : theme]);
  }
}

/** Окно «Тестовый режим» — только на /tested/, проверочной копии сайта. */
export function testNotice() {
  if (store.CHANNEL !== 'tested') return;
  var key = store.CHANNEL + ':test-notice';
  try {
    if (sessionStorage.getItem(key)) return;
    sessionStorage.setItem(key, '1');
  } catch (e) { /* без хранилища — показывать каждый раз */ }
  dialog('Тестовый режим', [
    h('p', null, 'Это проверочная копия сайта: новое попадает сюда раньше, чем на ' +
      'kogda-para-nsk.ru, и может показываться не так. Сверяйтесь с таблицей колледжа, когда это важно.'),
    h('p', null, 'Нашли ошибку — напишите автору в Telegram: ',
      externalLink('@toomonn', 'https://t.me/toomonn'), '.'),
  ], [{ label: 'Понятно', onClick: closeDialog }]);
}

/**
 * Счёт — только настоящее открытие: переход на страницу, а не перезагрузка и
 * не возврат «назад» (как поворот экрана у приложения). По виду перехода, а
 * не раз за сеанс: вкладку держат открытой месяцами.
 */
export function countOpen() {
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

export var workerUpdated = false;
var workerCheckedAt = 0;

export function checkWorkerUpdate() {
  if (!('serviceWorker' in navigator) || Date.now() - workerCheckedAt < 30 * 60 * 1000) return;
  workerCheckedAt = Date.now();
  navigator.serviceWorker.getRegistration().then(function (registration) {
    if (registration) registration.update().then(null, function () { /* без сети — в другой раз */ });
  }, function () { /* нет — и не надо */ });
}

export function registerWorker() {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return;
  // В разработке файлы меняются на каждом сохранении — кэш только мешал бы;
  // воркер, оставшийся от проверки сборки на том же адресе, — снять.
  if (build() === 'разработка') {
    navigator.serviceWorker.getRegistrations().then(function (all) {
      all.forEach(function (r) { r.unregister(); });
    }, function () { /* нет — и не надо */ });
    return;
  }
  // Сменился воркер при уже работавшем — вышла новая сборка; первый — нет.
  var hadController = !!navigator.serviceWorker.controller;
  // Первое открытие после выкладки отдаёт прежняя сборка из кэша, а новая
  // встаёт за ним через секунды. Пока страницу не трогали — показать новую
  // сразу: иначе она дойдёт только при следующем открытии, а при быстрой
  // перезагрузке — и не при нём.
  var touched = false;
  ['pointerdown', 'keydown', 'wheel'].forEach(function (type) {
    window.addEventListener(type, function () { touched = true; }, { capture: true, passive: true });
  });
  navigator.serviceWorker.addEventListener('controllerchange', function () {
    if (hadController) {
      workerUpdated = true;
      if (!touched && document.visibilityState === 'visible' && !document.querySelector('.overlay')) {
        location.reload();
        return;
      }
    }
    hadController = true;
  });
  // После первого показа и обновления: файлы для воркера не спорят за сеть с
  // расписанием.
  var start = function () {
    setTimeout(function () {
      navigator.serviceWorker.register('sw.js').then(null, function () { /* без него работает, только без сети — нет */ });
    }, 3000);
  };
  if (document.readyState === 'complete') start();
  else window.addEventListener('load', start);
}
