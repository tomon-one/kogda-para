// Настройки (SettingsScreen.kt) — без того, чего у сайта нет: виджетов, работы
// в фоне и обновления приложения. Разделы: «Таблица и данные», «Группы и
// подгруппы», «Оформление», «Уведомления», «О сайте». Раздел уведомлений —
// notifications.js.

import { h, icon, actionButton, actionLink, externalLink, snackbar, standalone, isIos, isAndroid, dialog, copyText, section } from './dom.js';
import { sheetLink } from '../format.js';
import { MAX_GROUPS, shortLabels, subgroupsOf, subjectRows } from '../schedule.js';
import { groupMark } from './days.js';
import { notifications } from './notifications.js';
import { VERSION, build } from '../version.js';
import { CHANNEL } from '../store.js';
import { capitalize, viceActive, viceAvailable } from '../time.js';

function row(main, sub, buttons) {
  return h('div', { class: 'setting-row' },
    h('div', { class: 'setting-text' }, main, sub ? h('div', { class: 'muted small' }, sub) : null),
    h('div', { class: 'setting-buttons' }, buttons));
}

/**
 * Плашка «Стекла»: превью темы, название с меткой beta,
 * пояснение и выключатель; нажимается целиком.
 */
function glassToggle(app) {
  var input = h('input', { type: 'checkbox', role: 'switch', class: 'switch', 'aria-label': 'Стекло' });
  input.checked = app.glass();
  input.addEventListener('change', function () { app.setGlass(input.checked); });
  return h('label', { class: 'glass-toggle' },
    h('span', { class: 'glass-preview', 'aria-hidden': 'true' }, h('i'), h('i'), h('i')),
    h('span', { class: 'glass-text' },
      h('span', { class: 'glass-name' }, 'Стекло', h('span', { class: 'badge' }, 'beta')),
      h('span', { class: 'glass-note' },
        'Почти как Liquid Glass на iPhone.')),
    input);
}

/**
 * Плашка «Vice»: квадрат заката с галочкой, название с меткой срока и
 * пояснение; нажимается целиком. После срока плашки нет.
 */
function viceToggle(app) {
  var now = app.now();
  if (!viceAvailable(now)) return null;
  var input = h('input', { type: 'checkbox', role: 'switch', class: 'switch', 'aria-label': 'Vice' });
  input.checked = viceActive(app.vice(), now);
  input.addEventListener('change', function () { app.setVice(input.checked); });
  return h('label', { class: 'vice-toggle' },
    input,
    h('span', { class: 'vice-preview', 'aria-hidden': 'true' }, h('span', { class: 'vice-check' })),
    h('span', { class: 'vice-text' },
      h('span', { class: 'vice-name' }, 'Vice', h('span', { class: 'badge' }, 'до 1 января')),
      h('span', { class: 'vice-note' },
        'Тёплый свет города, где не бывает зимы.')));
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

/** Выключатель строкой: тот же вид, что у «Стекла», но без картинки. */
function switchLine(label, on, onchange, hint) {
  var input = h('input', { type: 'checkbox', role: 'switch', class: 'switch', 'aria-label': label });
  input.checked = on;
  input.addEventListener('change', function () { onchange(input.checked); });
  return h('label', { class: 'switch-row' },
    h('span', null, label, hint ? h('span', { class: 'switch-hint' }, hint) : null),
    input);
}

/** Таблица сокращений: своё название предмета — этому браузеру. */
function subjectsEditor(app) {
  var schedule = app.shown();
  var items = subjectRows(schedule && schedule.days);
  if (!items.length) return h('p', { class: 'muted small' }, 'Расписание ещё не загружено — предметов пока нет.');
  var custom = app.subjectShorts();
  var inputs = [];
  var save = h('button', { type: 'button', class: 'action', disabled: true }, 'Сохранить');
  var table = h('div', { class: 'subject-table' },
    h('p', { class: 'muted small' },
      'Пустое поле — название из словаря или полное. Своё видит только этот браузер и только на сайте: ' +
        'уведомления рисует служба, в них словарь.'),
    items.map(function (item) {
      var saved = '';
      item.names.forEach(function (name) { if (!saved && custom[name]) saved = custom[name]; });
      var input = h('input', {
        type: 'text', class: 'subject-input', value: saved,
        placeholder: item.sn ? capitalize(item.sn) : item.s, 'aria-label': 'Как показывать «' + item.s + '»',
      });
      // Пишем не на каждую букву: пока человек печатает, ничего не сохраняется,
      // и видно, когда записалось.
      input.addEventListener('input', function () { save.disabled = false; });
      inputs.push(input);
      return h('label', { class: 'subject-row' },
        h('span', { class: 'subject-full' }, item.s),
        input);
    }),
    save);
  save.addEventListener('click', function () {
    items.forEach(function (item, i) {
      // Вариант с пометкой зачёта — тот же предмет: своё имя получают все написания.
      item.names.forEach(function (name) { app.setSubjectShort(name, inputs[i].value); });
    });
    save.disabled = true;
    snackbar('Сохранено');
  });
  return table;
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

  // Подгруппы своей группы — какие есть в списке: им и место под значок.
  var subs = !teacherMode && own ? subgroupsOf(own.name, app.state.groups || []) : [];
  // Значок, повторяющий название целиком, только сжимал бы его — такой не
  // ставится ни своей группе, ни другим.
  var ownMark = marks && own && marks[0] !== own.name ? marks[0] : null;
  // Слот одной ширины под значок: с ним и без него название начинается там же,
  // и добавленная подгруппа не сдвигает названия. Есть он, только если значок
  // вообще бывает: у самого названия или у подгрупп.
  var withSlot = !!marks || subs.length > 0;
  function nameCell(mark, ownMarked, text) {
    return h('span', { class: 'group-name' },
      withSlot ? h('span', { class: 'mark-slot' }, mark ? groupMark(mark, ownMarked) : null) : null,
      h('span', { class: 'group-name-text' }, text));
  }
  var group = [row(
    nameCell(ownMark, true, own ? own.name : 'не выбрано'),
    null,
    actionButton(teacherMode ? 'Выбрать заново' : 'Сменить', function () { app.go(teacherMode ? 'pick/self' : 'pick'); }, 'fixed'))];
  if (!teacherMode) {
    // Подгруппы своей группы — каждая своей строкой на своём месте: «Добавить»
    // в ней сменяется на «Убрать», и ничего не переезжает и не появляется
    // заново в списке доступных.
    var added = {};
    extras.forEach(function (g, i) { added[g.id] = i; });
    // Сколько ещё влезает: строк «Добавить» сверх этого нет.
    var room = MAX_GROUPS - 1 - extras.length;
    subs.forEach(function (g) {
      var i = added[g.id];
      var mark = marks && i != null && marks[i + 1] !== g.name ? marks[i + 1] : null;
      if (i != null) {
        group.push(row(nameCell(mark, false, g.name), 'подгруппа вашей группы',
          actionButton('Убрать', function () { app.removeExtra(g.id); }, 'fixed', 'remove:' + g.id, 'Убрать ' + g.name)));
        return;
      }
      if (!room) return;
      room--;
      group.push(row(nameCell(null, false, g.name), 'подгруппа вашей группы',
        actionButton('Добавить', function () { app.addExtras([g]); }, 'fixed', 'add:' + g.id, 'Добавить ' + g.name)));
    });
    // Остальные группы — любые, до шести вместе со своей; они идут после
    // подгрупп, и добавление подгруппы их не сдвигает.
    extras.forEach(function (g, i) {
      if (subs.some(function (s) { return s.id === g.id; })) return;
      group.push(row(
        nameCell(marks && marks[i + 1] !== g.name ? marks[i + 1] : null, false,
          g.name + (g.gone ? ' — нет в таблице' : '')),
        null,
        // Несколько одинаковых «Убрать» — чтецу с названием.
        actionButton('Убрать', function () { app.removeExtra(g.id); }, 'fixed', 'remove:' + g.id, 'Убрать ' + g.name)));
    });
    if (room > 0) {
      group.push(h('div', { class: 'setting-actions' },
        actionButton(extras.length || subs.length ? 'Добавить другую группу' : 'Добавить группу',
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

  // Своё у сайта и о сайте — в последней карточке: значок и сборка для Android.
  // Только во вкладке браузера: открытому значком он ни к чему. Есть кнопка
  // браузера — она вместо шагов; шаги — для этого телефона, а на компьютере без
  // кнопки раздела нет.
  var installBlock = standalone() || (!install && !ios && !android) ? null : [
    h('div', { class: 'setting-sub' }, 'Значок на экране'),
    h('p', null, 'Сайт можно открыть значком с домашнего экрана, как приложение.'),
    install ? actionButton('Добавить значок', app.install) : null,
    // На iOS 26 кнопки «Поделиться» на панели по умолчанию нет — она в
    // меню «•••» у адреса.
    !install && ios ? h('p', null, 'В Safari: кнопка «Поделиться» — на панели или в меню «•••» у ' +
      'адреса → «На экран „Домой“»; «Открыть как веб-приложение» не выключайте.') : null,
    !install && android ? h('p', null, 'Меню браузера → «Добавить на главный экран».') : null,
  ];
  // На айфоне его не поставить.
  var androidBlock = ios ? null : [
    h('div', { class: 'setting-sub' }, 'Приложение для Android'),
    h('p', null, 'Виджеты на домашнем экране, напоминания о парах и уведомления об отменах и заменах.'),
    actionLink('Скачать', '/download/latest.apk'),
    h('p', { class: 'small' }, externalLink('Как поставить',
      'https://github.com/tomon-one/kogda-para/blob/master/docs/install.md')),
  ];

  return h('div', { class: 'screen settings' },
    h('header', { class: 'topbar' },
      h('button', { type: 'button', class: 'icon-button', 'aria-label': 'Назад', onclick: app.back }, icon('back')),
      h('h1', { class: 'topbar-title' }, 'Настройки')),
    h('main', { class: 'content cards' },
      // Откуда расписание — первое, что ищут в настройках, и одна короткая
      // фраза о том, что уходит на сервер: остальное читают один раз.
      section('Таблица и данные', [
        // Дня нет в ответе (воскресенье, каникулы) — ссылка ведёт на ближайший
        // следующий; у преподавателя — колонка группы первой пары, без пар —
        // только строка дня (sheetLink).
        h('p', null, 'Сайт забирает расписание при открытии и раз в час, пока страница открыта; ' +
          'сервер читает таблицу колледжа чаще и перед каждой парой.'),
        link ? actionLink('Открыть таблицу колледжа', link)
          : actionButton('Открыть таблицу колледжа', function () { snackbar('Адрес таблицы ещё не получен от сервера'); }),
        h('p', null, teacherMode
          ? 'На сервер уходит выбранное имя, как оно записано в таблице, — больше ничего.'
          : 'На сервер уходят только названия вашей группы и других, если вы их выбрали, — больше ничего.'),
        h('p', null, 'Что написано в таблице колледжа, то и покажет сайт.'),
      ]),

      section(teacherMode ? 'Преподаватель' : 'Группы и подгруппы', group),

      section('Оформление', [
        segmented([['Системная', 'system'], ['Тёмная', 'dark'], ['Светлая', 'light']], app.theme(), app.setTheme),
        // «Vice» и «Стекло» вместе не работают: при включённой «Vice» плашки
        // стекла нет, а его флаг в хранилище не трогается.
        viceToggle(app),
        viceActive(app.vice(), app.now()) ? null : glassToggle(app),
        switchLine('Компактный вид', app.compact(), app.setCompact,
          'Тесные карточки: время и место с преподавателем в одну строку. ' +
          'Включается сам, если экран у́же 360 px.'),
        switchLine('Короткие названия', app.shortSubjects(), app.setShortSubjects,
          '«Алгоритмы» вместо длинного названия. Своё можно написать в таблице сокращений.'),
        h('div', { class: 'setting-sub' }, 'Таблица сокращений'),
        subjectsEditor(app),
      ]),

      own ? notifications(app, teacherMode) : null,

      // «Я преподаватель» здесь нет: тот же переход — первой строкой списка,
      // куда ведёт «Сменить».
      section('О сайте', [
        h('p', null, 'Неофициальный сайт для студентов и преподавателей НГОК.'),
        installBlock,
        androidBlock,
        h('p', null, externalLink('Новости в Telegram', 'https://t.me/kogda_para_news')),
        h('p', null, externalLink('Новости во ВКонтакте', 'https://vk.com/kogda_para_nsk')),
        h('p', null, externalLink('Нашли ошибку? Напишите автору в Telegram', 'https://t.me/toomonn')),
        h('p', null, h('button', { type: 'button', class: 'text-link', 'data-key': 'report', onclick: app.showReport }, 'Сведения для отчёта')),
        h('p', null, externalLink('Исходный код', 'https://github.com/tomon-one/kogda-para')),
        h('p', null, externalLink('GitHub автора', 'https://github.com/tomon-one')),
        h('p', null, h('button', { type: 'button', class: 'text-link', 'data-key': 'share', onclick: share }, 'Поделиться')),
        h('p', { class: 'muted small' }, 'Версия ' + VERSION + (CHANNEL === 'tested' ? ' tested' : '') + ', сборка ' + build()),
        h('p', { class: 'signature' }, 'Создано Tomon'),
        h('p', { class: 'signature' }, 'Собрано задолго до рассвета'),
      ])));
}
