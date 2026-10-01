// Раздел «Уведомления» в настройках и окно выбора минут напоминания
// (SettingsScreen.kt, ReminderDialog.kt). Подписку ведёт push.js.

import { h, actionButton, isIos, isIpad, isAndroid, isChrome, dialog, closeDialog, section } from './dom.js';
import { durationShort, tileLabel } from '../format.js';
import { REMIND_CHOICES, lastRemind, revoked, service } from '../push.js';
import { CHANNEL } from '../store.js';

/** Выключатель строкой: подпись слева, бегунок справа — SwitchRow приложения. */
function switchRow(label, on, onChange, key) {
  var input = h('input', { type: 'checkbox', role: 'switch', class: 'switch', 'data-key': key });
  input.checked = on;
  input.addEventListener('change', function () { onChange(input.checked); });
  return h('label', { class: 'switch-row' }, h('span', null, label), input);
}

/**
 * Уведомления: считает и шлёт их служба (push.js). На айфоне — beta, на
 * остальных — альфа-тест: там они идут через серверы Google (у Firefox —
 * Mozilla), а до Google с сервера достаётся не всегда. Каждое
 * ограничение, которое не обойти, — строкой-предупреждением рядом.
 */
export function notifications(app, teacherMode) {
  var ios = isIos();
  // На айпаде — про айпад: формы «на …е», «…а», «…».
  var pad = isIpad();
  var device = pad ? ['айпаде', 'айпада', 'айпад'] : ['айфоне', 'айфона', 'айфон'];
  var title = [h('span', null, 'Уведомления'), ' ', h('span', { class: 'badge' }, ios ? 'beta' : 'альфа-тест')];
  var why = app.pushBlocker();
  if (why === 'home') {
    // У значка своё хранилище: выбор из Safari туда не переезжает (WebKit
    // 181849) — группу придётся выбрать ещё раз.
    return section(title, h('p', null, 'На ' + device[0] + ' уведомления приходят только сайту со значком на экране ' +
      '«Домой», на iOS 16.4 и новее. Добавьте значок — раздел выше — и откройте сайт им: там ' +
      'выберите группу ещё раз (у значка свои настройки) и включите уведомления.'));
  }
  if (why === 'webview') {
    return section(title, h('p', null, 'Во встроенном браузере приложения уведомлений нет — откройте ' +
      'сайт в Chrome, Яндекс Браузере или любом другом. Выбор группы там придётся сделать ещё раз.'));
  }
  if (why === 'unsupported') {
    // Firefox в приватном окне выключает сервис-воркеры, а с ними и
    // уведомления: «не умеет» там неправда.
    return section(title, h('p', null, ios
      ? 'Этот браузер не умеет уведомления сайтов: нужна iOS 16.4 или новее.'
      : 'Этот браузер не умеет уведомления сайтов или не даёт их в этом окне — например, в приватном.'));
  }
  if (why === 'denied') {
    // Куда идти — своё у айфона со значком; в окне инкогнито Chrome
    // разрешения не дать вовсе.
    return section(title, h('p', null, ios
      ? 'Уведомления запрещены: разрешите их в настройках ' + device[1] + ' — «Уведомления» → «Когда пара?' +
        (CHANNEL === 'tested' ? ' tested' : '') + '».'
      : 'Уведомления для этого сайта запрещены в настройках браузера или это окно инкогнито — ' +
        'разрешите их в настройках браузера, в обычном окне.'));
  }

  // Браузер сам снял разрешение: выключатели — как есть на деле, выключены.
  var lost = revoked();
  var now = lost ? { changes: false, remind: 0 } : app.pushChoice();
  function change(next) {
    if ((app.pushEnabled() && !lost) || (!next.changes && !next.remind)) {
      app.setPush(next);
      return;
    }
    // Первое включение — сначала сказать, что появится на сервере.
    // Выключатель под окном — обратно, пока не согласились: окно закрывают
    // и мимо кнопок.
    app.render();
    // Что на сервере — всё, что лежит в записи (push.json), и через кого идёт
    // доставка.
    dialog('Включить уведомления?', [
      h('p', null, 'Чтобы их присылать, сервер будет хранить адрес и ключи, по которым этот браузер ' +
        'принимает уведомления, ' + (teacherMode ? 'выбранное имя из таблицы' : 'вашу группу') +
        ', что присылать, какой сайт, когда вы его открывали последний раз и когда сюда впервые ' +
        'дойдёт уведомление, а если ' +
        (teacherMode ? 'имени' : 'группы') + ' не станет в таблице — когда об этом сообщили. ' +
        'Больше ничего. Выключите — запись сотрётся.'),
      h('p', null, 'Доставляет уведомления служба браузера — Apple, Google, Mozilla или Microsoft: ' +
        'она видит, когда уведомление пришло, но не его текст.'),
    ], [
      { label: 'Отмена' },
      // Разрешение браузера спрашивается прямо в этом нажатии: айфон
      // спрашивает только по нажатию.
      { label: 'Включить', onClick: function () { closeDialog(); app.setPush(next); } },
    ]);
  }

  var body = [
    lost ? h('p', { class: 'warning small' }, 'Браузер отключил уведомления этого сайта — включите их снова.') : null,
    // Смена группы не дошла до службы — сказать, а не молчать.
    !lost && app.pushBehind() ? h('p', { class: 'warning small' },
      'Сервер ещё не знает о новом выборе: уведомления пока о прежнем расписании.') : null,
    // Как в приложении: о других группах уведомлений нет.
    teacherMode ? null : h('p', { class: 'muted small' }, 'Только о вашей группе, не о других.'),
    switchRow('Сообщать об изменениях', now.changes, function (on) {
      change({ changes: on, remind: now.remind });
    }, 'push-changes'),
    h('p', { class: 'muted small' }, 'Отмены и замены на сегодня и завтра.'),
    switchRow('Напоминать о паре', now.remind > 0, function (on) {
      change({ changes: now.changes, remind: on ? (now.remind || lastRemind()) : 0 });
    }, 'push-remind'),
  ];
  if (now.remind > 0) {
    // Кнопка с выбранным, выбор — в своём окне: выпадающий
    // список браузера выглядел чужим, и своего времени в нём не было.
    body.push(h('div', { class: 'value-row' }, h('span', null, 'За сколько предупредить'),
      actionButton(durationShort(now.remind), function () {
        remindDialog(now.remind, function (m) { app.setPush({ changes: now.changes, remind: m }); });
      }, 'fixed', 'remind-minutes', 'За сколько предупредить: ' + durationShort(now.remind) + '. Изменить')));
    body.push(h('p', { class: 'muted small' }, 'Напоминание о первой паре дня или между парами.'));
    body.push(h('p', { class: 'warning small' }, ios
      ? 'Нестабильно: ' + device[2] + ' может задержать напоминание.'
      : 'Нестабильно: браузер может задержать напоминание или не показать его.' +
        (isAndroid() ? ' Надёжнее — приложение.' : '')));
  }
  if (!ios) {
    // Служба рассылки — по адресу подписки, до неё — по браузеру: у Safari на
    // Mac это Apple, у Edge — Microsoft, а не Google.
    body.push(h('p', { class: 'warning small' }, 'Альфа-тест: уведомления идут через серверы ' +
      service() + ' и могут не дойти.'));
  }
  if (isAndroid() && isChrome()) {
    // Chrome на Android сам читает текст уведомлений и может спрятать
    // обычное за пометкой «возможный спам»; сайту это не обойти.
    // Только Chrome: у Samsung Internet и Яндекса такой пометки нет.
    body.push(h('p', { class: 'warning small' }, 'Chrome может спрятать уведомление за пометкой ' +
      '«возможный спам»: откройте его и разрешите этот сайт всегда.'));
  }
  return section(title, body);
}

/** Границы своего времени — те же, что у службы (REMIND_MIN, REMIND_MAX в push/service.py). */
var REMIND_MIN = 10;
var REMIND_MAX = 240;

/**
 * Окно выбора минут (ReminderDialog.kt): сверху крестик, быстрый выбор
 * плитками — нажатие сразу сохраняет, — внизу своё время.
 */
function remindDialog(current, onPick) {
  var quick = REMIND_CHOICES.indexOf(current) >= 0;
  var input = h('input', {
    type: 'text', inputmode: 'numeric', pattern: '[0-9]*', maxlength: '3', autocomplete: 'off',
    class: 'own-input', placeholder: 'Например, 47', 'aria-label': 'Своё время, минут',
  });
  if (!quick) input.value = String(current);
  var field = h('label', { class: 'own-field' }, input, h('span', { class: 'own-unit', 'aria-hidden': 'true' }, 'мин'));
  var done = actionButton('Готово', submit, 'fixed');
  var hint = h('p', { class: 'muted small own-hint' }, 'От ' + REMIND_MIN + ' до ' + REMIND_MAX + ' минут (' + (REMIND_MAX / 60) + ' часа)');

  function value() {
    var n = parseInt(input.value, 10);
    return /^[0-9]{1,3}$/.test(input.value) && n >= REMIND_MIN && n <= REMIND_MAX ? n : 0;
  }
  function update() {
    var digits = input.value.replace(/[^0-9]/g, '').slice(0, 3);
    if (digits !== input.value) input.value = digits;
    var ok = !!value();
    done.disabled = !ok;
    hint.classList.toggle('error', !!input.value && !ok);
    field.classList.toggle('chosen', !quick && input.value === String(current));
  }
  function submit() {
    var m = value();
    if (!m) return;
    closeDialog();
    onPick(m);
  }
  input.addEventListener('input', update);
  input.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') {
      e.preventDefault();
      submit();
    }
  });
  update();

  dialog('За сколько предупредить', [
    h('div', { class: 'dialog-caption' }, 'Быстрый выбор'),
    h('div', { class: 'tiles', role: 'radiogroup', 'aria-label': 'Быстрый выбор' }, REMIND_CHOICES.map(function (m) {
      var label = tileLabel(m);
      var on = m === current;
      return h('button', {
        type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', 'aria-label': durationShort(m),
        class: 'tile' + (on ? ' on' : ''),
        onclick: function () {
          closeDialog();
          onPick(m);
        },
      }, h('span', { class: 'tile-number' }, label[0]), h('span', { class: 'tile-unit' }, label[1]));
    })),
    h('div', { class: 'dialog-caption' }, 'Своё время'),
    h('div', { class: 'own-row' }, field, done),
    hint,
  ], [], { close: true, cls: 'remind-dialog' });
}
