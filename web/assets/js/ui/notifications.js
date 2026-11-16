// Раздел «Уведомления» в настройках и окно выбора минут напоминания
// (SettingsSections.kt, ReminderDialog.kt). Подписку ведёт push.js.

import { h, actionButton, isIos, isIpad, isAndroid, isChrome, dialog, closeDialog, section } from './dom.js';
import { durationShort, tileLabel } from '../format.js';
import { DIGEST_DEFAULT, DIGEST_MAX, DIGEST_MIN, REMIND_CHOICES, lastDigest, lastRemind, revoked, service } from '../push.js';
import { CHANNEL } from '../store.js';

/** Выключатель строкой: подпись слева, бегунок справа (SwitchRow в приложении). */
function switchRow(label, on, onChange, key) {
  var input = h('input', { type: 'checkbox', role: 'switch', class: 'switch', 'data-key': key });
  input.checked = on;
  input.addEventListener('change', function () { onChange(input.checked); });
  return h('label', { class: 'switch-row' }, h('span', null, label), input);
}

/**
 * Уведомления: считает и шлёт их служба (push.js). Везде — beta: не на
 * айфоне они идут через серверы Google (у Firefox — Mozilla), а до Google с
 * сервера достаётся не всегда. Каждое
 * ограничение, которое не обойти, — строкой-предупреждением рядом.
 */
export function notifications(app, teacherMode) {
  var ios = isIos();
  // На айпаде — про айпад: формы «на …е», «…а», «…».
  var pad = isIpad();
  var device = pad ? ['айпаде', 'айпада', 'айпад'] : ['айфоне', 'айфона', 'айфон'];
  var title = [h('span', null, 'Уведомления'), ' ', h('span', { class: 'badge' }, 'beta')];
  var why = app.pushBlocker();
  if (why === 'home') {
    // У значка своё хранилище: выбор из Safari туда не переезжает (WebKit
    // 181849) — группу придётся выбрать ещё раз.
    return section(title, h('p', null, 'На ' + device[0] + ' уведомления приходят только сайту со значком на экране ' +
      '«Домой», на iOS 16.4 и новее. Добавьте значок — раздел выше — и откройте сайт им: там ' +
      (teacherMode ? 'выберите себя' : 'выберите группу') + ' ещё раз (у значка свои настройки) и включите уведомления.'));
  }
  if (why === 'webview') {
    return section(title, h('p', null, 'Во встроенном браузере приложения уведомлений нет — откройте ' +
      'сайт в Chrome, Яндекс Браузере или любом другом. ' +
      (teacherMode ? 'Себя там придётся выбрать ещё раз.' : 'Выбор группы там придётся сделать ещё раз.')));
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
  var now = lost ? { changes: false, remind: 0, digest: 0 } : app.pushChoice();
  function change(next) {
    if ((app.pushEnabled() && !lost) || (!next.changes && !next.remind && !next.digest)) {
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
      // Разрешение браузера спрашивается в этом же нажатии (push.update).
      { label: 'Включить', onClick: function () { closeDialog(); app.setPush(next); } },
    ]);
  }

  var body = [
    lost ? h('p', { class: 'warning small' }, 'Браузер отключил уведомления этого сайта — включите их снова.') : null,
    // Смена группы не дошла до службы — сказать, а не молчать.
    !lost && app.pushBehind() ? h('p', { class: 'warning small' },
      'Сервер ещё не знает о новом выборе: уведомления пока о прежнем расписании.') : null,
    // О других группах уведомлений нет.
    teacherMode ? null : h('p', { class: 'muted small' }, 'Только о вашей группе, не о других.'),
    // Порядок: напоминание, подсказка, минуты, изменения.
    switchRow('Напоминать о паре', now.remind > 0, function (on) {
      var cur = lost ? now : app.pushIntended();
      change({ changes: cur.changes, remind: on ? (cur.remind || lastRemind()) : 0, digest: cur.digest || 0 });
    }, 'push-remind'),
  ];
  if (now.remind > 0) {
    body.push(h('p', { class: 'muted small' }, 'Напоминание о первой паре дня или между парами.'));
    // Кнопка с выбранным, выбор — в своём окне: выпадающий список браузера
    // выглядит чужим, и своего времени в нём не задать.
    body.push(h('div', { class: 'value-row' }, h('span', null, 'За сколько предупредить'),
      actionButton(durationShort(now.remind), function () {
        remindDialog(now.remind, function (m) {
          app.setPush({
            changes: app.pushIntended().changes, remind: m, digest: app.pushIntended().digest || 0,
          });
        });
      }, 'fixed', 'remind-minutes', 'За сколько предупредить: ' + durationShort(now.remind) + '. Изменить')));
    body.push(h('p', { class: 'warning small' }, ios
      ? 'Нестабильно: ' + device[2] + ' может задержать напоминание.'
      : 'Нестабильно: браузер может задержать напоминание или не показать его.' +
        (isAndroid() ? ' Надёжнее — приложение.' : '')));
  }
  // Второе нажатие до ответа службы — от выбора первого, а не от того, что
  // было при отрисовке (app.pushIntended).
  body.push(switchRow('Сообщать об изменениях', now.changes, function (on) {
    var cur = lost ? now : app.pushIntended();
    change({ changes: on, remind: cur.remind, digest: cur.digest || 0 });
  }, 'push-changes'));
  body.push(h('p', { class: 'muted small' }, 'Отмены и замены на сегодня и завтра. С 22:00 до 7:00 не приходят: утром — одно за ночь.'));

  // Вечерняя сводка: первая пара завтрашнего дня.
  body.push(switchRow('Вечерняя сводка', now.digest > 0, function (on) {
    var cur = lost ? now : app.pushIntended();
    change({
      changes: cur.changes, remind: cur.remind, digest: on ? (cur.digest || lastDigest()) : 0,
    });
  }, 'push-digest'));
  if (now.digest > 0) {
    body.push(h('p', { class: 'muted small' }, 'Первая пара завтрашнего дня. В субботу вечером не приходит; ' +
      'нет пар — молчит.'));
    body.push(h('div', { class: 'value-row' }, h('span', null, 'Во сколько'),
      actionButton(timeText(now.digest), function () {
        digestDialog(now.digest, function (minute) {
          app.setPush({
            changes: app.pushIntended().changes, remind: app.pushIntended().remind, digest: minute,
          });
        });
      }, 'fixed', 'digest-time', 'Вечерняя сводка в ' + timeText(now.digest) + '. Изменить')));
  }
  if (!ios) {
    // Служба рассылки — по адресу подписки, до неё — по браузеру: у Safari на
    // Mac это Apple, у Edge — Microsoft, а не Google.
    body.push(h('p', { class: 'warning small' }, 'Уведомления идут через серверы ' +
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

/** Границы своего времени — те же, что у службы (REMIND_MIN, REMIND_MAX в push/subscription.py). */
var REMIND_MIN = 10;
var REMIND_MAX = 240;

/** «20:00» из минуты дня. */
function timeText(minutes) {
  var h = Math.floor(minutes / 60);
  var m = minutes % 60;
  return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
}

/** Окно времени сводки: быстрые вечера и своё «ЧЧ:ММ». */
function digestDialog(current, onPick) {
  var quick = [17 * 60, 18 * 60, 19 * 60, 20 * 60, 21 * 60, 22 * 60];
  var hh = h('input', {
    type: 'text', inputmode: 'numeric', maxlength: '2', class: 'own-input', 'aria-label': 'Часы',
  });
  var mm = h('input', {
    type: 'text', inputmode: 'numeric', maxlength: '2', class: 'own-input', 'aria-label': 'Минуты',
  });
  if (quick.indexOf(current) < 0) {
    hh.value = String(Math.floor(current / 60));
    mm.value = ('0' + (current % 60)).slice(-2);
  }
  var done = actionButton('Готово', submit, 'fixed');
  var hint = h('p', { class: 'muted small own-hint' },
    'С ' + timeText(DIGEST_MIN) + ' до ' + timeText(DIGEST_MAX));

  function value() {
    if (!/^[0-9]{1,2}$/.test(hh.value) || !/^[0-9]{1,2}$/.test(mm.value)) return 0;
    var h = Number(hh.value);
    var m = Number(mm.value);
    var all = h * 60 + m;
    return m < 60 && all >= DIGEST_MIN && all <= DIGEST_MAX ? all : 0;
  }
  function update() {
    var digitsH = hh.value.replace(/[^0-9]/g, '').slice(0, 2);
    if (digitsH !== hh.value) hh.value = digitsH;
    var digitsM = mm.value.replace(/[^0-9]/g, '').slice(0, 2);
    if (digitsM !== mm.value) mm.value = digitsM;
    var ok = !!value();
    done.disabled = !ok;
    hint.classList.toggle('error', (!!hh.value || !!mm.value) && !ok);
  }
  function submit() {
    var minute = value();
    if (!minute) return;
    closeDialog();
    onPick(minute);
  }
  hh.addEventListener('input', update);
  mm.addEventListener('input', update);
  [hh, mm].forEach(function (input) {
    input.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      submit();
    });
  });
  update();

  dialog('Вечерняя сводка', [
    h('div', { class: 'dialog-caption' }, 'Быстрый выбор'),
    h('div', { class: 'tiles', role: 'radiogroup', 'aria-label': 'Быстрый выбор' }, quick.map(function (minute) {
      var on = minute === current;
      return h('button', {
        type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', 'aria-label': timeText(minute),
        class: 'tile' + (on ? ' on' : ''),
        onclick: function () {
          closeDialog();
          onPick(minute);
        },
      }, h('span', { class: 'tile-number' }, timeText(minute)));
    })),
    h('div', { class: 'dialog-caption' }, 'Своё время'),
    h('div', { class: 'own-row' },
      h('label', { class: 'own-field' }, hh, h('span', { class: 'own-unit', 'aria-hidden': 'true' }, 'ч')),
      h('label', { class: 'own-field' }, mm, h('span', { class: 'own-unit', 'aria-hidden': 'true' }, 'мин')),
      done),
    hint,
  ], [], { close: true, cls: 'remind-dialog' });
}

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
