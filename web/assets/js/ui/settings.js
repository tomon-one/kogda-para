// Настройки (SettingsScreen.kt) — без того, чего у сайта нет: виджетов, работы
// в фоне, уведомлений и обновления приложения.

import { h, icon, actionButton, actionLink, externalLink, snackbar } from './dom.js';
import { sheetLink } from '../format.js';
import { MAX_GROUPS, subgroupsOf } from '../schedule.js';
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

export function settingsScreen(app) {
  var teacherMode = app.isTeacher();
  var own = app.chosen();
  var extras = app.extras();
  var byName = app.groupsByName();
  // Номера у групп — только когда пары ими и подписаны: названия и так в строке.
  var numbered = !teacherMode && extras.length > 0 && !byName;

  // С другими группами — номера, как у значков под временем пар; своя — 1.
  var group = [row(
    h('span', { class: 'group-name' }, numbered ? groupMark(1, true) : null, own ? own.name : 'не выбрано'),
    null,
    actionButton(teacherMode ? 'Выбрать заново' : 'Сменить', function () { app.go(teacherMode ? 'pick/self' : 'pick'); }, 'fixed'))];
  if (!teacherMode) {
    // Остальные группы — любые, до шести вместе со своей (Tomon 28.09).
    extras.forEach(function (g, i) {
      group.push(row(
        h('span', { class: 'group-name' }, numbered ? groupMark(i + 2, false) : null, g.name + (g.gone ? ' — нет в таблице' : '')),
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

  return h('div', { class: 'screen settings' },
    h('header', { class: 'topbar' },
      h('button', { type: 'button', class: 'icon-button', 'aria-label': 'Назад', onclick: app.back }, icon('back')),
      h('h1', { class: 'topbar-title' }, 'Настройки')),
    h('main', { class: 'content cards' },
      section(teacherMode ? 'Преподаватель' : 'Группа', group),

      section('Оформление', segmented(
        [['Системная', 'system'], ['Тёмная', 'dark'], ['Светлая', 'light']],
        app.theme(), app.setTheme)),

      section('Расписание', [
        h('p', null, 'Сайт забирает его при открытии и раз в час, пока страница открыта; сервер читает ' +
          'таблицу колледжа чаще и перед каждой парой. Кнопка ниже ' +
          (teacherMode ? 'откроет таблицу на сегодняшнем дне, у колонки группы первой пары.'
            : 'откроет таблицу на вашей колонке и сегодняшнем дне.')),
        link ? actionLink('Открыть таблицу колледжа', link)
          : actionButton('Открыть таблицу колледжа', function () { snackbar('Адрес таблицы ещё не получен от сервера'); }),
      ]),

      section('Значок на экране', [
        h('p', null, 'Сайт можно открыть значком с домашнего экрана, как приложение.'),
        h('p', null, 'iPhone и iPad: в Safari «Поделиться» → «На экран „Домой“».'),
        h('p', null, 'Android: меню браузера → «Добавить на главный экран».'),
        install ? actionButton('Добавить значок', app.install) : null,
      ]),

      section('Данные', h('p', null, (teacherMode
        ? 'На сервер уходит выбранное имя, как оно записано в таблице колледжа. Больше ничего: '
        : 'На сервер уходят только названия вашей группы и других, если вы их выбрали. ' +
          'Больше ничего: ни имени, ') +
        'ни номера телефона, ни местоположения. Учётной записи нет, аналитики и рекламы нет, ' +
        'выбор хранится только в этом браузере. Когда смотрите чужое расписание, серверу уходит, ' +
        'чьё именно: иначе его неоткуда взять. Всё для вашего удобства.')),

      section('Ответственность', [
        h('p', null, 'Что написано в таблице колледжа, то и покажет сайт: за ошибки, замены и ' +
          'опоздавшие обновления автор не отвечает.'),
        h('p', null, 'Если однажды что-то сломается, автор постарается починить, но сроков не обещает. ' +
          'Пропущенная пара остаётся на вашей совести, даже если сайт в этот момент показывал ' +
          'неправильно. Сверяйтесь с таблицей, когда это важно.'),
      ]),

      section('Приложение для Android', [
        h('p', null, 'Виджеты на домашнем экране, напоминания о парах и уведомления об отменах и заменах.'),
        actionLink('Скачать', '/download/latest.apk'),
        h('p', { class: 'small' }, externalLink('Как поставить',
          'https://github.com/tomon-one/kogda-para/blob/master/docs/install.md')),
      ]),

      section('О сайте', [
        h('p', null, 'Неофициальный сайт для студентов и преподавателей НГОК.'),
        h('p', null, externalLink('Нашли ошибку? Напишите автору в Telegram', 'https://t.me/toomonn')),
        h('p', null, h('button', { type: 'button', class: 'text-link', 'data-key': 'report', onclick: app.showReport }, 'Сведения для отчёта')),
        h('p', null, externalLink('Исходный код', 'https://github.com/tomon-one/kogda-para')),
        h('p', null, externalLink('GitHub автора', 'https://github.com/tomon-one')),
        h('p', null, h('button', {
          type: 'button', class: 'text-link',
          onclick: function () { app.go(teacherMode ? 'pick' : 'pick/self'); },
        }, teacherMode ? 'Я студент' : 'Я преподаватель')),
        h('p', { class: 'muted small' }, 'Версия ' + VERSION + (CHANNEL === 'tested' ? ' tested' : '') + ', сборка ' + build()),
        h('p', { class: 'signature' }, 'Создано Tomon'),
      ])));
}
