// Приветствие при первом входе (WelcomeScreen.kt).

import { h, externalLink } from './dom.js';

var SHEET = 'https://docs.google.com/spreadsheets/d/1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc/edit';

function point(title, body) {
  return h('div', { class: 'point' }, h('h2', null, title), h('p', null, body));
}

export function welcomeScreen(app) {
  return h('div', { class: 'screen welcome' },
    h('img', { class: 'welcome-icon', src: 'assets/icon.svg', alt: '', width: '96', height: '96' }),
    h('h1', null, 'Когда пара?'),
    h('p', { class: 'welcome-sub' }, 'Расписание НГОК в браузере'),
    point('Сделано студентом', 'Для студентов и преподавателей. Колледж к сайту отношения не имеет.'),
    point('Расписание — колледжа', [
      'Оно берётся из ', externalLink('гугл таблицы расписания', SHEET),
      ' колледжа. Что написано там, то и покажет сайт.',
    ]),
    point('Сайт о вас ничего не собирает',
      'Серверу уходит только то, чьё расписание показать: группы, у преподавателя — ' +
      'имя из таблицы. Выбор хранится в этом браузере. Включите уведомления — сервер будет ' +
      'хранить ещё адрес, по которому браузер их принимает, ваш выбор и что присылать.'),
    h('button', { type: 'button', class: 'primary-button', onclick: app.finishWelcome }, 'Выбрать расписание'),
    h('p', { class: 'welcome-android' },
      'На Android есть приложение — с виджетами и напоминаниями: ',
      externalLink('скачать', '/download/latest.apk'), '.'),
    h('p', { class: 'signature' }, 'Создано Tomon'));
}
