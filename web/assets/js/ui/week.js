// Полоска дней недели под шапкой (WeekStrip в DayList.kt): неделя дня, который
// сейчас наверху списка, выделен он же, точка — сегодня. Нажатие ведёт к дню.

import { h } from './dom.js';
import { addDays, weekStart } from '../time.js';

var NAMES = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

/** `day` — с какого дня неделя (тот, к которому открыт список), `today` — дата колледжа. */
export function weekStrip(day, today, onPick) {
  var strip = h('nav', { class: 'week-strip', 'aria-label': 'Дни недели' });
  strip.__today = today;
  strip.__onPick = onPick;
  fill(strip, weekStart(day));
  mark(strip, day);
  // Экран перестроен и прокручен — выделить день, который на самом деле наверху.
  window.requestAnimationFrame(function () { mark(strip, topDay() || day); });
  return strip;
}

function fill(strip, monday) {
  strip.__monday = monday;
  while (strip.firstChild) strip.removeChild(strip.firstChild);
  NAMES.forEach(function (name, i) {
    var date = addDays(monday, i);
    var button = h('button', {
      type: 'button',
      class: 'week-day' + (date === strip.__today ? ' today' : ''),
      'data-date': date,
      'aria-label': name + ', ' + Number(date.slice(8)),
    }, h('span', { class: 'week-name' }, name), h('span', { class: 'week-num' }, String(Number(date.slice(8)))));
    button.addEventListener('click', function () { strip.__onPick(date); });
    strip.appendChild(button);
  });
}

/** Выделить день; он из другой недели — сначала перерисовать полоску на неё. */
export function mark(strip, day) {
  if (!strip || !day) return;
  var monday = weekStart(day);
  if (monday !== strip.__monday) fill(strip, monday);
  var buttons = strip.querySelectorAll('.week-day');
  for (var i = 0; i < buttons.length; i++) {
    var on = buttons[i].getAttribute('data-date') === day;
    buttons[i].classList.toggle('on', on);
    if (on) buttons[i].setAttribute('aria-current', 'date');
    else buttons[i].removeAttribute('aria-current');
  }
}

// Листают — выделение идёт за днём, который наверху под шапкой.
var pending = false;
window.addEventListener('scroll', function () {
  if (pending) return;
  pending = true;
  window.requestAnimationFrame(function () {
    pending = false;
    var strip = document.querySelector('.week-strip');
    if (strip) mark(strip, topDay());
  });
}, { passive: true });

function topDay() {
  var head = document.querySelector('.head');
  var top = head ? head.getBoundingClientRect().bottom : 0;
  var cards = document.querySelectorAll('[data-day]');
  for (var i = 0; i < cards.length; i++) {
    if (cards[i].getBoundingClientRect().bottom > top + 24) return cards[i].getAttribute('data-day');
  }
  return null;
}
