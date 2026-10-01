// Настройки (SettingsScreen.kt) — без того, чего у сайта нет: виджетов, работы
// в фоне и обновления приложения. Порядок тот же: группа, своё для этого
// телефона (значок, уведомления, приложение), таблица, оформление, данные и
// «О сайте». Раздел уведомлений — notifications.js.

import { h, icon, actionButton, actionLink, externalLink, snackbar, standalone, isIos, isAndroid, dialog, copyText, section } from './dom.js';
import { sheetLink } from '../format.js';
import { MAX_GROUPS, shortLabels, subgroupsOf } from '../schedule.js';
import { groupMark } from './days.js';
import { notifications } from './notifications.js';
import { VERSION, build } from '../version.js';
import { CHANNEL } from '../store.js';

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

var SITE = 'https://kogda-para-nsk.ru';
var REPO = 'https://github.com/tomon-one/kogda-para';

/**
 * «Поделиться» (ShareDialog в приложении): код для камеры, ссылки на сайт и
 * на исходный код с копированием, системное «Отправить», где оно есть. Код
 * ведёт в репозиторий: оттуда и сайт, и файл приложения.
 */
function share() {
  function copyRow(label, url) {
    return h('div', { class: 'setting-row' },
      h('div', { class: 'setting-text' }, h('div', { class: 'muted small' }, label), url.replace('https://', '')),
      h('div', { class: 'setting-buttons' }, actionButton('Скопировать', function () {
        copyText(url).then(function (ok) { snackbar(ok ? 'Скопировано' : 'Скопировать не вышло — выделите ссылку вручную'); });
      }, 'fixed', null, 'Скопировать ссылку: ' + label)));
  }
  var text = '«Когда пара?» — расписание НГОК на экране телефона: ' + SITE +
    '\nПриложение для Android и исходный код: ' + REPO;
  var buttons = [{ label: 'Закрыть' }];
  if (navigator.share) {
    buttons.unshift({
      label: 'Отправить',
      onClick: function () {
        navigator.share({ title: 'Когда пара?', text: text }).then(null, function () { /* передумали */ });
      },
    });
  }
  dialog('Поделиться', [
    h('img', { class: 'share-qr', src: 'assets/qr.png', width: '200', height: '200',
      alt: 'Код со ссылкой на исходный код и файл приложения' }),
    h('p', { class: 'muted small' }, 'Наведите камеру — откроется страница приложения: оттуда и файл, и сайт.'),
    copyRow('Сайт', SITE),
    copyRow('Исходный код', REPO),
  ], buttons);
}

export function settingsScreen(app) {
  var teacherMode = app.isTeacher();
  var own = app.chosen();
  var extras = app.extras();
  var byName = app.groupsByName();
  // С другими группами у каждой — её значок, как под временем пар: номер или
  // короткое название.
  var marks = teacherMode || !extras.length ? null
    : byName ? shortLabels([own ? own.name : ''].concat(extras.map(function (g) { return g.name; })))
      : extras.concat([null]).map(function (g, i) { return String(i + 1); });

  // Значок, повторяющий название целиком, только сжимал бы его — такой не
  // ставится ни своей группе, ни другим.
  var ownMark = marks && own && marks[0] !== own.name ? marks[0] : null;
  var group = [row(
    h('span', { class: 'group-name' }, ownMark ? groupMark(ownMark, true) : null,
      h('span', { class: 'group-name-text' }, own ? own.name : 'не выбрано')),
    null,
    actionButton(teacherMode ? 'Выбрать заново' : 'Сменить', function () { app.go(teacherMode ? 'pick/self' : 'pick'); }, 'fixed'))];
  if (!teacherMode) {
    // Остальные группы — любые, до шести вместе со своей.
    extras.forEach(function (g, i) {
      group.push(row(
        h('span', { class: 'group-name' }, marks && marks[i + 1] !== g.name ? groupMark(marks[i + 1], false) : null,
          h('span', { class: 'group-name-text' }, g.name + (g.gone ? ' — нет в таблице' : ''))),
        null,
        // Несколько одинаковых «Убрать» — чтецу с названием.
        actionButton('Убрать', function () { app.removeExtra(g.id); }, 'fixed', 'remove:' + g.id, 'Убрать ' + g.name)));
    });
    var room = MAX_GROUPS - 1 - extras.length;
    var subgroups = own ? subgroupsOf(own.name, app.state.groups || []).filter(function (g) {
      return !extras.some(function (x) { return x.id === g.id; });
    }).slice(0, room) : [];
    // Подгруппы своей группы — строками с «Добавить», как у добавленных
    // «Убрать»: две кнопки рядом вышли бы разной высоты.
    subgroups.forEach(function (g) {
      group.push(row(g.name, 'подгруппа вашей группы',
        actionButton('Добавить', function () { app.addExtras([g]); }, 'fixed', 'add:' + g.id, 'Добавить ' + g.name)));
    });
    if (room > 0) {
      group.push(h('div', { class: 'setting-actions' },
        actionButton(extras.length || subgroups.length ? 'Добавить другую группу' : 'Добавить группу',
          function () { app.go('pick/extra'); }, null, 'add-group')));
    }
    if (!extras.length) {
      group.push(h('p', { class: 'muted small' }, 'Пары других групп можно видеть в расписании рядом со своими.'));
    } else {
      // Названия или номера под временем пар.
      group.push(h('div', { class: 'setting-sub' }, 'Подписи у пар'));
      group.push(segmented([['Названия', 'names'], ['Номера', 'numbers']], byName ? 'names' : 'numbers',
        function (value) { app.setGroupsByName(value === 'names'); }));
      // Что значат значки — здесь, где их выбирают.
      group.push(h('p', { class: 'muted small' },
        'Под часами пары — группы, у которых она есть: ваша закрашена, остальные более бледные. ' +
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

      // Только во вкладке браузера: открытому значком он ни к чему. Есть
      // кнопка браузера — она вместо шагов; шаги — для этого телефона, а на
      // компьютере без кнопки раздела нет.
      standalone() || (!install && !ios && !android) ? null : section('Значок на экране', [
        h('p', null, 'Сайт можно открыть значком с домашнего экрана, как приложение.'),
        install ? actionButton('Добавить значок', app.install) : null,
        // На iOS 26 кнопки «Поделиться» на панели по умолчанию нет — она в
        // меню «•••» у адреса.
        !install && ios ? h('p', null, 'В Safari: кнопка «Поделиться» — на панели или в меню «•••» у ' +
          'адреса → «На экран „Домой“»; «Открыть как веб-приложение» не выключайте.') : null,
        !install && android ? h('p', null, 'Меню браузера → «Добавить на главный экран».') : null,
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

      // Два текста, которые читают один раз, — одной карточкой.
      section('Данные и ответственность', [
        h('p', null, (teacherMode
          ? 'На сервер уходит выбранное имя, как оно записано в таблице колледжа. Больше ничего: '
          : 'На сервер уходят только названия вашей группы и других, если вы их выбрали. ' +
            'Больше ничего: ни имени, ') +
          'ни номера телефона, ни местоположения. Учётной записи нет, аналитики и рекламы нет, ' +
          'выбор хранится в этом браузере, а с уведомлениями — ещё и на сервере. Когда смотрите ' +
          'чужое расписание, серверу уходит, чьё именно: иначе его неоткуда взять. Всё для вашего удобства.'),
        // Уведомления — единственное, что сервер хранит о браузере;
        // перечень — как в записи на сервере.
        h('p', null, 'Пока включены уведомления, сервер хранит адрес и ключи, по которым этот браузер ' +
          'их принимает, ' + (teacherMode ? 'выбранное имя' : 'вашу группу') + ', что присылать, какой ' +
          'сайт, когда вы его открывали последний раз и когда сюда впервые дошло уведомление, а если ' +
          (teacherMode ? 'имени' : 'группы') +
          ' не станет в таблице — когда об этом сообщили. Доставляет их служба браузера — Apple, Google, ' +
          'Mozilla или Microsoft: текст ей не виден. Выключите — запись сотрётся.'),
        h('p', null, 'Что написано в таблице колледжа, то и покажет сайт: за ошибки, замены и ' +
          'опоздавшие обновления автор не отвечает.'),
        h('p', null, 'Если однажды что-то сломается, автор постарается починить, но сроков не обещает. ' +
          'Пропущенная пара остаётся на вашей совести, даже если сайт в этот момент показывал ' +
          'неправильно. Сверяйтесь с таблицей, когда это важно.'),
      ]),

      // «Я преподаватель» здесь нет: тот же переход — первой строкой списка,
      // куда ведёт «Сменить».
      section('О сайте', [
        h('p', null, 'Неофициальный сайт для студентов и преподавателей НГОК.'),
        h('p', null, externalLink('Нашли ошибку? Напишите автору в Telegram', 'https://t.me/toomonn')),
        h('p', null, h('button', { type: 'button', class: 'text-link', 'data-key': 'report', onclick: app.showReport }, 'Сведения для отчёта')),
        h('p', null, externalLink('Исходный код', 'https://github.com/tomon-one/kogda-para')),
        h('p', null, externalLink('GitHub автора', 'https://github.com/tomon-one')),
        h('p', null, h('button', { type: 'button', class: 'text-link', 'data-key': 'share', onclick: share }, 'Поделиться')),
        h('p', { class: 'muted small' }, 'Версия ' + VERSION + (CHANNEL === 'tested' ? ' tested' : '') + ', сборка ' + build()),
        h('p', { class: 'signature' }, 'Создано Tomon'),
      ])));
}
