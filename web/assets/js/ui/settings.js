// Настройки (SettingsScreen.kt) — без того, чего у сайта нет: виджетов, работы
// в фоне, уведомлений и обновления приложения.

import { h, icon, actionButton, actionLink, externalLink, snackbar } from './dom.js';
import { sheetLink } from '../format.js';
import { VERSION } from '../version.js';
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
  return h('div', { class: 'segmented', role: 'radiogroup' }, options.map(function (o) {
    var on = o[1] === selected;
    return h('button', {
      type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false',
      class: 'segment' + (on ? ' on' : ''), onclick: function () { onPick(o[1]); },
    }, o[0]);
  }));
}

export function settingsScreen(app) {
  var teacherMode = app.isTeacher();
  var own = app.chosen();
  var sub = app.second();

  var group = [row(own ? own.name : 'не выбрано', null,
    actionButton(teacherMode ? 'Выбрать заново' : 'Сменить', function () { app.go(teacherMode ? 'pick/self' : 'pick'); }, 'fixed'))];
  if (!teacherMode) {
    group.push(row(
      h('span', null, 'Подгруппа ', h('span', { class: 'beta' }, 'beta')),
      sub ? sub.name + (app.secondGone() ? ' — нет в таблице' : '') : 'не выбрана',
      sub ? [
        actionButton('Заменить', function () { app.go('pick/second'); }, 'fixed'),
        actionButton('Убрать', function () { app.pick('second', null); }, 'fixed'),
      ] : actionButton('Добавить', function () { app.go('pick/second'); }, 'fixed')));
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
        : 'На сервер уходит только название вашей группы — и подгруппы, если вы её выбрали. ' +
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
        h('p', null, h('button', { type: 'button', class: 'text-link', onclick: app.showReport }, 'Сведения для отчёта')),
        h('p', null, externalLink('Исходный код', 'https://github.com/tomon-one/kogda-para')),
        h('p', null, externalLink('GitHub автора', 'https://github.com/tomon-one')),
        h('p', null, h('button', {
          type: 'button', class: 'text-link',
          onclick: function () { app.go(teacherMode ? 'pick' : 'pick/self'); },
        }, teacherMode ? 'Я студент' : 'Я преподаватель')),
        h('p', { class: 'muted small' }, 'Версия ' + VERSION + (CHANNEL === 'tested' ? ' tested' : '')),
        h('p', { class: 'signature' }, 'Создано Tomon'),
      ])));
}
