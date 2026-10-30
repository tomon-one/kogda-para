// Дни расписания карточками — общий вид для своего и чужого расписания
// (DayCard в DayList.kt, LessonRow.kt).

import { h, actionLink, snackbar, copyText } from './dom.js';
import { dayTitle, capitalize } from '../time.js';
import { currentLessonNumber, dayBells, daySummary, daysWithGaps, freeDay, freeOwnDay, lessonTime, onlyAt, shortLabels, unreadNotes } from '../schedule.js';
import {
  isCancelled, isKnownWebinar, isOnline, isWebLink, kindName, linkEnd, linkHost, onlineLabel,
  roomLabel, shortenName,
} from '../format.js';

/**
 * Список карточек. `now` — {date, sec} по часам колледжа; `before` — что
 * вставить перед днём `beforeDay` (плашки сбоя — рядом с сегодняшним днём,
 * куда страница и прокручивается).
 */
export function dayCards(schedule, now, before, beforeDay, byName) {
  var days = daysWithGaps(schedule);
  var teacher = schedule.kind === 'teacher';
  var placed = false;
  var list = h('div', { class: 'days' });
  days.forEach(function (day, i) {
    if (before && !placed && beforeDay && day.d >= beforeDay) {
      list.appendChild(before);
      placed = true;
    }
    var nextFree = freeOwnDay(days[i + 1], schedule.groupNames || []);
    list.appendChild(dayCard(day, dayBells(schedule, day), now, teacher, schedule.groupNames || [], byName !== false, nextFree));
  });
  if (before && !placed) {
    if (days.length && beforeDay && days[days.length - 1].d < beforeDay) list.appendChild(before);
    else list.insertBefore(before, list.firstChild);
  }
  return list;
}

/** Строка карточки «Сегодня» 2 апреля (как в приложении, `DayList.kt`). */
var BIRTHDAY = '🎂 Сегодня у автора день рождения. Он не вправе отменить пары, но очень хотел бы';

var HOLIDAYS = {
  '01-07': '⭐ С Рождеством!',
  '02-23': '🎖️ С Днём защитника Отечества!',
  '03-08': '🌷 С 8 Марта!',
  '05-01': '🌱 С Первомаем!',
  '05-09': '🕊️ С Днём Победы!',
  '06-12': '🇷🇺 С Днём России!',
  '11-04': '🤝 С Днём народного единства!',
};

/** Поздравление карточки «Сегодня» в праздник (как в приложении, `DayList.kt`). */
export function holidayNote(date) {
  var day = date.slice(5);
  if (HOLIDAYS[day]) return HOLIDAYS[day];
  return day >= '01-01' && day <= '01-08' ? '🎄 С Новым годом!' : null;
}

function dayCard(day, bells, now, teacher, groups, byName, nextFree) {
  var isToday = day.d === now.date;
  var past = day.d < now.date;
  var current = isToday ? currentLessonNumber(bells, day.d, now) : null;
  var lessons = day.l || [];
  var card = h('section', {
    class: 'day' + (isToday ? ' today' : '') + (past ? ' past' : ''),
    'data-day': day.d,
  }, h('h2', { class: 'day-title' }, dayTitle(day.d, now.date),
    summaryLine(daySummary(lessons, groups, bells))));

  var notes = unreadNotes(day);
  if (isToday && !teacher && day.d.slice(5) === '04-02') notes = notes.concat(BIRTHDAY);
  if (isToday && holidayNote(day.d)) notes = notes.concat(holidayNote(day.d));
  notes.forEach(function (note) {
    card.appendChild(h('p', { class: 'day-note' }, note));
  });
  // Свой день не прочитан, и пар нет — это не «пар нет».
  var unknown = day.un === 'missing';
  if (!lessons.length) {
    if (!unknown) card.appendChild(h('p', { class: 'day-empty' }, freeDay(day, teacher, nextFree, now)));
    return card;
  }
  // Своих пар нет, а у выбранных групп есть: это уже сказала сводка под датой
  // («Пары только у …»), у самих пар подпись не повторяем. Отменённые сводка
  // не считает — у них подпись остаётся.
  var allForeign = groups.length > 0 && lessons.every(function (l) { return isForeign(l, groups); });
  lessons.forEach(function (lesson, index) {
    // Пара только у других групп — не «идёт сейчас»: человек на ней не сидит.
    var foreign = isForeign(lesson, groups);
    // С порядком: у одного номера бывают две пары — подгруппы в разных кабинетах.
    var key = day.d + ':' + lesson.n + ':' + (lesson.gr || (lesson.slots || []).join('-')) + ':' + index;
    card.appendChild(lessonRow(lesson, bells, lesson.n === current && !isCancelled(lesson) && !foreign, key, groups, byName, allForeign && !isCancelled(lesson)));
  });
  // Считаются только свои пары.
  var own = groups.length ? lessons.filter(function (l) { return !isForeign(l, groups); }) : lessons;
  if (own.length && own.every(isCancelled)) {
    // Преподавателю отмена всех пар — сорванные часы, не удача.
    card.appendChild(h('p', { class: 'day-note' }, teacher ? 'Все пары отменены' : 'Всё отменили. Повезло'));
  }
  return card;
}

/** «09:00–10:30»: начало крупно, конец под ним. */
function timeBlock(time) {
  var parts = time.split('–');
  return h('div', { class: 'time', 'aria-label': time },
    h('span', { class: 'time-start' }, parts[0]),
    parts[1] ? h('span', { class: 'time-end' }, parts[1]) : null);
}

function summaryLine(text) {
  return text ? h('span', { class: 'day-summary' }, text) : null;
}

function isForeign(lesson, groups) {
  return groups.length > 0 && (lesson.slots || []).indexOf(0) < 0;
}

function lessonRow(lesson, bells, isNow, key, groups, byName, saidAbove) {
  // Пара только у других выбранных групп: её видно сразу, а не только по
  // значкам — серый фон, приглушённый текст и подпись.
  var foreign = isForeign(lesson, groups);
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
    // Чья пара — первой строкой: сначала видно, что она не своя.
    foreign && !saidAbove ? h('div', { class: 'sub only-at' }, onlyAt(lesson.slots, groups)) : null,
    h('div', { class: 'subject' + (isCancelled(lesson) ? ' cancelled' : '') }, lesson.s),
    h('div', { class: 'lesson-meta' }, place),
    lesson.u ? onlineLink(lesson.u, key) : null,
    // Подпись группы — вместо преподавателя: у преподавателя важно, кому
    // читается пара. Чья пара у выбранных групп — видно по значкам.
    // Тип занятия — через точку после последней подписи: рядом с длинным местом
    // он висел бы посреди его строк.
    whoLines(lesson.gr != null ? [lesson.gr] : (lesson.t || []).map(shortenName), kind)
      .map(function (line) { return h('div', { class: 'sub' }, line); }),
    note(lesson));
  return h('div', { class: 'lesson' + (isNow ? ' now' : '') + (foreign ? ' foreign' : '') },
    h('div', { class: 'lesson-time' + (groups.length ? ' with-marks' : '') },
      h('div', { class: 'pair' }, lesson.n + ' пара'),
      time ? timeBlock(time) : null,
      isNow ? nowLabel() : null,
      groups.length ? groupMarks(lesson.slots || [], groups, byName) : null),
    body);
}

/** Подписи под парой; [kind] — тип занятия через точку после последней. */
function whoLines(who, kind) {
  if (!kind) return who;
  if (!who.length) return [kind];
  return who.slice(0, -1).concat(who[who.length - 1] + ' · ' + kind);
}

/**
 * Значки групп под временем (GroupMarks в GroupMarks.kt) — только тех, у кого
 * пара есть (пустые рамки путают): своя — закрашенным, другие — бледным.
 * Подпись — название (shortLabels) или номер: место в настройках, 1 — своя.
 * Номера — по три в ряд, названия — сколько влезет.
 */
function groupMarks(slots, groups, byName) {
  var whose = slots.slice().sort().map(function (i) { return groups[i]; }).filter(Boolean);
  var labels = byName ? shortLabels(groups) : groups.map(function (g, i) { return String(i + 1); });
  var box = h('div', { class: 'marks' + (byName ? ' names' : ''), role: 'img', 'aria-label': 'Пара у групп: ' + whose.join(', ') });
  labels.forEach(function (label, i) {
    if (slots.indexOf(i) >= 0) box.appendChild(groupMark(label, i === 0, groups[i]));
  });
  return box;
}

/** Один значок. `name` — полное название во всплывающей подсказке: длинное в значке обрезано. */
export function groupMark(label, own, name) {
  return h('span', { class: 'mark' + (own ? ' own' : ''), 'aria-hidden': 'true', title: name || null }, String(label));
}

// Точка у «идёт сейчас» дышит по часам страницы, а не с момента, когда строка
// нарисована: экран перестраивается (пришло расписание, звонок, звезда), и
// новая точка продолжает с той же фазы, а не вспыхивает заново. Период — как
// у анимации pulse в motion.css.
var PULSE_MS = 2400;

function nowLabel() {
  var el = h('div', { class: 'now-label' }, 'идёт');
  var now = window.performance && performance.now ? performance.now() : Date.now();
  el.style.setProperty('--pulse-shift', -(now % PULSE_MS) + 'ms');
  return el;
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
  // Адрес — только чужой, как предупреждение; у площадки колледжа хватает кнопок.
  return h('div', { class: 'online-link' },
    known ? null : h('div', { class: 'link-where foreign' },
      'чужой адрес: ',
      h('span', { class: 'link-host' }, linkHost(url)),
      // Обычный пробел: перенос — между хостом и хвостом, а не посреди хоста.
      end ? ' ' : null,
      end ? h('span', { class: 'link-end' }, '· …/' + end) : null),
    h('div', { class: 'link-buttons' },
      isWebLink(url) ? keyed(actionLink('Открыть', url, 'link-button open'), 'open-link:' + key) : null,
      copy));
}
