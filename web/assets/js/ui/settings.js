// Настройки (SettingsScreen.kt) — без того, чего у сайта нет: виджетов, работы
// в фоне, уведомлений и обновления приложения. Порядок тот же: группа, своё
// для этого телефона (значок, приложение), таблица, оформление, данные и «О
// сайте» (Tomon 28.09).

import { h, icon, actionButton, actionLink, externalLink, snackbar, standalone, isIos, isAndroid, dialog, closeDialog } from './dom.js';
import { sheetLink, durationShort } from '../format.js';
import { REMIND_CHOICES } from '../push.js';
import { MAX_GROUPS, shortLabels, subgroupsOf } from '../schedule.js';
import { groupMark } from './days.js';
import { VERSION, build } from '../version.js';
import { CHANNEL } from '../store.js';

function section(title, body) {
  return h('section', { class: 'card' }, h('h2', { class: 'card-title' }, title), body);
}

function row(main, sub, buttons) {
  return h('div', { class: 'setting-row' },
    h('div', { class: 'setting-text' }, main, sub ? h('div', { class: 'muted small' }, sub) : null),
    h('div', { class: 'setting-buttons' }, buttons));
}

function segmented(options, selected, onPick) {
  var group = h('div', { class: 'segmented', role: 'radiogroup' });
  options.forEach(function (o) {
    var on = o[1] === selected;
    group.appendChild(h('button', {
      type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false',
      class: 'segment' + (on ? ' on' : ''),
      onclick: function (e) {
        var buttons = group.querySelectorAll('.segment');
        for (var i = 0; i < buttons.length; i++) {
          var mine = buttons[i] === e.currentTarget;
          buttons[i].classList.toggle('on', mine);
          buttons[i].setAttribute('aria-checked', mine ? 'true' : 'false');
        }
        onPick(o[1]);
      },
    }, o[0]));
  });
  return group;
}

/** Выключатель строкой: подпись слева, бегунок справа — SwitchRow приложения. */
function switchRow(label, on, onChange, key) {
  var input = h('input', { type: 'checkbox', role: 'switch', class: 'switch', 'data-key': key });
  input.checked = on;
  input.addEventListener('change', function () { onChange(input.checked); });
  return h('label', { class: 'switch-row' }, h('span', null, label), input);
}

/**
 * Уведомления: считает и шлёт их служба (push.js). На айфоне — beta, на
 * остальных — альфа-тест: там они идут через серверы Google, а до них с
 * сервера достаётся не всегда (Tomon 28.09). Каждое ограничение, которое не
 * обойти, — строкой-предупреждением рядом.
 */
function notifications(app, teacherMode) {
  var ios = isIos();
  var title = [h('span', null, 'Уведомления'), ' ', h('span', { class: 'badge' }, ios ? 'beta' : 'альфа-тест')];
  var why = app.pushBlocker();
  if (why === 'home') {
    return section(title, h('p', null, 'На айфоне уведомления приходят только сайту со значком на экране ' +
      '«Домой», на iOS 16.4 и новее. Добавьте значок — раздел выше — откройте сайт им и включите здесь.'));
  }
  if (why === 'unsupported') {
    return section(title, h('p', null, 'Этот браузер не умеет уведомления сайтов' +
      (ios ? ': нужна iOS 16.4 или новее.' : '.')));
  }
  if (why === 'denied') {
    return section(title, h('p', null, 'Уведомления для этого сайта запрещены в настройках браузера — ' +
      'разрешите их там.'));
  }

  var now = app.pushChoice();
  function change(next) {
    if (app.pushEnabled() || (!next.changes && !next.remind)) {
      app.setPush(next);
      return;
    }
    // Первое включение — сначала сказать, что появится на сервере (Tomon 28.09).
    // Выключатель под окном — обратно, пока не согласились: окно закрывают
    // и мимо кнопок.
    app.render();
    dialog('Включить уведомления?', [
      h('p', null, 'Чтобы их присылать, сервер будет хранить адрес, по которому этот браузер ' +
        'принимает уведомления, ' + (teacherMode ? 'выбранное имя из таблицы' : 'название вашей группы') +
        ', что присылать и дату подписки — пока уведомления включены. Больше ничего. ' +
        'Выключите — запись сотрётся.'),
    ], [
      { label: 'Отмена' },
      // Разрешение браузера спрашивается прямо в этом нажатии: айфон
      // спрашивает только по нажатию.
      { label: 'Включить', onClick: function () { closeDialog(); app.setPush(next); } },
    ]);
  }

  var body = [
    switchRow('Сообщать об изменениях', now.changes, function (on) {
      change({ changes: on, remind: now.remind });
    }, 'push-changes'),
    h('p', { class: 'muted small' }, 'Отмены и замены на сегодня и завтра.'),
    switchRow('Напоминать о паре', now.remind > 0, function (on) {
      change({ changes: now.changes, remind: on ? (now.remind || 20) : 0 });
    }, 'push-remind'),
  ];
  if (now.remind > 0) {
    var select = h('select', { class: 'minutes', 'aria-label': 'За сколько предупредить' });
    REMIND_CHOICES.forEach(function (m) {
      var option = h('option', { value: String(m) }, durationShort(m));
      option.selected = m === now.remind;
      select.appendChild(option);
    });
    select.addEventListener('change', function () {
      app.setPush({ changes: now.changes, remind: parseInt(select.value, 10) });
    });
    body.push(h('label', { class: 'switch-row' }, h('span', null, 'За сколько предупредить'), select));
    body.push(h('p', { class: 'muted small' }, 'О первой паре дня — всегда. О следующих — только если ' +
      'напоминание приходится на перемену, а не на пару.'));
    body.push(h('p', { class: 'warning small' }, ios
      ? 'Нестабильно: айфон может задержать напоминание.'
      : 'Нестабильно: браузер может задержать напоминание или не показать его. Надёжнее — приложение.'));
  }
  if (!ios) {
    body.push(h('p', { class: 'warning small' }, 'Альфа-тест: на Android и компьютере уведомления идут через ' +
      'серверы Google и могут не дойти.'));
  }
  return section(title, body);
}

export function settingsScreen(app) {
  var teacherMode = app.isTeacher();
  var own = app.chosen();
  var extras = app.extras();
  var byName = app.groupsByName();
  // С другими группами у каждой — её значок, как под временем пар: номер или
  // короткое название (Tomon 28.09).
  var marks = teacherMode || !extras.length ? null
    : byName ? shortLabels([own ? own.name : ''].concat(extras.map(function (g) { return g.name; })))
      : extras.concat([null]).map(function (g, i) { return String(i + 1); });

  var group = [row(
    h('span', { class: 'group-name' }, marks ? groupMark(marks[0], true) : null,
      h('span', { class: 'group-name-text' }, own ? own.name : 'не выбрано')),
    null,
    actionButton(teacherMode ? 'Выбрать заново' : 'Сменить', function () { app.go(teacherMode ? 'pick/self' : 'pick'); }, 'fixed'))];
  if (!teacherMode) {
    // Остальные группы — любые, до шести вместе со своей (Tomon 28.09).
    extras.forEach(function (g, i) {
      group.push(row(
        h('span', { class: 'group-name' }, marks ? groupMark(marks[i + 1], false) : null,
          h('span', { class: 'group-name-text' }, g.name + (g.gone ? ' — нет в таблице' : ''))),
        null,
        actionButton('Убрать', function () { app.removeExtra(g.id); }, 'fixed', 'remove:' + g.id)));
    });
    var room = MAX_GROUPS - 1 - extras.length;
    var subgroups = own ? subgroupsOf(own.name, app.state.groups || []).filter(function (g) {
      return !extras.some(function (x) { return x.id === g.id; });
    }).slice(0, room) : [];
    // Подгруппы своей группы — строками с «Добавить», как у добавленных
    // «Убрать»: две кнопки рядом выходили разной высоты (Tomon 28.09).
    subgroups.forEach(function (g) {
      group.push(row(g.name, 'подгруппа вашей группы',
        actionButton('Добавить', function () { app.addExtras([g]); }, 'fixed', 'add:' + g.id)));
    });
    if (room > 0) {
      group.push(h('div', { class: 'setting-actions' },
        actionButton(extras.length || subgroups.length ? 'Добавить другую группу' : 'Добавить группу',
          function () { app.go('pick/extra'); }, null, 'add-group')));
    }
    if (!extras.length) {
      group.push(h('p', { class: 'muted small' }, 'Пары других групп можно видеть в расписании рядом со своими.'));
    } else {
      // Названия или номера под временем пар (Tomon 28.09).
      group.push(h('div', { class: 'setting-sub' }, 'Подписи у пар'));
      group.push(segmented([['Названия', 'names'], ['Номера', 'numbers']], byName ? 'names' : 'numbers',
        function (value) { app.setGroupsByName(value === 'names'); }));
      // Что значат значки — здесь, где их выбирают (Tomon 28.09).
      group.push(h('p', { class: 'muted small' },
        'Под временем пары — группы, у которых она есть: ваша закрашена, остальные бледные. ' +
        'Пары, которых у вашей группы нет, — на сером фоне.'));
    }
  }

  var link = sheetLink(app.saved(), app.now().date, app.server().src_url);
  var install = app.installPrompt();
  var ios = isIos();
  var android = !ios && isAndroid();

  return h('div', { class: 'screen settings' },
    h('header', { class: 'topbar' },
      h('button', { type: 'button', class: 'icon-button', 'aria-label': 'Назад', onclick: app.back }, icon('back')),
      h('h1', { class: 'topbar-title' }, 'Настройки')),
    h('main', { class: 'content cards' },
      section(teacherMode ? 'Преподаватель' : 'Группа', group),

      // Только во вкладке браузера: открытому значком он ни к чему. Шаги — для
      // этого телефона; есть кнопка браузера — она вместо шагов (разбор 28.09).
      standalone() ? null : section('Значок на экране', [
        h('p', null, 'Сайт можно открыть значком с домашнего экрана, как приложение.'),
        install ? actionButton('Добавить значок', app.install) : null,
        !install && !android ? h('p', null, 'iPhone и iPad: в Safari «Поделиться» → «На экран „Домой“».') : null,
        !install && !ios ? h('p', null, 'Android: меню браузера → «Добавить на главный экран».') : null,
      ]),

      own ? notifications(app, teacherMode) : null,

      // На айфоне его не поставить.
      ios ? null : section('Приложение для Android', [
        h('p', null, 'Виджеты на домашнем экране, напоминания о парах и уведомления об отменах и заменах.'),
        actionLink('Скачать', '/download/latest.apk'),
        h('p', { class: 'small' }, externalLink('Как поставить',
          'https://github.com/tomon-one/kogda-para/blob/master/docs/install.md')),
      ]),

      section('Расписание', [
        // Дня нет в ответе (воскресенье, каникулы) — ссылка ведёт на ближайший
        // следующий; у преподавателя — колонка группы первой пары, без пар —
        // только строка дня (sheetLink).
        h('p', null, 'Сайт забирает его при открытии и раз в час, пока страница открыта; сервер читает ' +
          'таблицу колледжа чаще и перед каждой парой. Кнопка ниже ' +
          (teacherMode ? 'откроет таблицу на сегодняшнем дне, а если его в таблице нет — на ближайшем ' +
            'следующем; в день с парами — у колонки группы первой из них.'
            : 'откроет таблицу на вашей колонке и сегодняшнем дне, а если его в таблице нет — на ' +
              'ближайшем следующем.')),
        link ? actionLink('Открыть таблицу колледжа', link)
          : actionButton('Открыть таблицу колледжа', function () { snackbar('Адрес таблицы ещё не получен от сервера'); }),
      ]),

      section('Оформление', segmented(
        [['Системная', 'system'], ['Тёмная', 'dark'], ['Светлая', 'light']],
        app.theme(), app.setTheme)),

      // Два текста, которые читают один раз, — одной карточкой (Tomon 28.09).
      section('Данные и ответственность', [
        h('p', null, (teacherMode
          ? 'На сервер уходит выбранное имя, как оно записано в таблице колледжа. Больше ничего: '
          : 'На сервер уходят только названия вашей группы и других, если вы их выбрали. ' +
            'Больше ничего: ни имени, ') +
          'ни номера телефона, ни местоположения. Учётной записи нет, аналитики и рекламы нет, ' +
          'выбор хранится только в этом браузере. Когда смотрите чужое расписание, серверу уходит, ' +
          'чьё именно: иначе его неоткуда взять. Всё для вашего удобства.'),
        // Уведомления — единственное, что сервер хранит о браузере (Tomon 28.09).
        h('p', null, 'Пока включены уведомления, сервер хранит адрес, по которому этот браузер их ' +
          'принимает, ' + (teacherMode ? 'и выбранное имя' : 'и вашу группу') + ', что присылать и дату ' +
          'подписки. Выключите — запись сотрётся.'),
        h('p', null, 'Что написано в таблице колледжа, то и покажет сайт: за ошибки, замены и ' +
          'опоздавшие обновления автор не отвечает.'),
        h('p', null, 'Если однажды что-то сломается, автор постарается починить, но сроков не обещает. ' +
          'Пропущенная пара остаётся на вашей совести, даже если сайт в этот момент показывал ' +
          'неправильно. Сверяйтесь с таблицей, когда это важно.'),
      ]),

      // «Я преподаватель» отсюда убрано: тот же переход — первой строкой
      // списка, куда ведёт «Сменить» (Tomon 28.09).
      section('О сайте', [
        h('p', null, 'Неофициальный сайт для студентов и преподавателей НГОК.'),
        h('p', null, externalLink('Нашли ошибку? Напишите автору в Telegram', 'https://t.me/toomonn')),
        h('p', null, h('button', { type: 'button', class: 'text-link', 'data-key': 'report', onclick: app.showReport }, 'Сведения для отчёта')),
        h('p', null, externalLink('Исходный код', 'https://github.com/tomon-one/kogda-para')),
        h('p', null, externalLink('GitHub автора', 'https://github.com/tomon-one')),
        h('p', { class: 'muted small' }, 'Версия ' + VERSION + (CHANNEL === 'tested' ? ' tested' : '') + ', сборка ' + build()),
        h('p', { class: 'signature' }, 'Создано Tomon'),
      ])));
}
