// Дни расписания карточками — общий вид для своего и чужого расписания
// (DayCard и LessonRow в TodayScreen.kt).

import { h, actionLink, snackbar, copyText } from './dom.js';
import { dayTitle, capitalize } from '../time.js';
import { currentLessonNumber, daysWithGaps, freeDay, lessonTime } from '../schedule.js';
import {
  isCancelled, isKnownWebinar, isOnline, isWebLink, kindName, linkEnd, linkHost, onlineLabel,
  roomLabel, shortenName,
} from '../format.js';

/**
 * Список карточек. `now` — {date, sec} по часам колледжа; `before` — что
 * вставить перед днём `beforeDay` (плашки сбоя — рядом с сегодняшним днём,
 * куда страница и прокручивается).
 */
export function dayCards(schedule, now, before, beforeDay) {
  var days = daysWithGaps(schedule);
  var teacher = schedule.kind === 'teacher';
  var placed = false;
  var list = h('div', { class: 'days' });
  days.forEach(function (day) {
    if (before && !placed && beforeDay && day.d >= beforeDay) {
      list.appendChild(before);
      placed = true;
    }
    list.appendChild(dayCard(day, schedule.bells || {}, now, teacher));
  });
  if (before && !placed) {
    if (days.length && beforeDay && days[days.length - 1].d < beforeDay) list.appendChild(before);
    else list.insertBefore(before, list.firstChild);
  }
  return list;
}

function dayCard(day, bells, now, teacher) {
  var isToday = day.d === now.date;
  var past = day.d < now.date;
  var current = isToday ? currentLessonNumber(bells, day.d, now) : null;
  var lessons = day.l || [];
  var card = h('section', {
    class: 'day' + (isToday ? ' today' : '') + (past ? ' past' : ''),
    'data-day': day.d,
  }, h('h3', { class: 'day-title' }, dayTitle(day.d, now.date)));

  if (!lessons.length) {
    card.appendChild(h('p', { class: 'day-empty' }, freeDay(day)));
    return card;
  }
  lessons.forEach(function (lesson) {
    card.appendChild(lessonRow(lesson, bells, lesson.n === current && !isCancelled(lesson), day.d + ':' + lesson.n + ':' + (lesson.gr || '')));
  });
  if (lessons.every(isCancelled)) {
    // Преподавателю отмена всех пар — сорванные часы, не удача.
    card.appendChild(h('p', { class: 'day-note' }, teacher ? 'Все пары отменены' : 'Всё отменили. Повезло'));
  }
  return card;
}

function lessonRow(lesson, bells, isNow, key) {
  var time = lessonTime(bells, lesson.n);
  var place;
  if (isOnline(lesson)) {
    place = h('span', { class: 'place' }, capitalize(onlineLabel(lesson)));
  } else {
    // Ни кабинета, ни ссылки — так и говорим: пустая строка читается как «не загрузилось».
    var room = roomLabel(lesson.r);
    place = h('span', { class: 'place' + (room ? '' : ' muted') }, room || 'Не указано');
  }
  var kind = kindName(lesson.k);
  var body = h('div', { class: 'lesson-body' },
    h('div', { class: 'subject' + (isCancelled(lesson) ? ' cancelled' : '') }, lesson.s),
    h('div', { class: 'lesson-meta' }, place, kind ? h('span', { class: 'kind' }, kind) : null),
    lesson.u ? onlineLink(lesson.u, key) : null,
    // Подпись группы — вместо преподавателя: у преподавателя важно, кому
    // читается пара, у пары второй подгруппы — чья она.
    lesson.gr != null
      ? h('div', { class: 'sub' }, lesson.gr)
      : (lesson.t || []).map(function (t) { return h('div', { class: 'sub' }, shortenName(t)); }),
    note(lesson));
  return h('div', { class: 'lesson' + (isNow ? ' now' : '') },
    h('div', { class: 'lesson-time' },
      h('div', { class: 'pair' }, lesson.n + ' пара'),
      time ? h('div', { class: 'time' }, time) : null,
      isNow ? h('div', { class: 'now-label' }, 'идёт сейчас') : null),
    body);
}

function keyed(el, key) {
  el.setAttribute('data-key', key);
  return el;
}

function note(lesson) {
  if (isCancelled(lesson)) {
    return h('div', { class: 'cancel-note' }, lesson.c ? 'Отменена — ' + lesson.c : 'Отменена');
  }
  // У замены — «Вместо: Математика»: без неё новая пара выглядела бы ошибкой таблицы.
  return lesson.c ? h('div', { class: 'sub' }, capitalize(lesson.c)) : null;
}

/**
 * Ссылка на занятие: куда ведёт и две кнопки. Хост не с площадки вебинаров
 * колледжа — красным: ссылку мог вписать кто угодно, кто правит таблицу.
 */
function onlineLink(url, key) {
  var known = isKnownWebinar(url);
  var end = linkEnd(url);
  var copy = h('button', { type: 'button', class: 'link-button', 'data-key': 'copy:' + key }, 'Копировать');
  copy.addEventListener('click', function () {
    copyText(url).then(function (ok) {
      snackbar(ok ? 'Ссылка скопирована' : 'Скопировать не вышло');
    });
  });
  return h('div', { class: 'online-link' },
    h('div', { class: 'link-where' + (known ? '' : ' foreign') },
      h('span', { class: 'link-host' }, (known ? '' : 'чужой адрес: ') + linkHost(url)),
      // Обычный пробел: перенос — между хостом и хвостом, а не посреди хоста.
      end ? ' ' : null,
      end ? h('span', { class: 'link-end' }, '· …/' + end) : null),
    h('div', { class: 'link-buttons' },
      isWebLink(url) ? keyed(actionLink('Открыть', url, 'link-button'), 'open-link:' + key) : null,
      copy));
}
