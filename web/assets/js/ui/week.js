// Полоска дней недели под шапкой (WeekStrip в DayList.kt): неделя дня, который
// сейчас наверху списка, выделен он же, точка — сегодня. Нажатие ведёт к дню.

import { h } from './dom.js';
import { addDays, weekStart } from '../time.js';

var NAMES = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

/** `day` — с какого дня неделя (тот, к которому открыт список), `today` — дата колледжа. */
export function weekStrip(day, today) {
  var strip = h('nav', { class: 'week-strip', 'aria-label': 'Дни недели' });
  strip.__today = today;
  fill(strip, weekStart(day));
  mark(strip, picked || day);
  // Экран перестроен и прокручен — выделить день, который на самом деле наверху.
  window.requestAnimationFrame(function () { mark(strip, picked || topDay() || day); });
  return strip;
}

function fill(strip, monday) {
  strip.__monday = monday;
  while (strip.firstChild) strip.removeChild(strip.firstChild);
  // Выделение — одна плашка, она переезжает от дня к дню (motion.css).
  strip.appendChild(h('span', { class: 'week-pill', 'aria-hidden': 'true' }));
  NAMES.forEach(function (name, i) {
    var date = addDays(monday, i);
    var button = h('button', {
      type: 'button',
      class: 'week-day' + (date === strip.__today ? ' today' : ''),
      'data-date': date,
      'aria-label': name + ', ' + Number(date.slice(8)),
    }, h('span', { class: 'week-name' }, name), h('span', { class: 'week-num' }, String(Number(date.slice(8)))));
    button.addEventListener('click', function () { go(strip, date); });
    strip.appendChild(button);
  });
}

/** Выделить день; он из другой недели — сначала перерисовать полоску на неё. */
export function mark(strip, day) {
  if (!strip || !day) return;
  var monday = weekStart(day);
  if (monday !== strip.__monday) fill(strip, monday);
  var buttons = strip.querySelectorAll('.week-day');
  var index = -1;
  for (var i = 0; i < buttons.length; i++) {
    var on = buttons[i].getAttribute('data-date') === day;
    if (on) index = i;
    buttons[i].classList.toggle('on', on);
    if (on) buttons[i].setAttribute('aria-current', 'date');
    else buttons[i].removeAttribute('aria-current');
  }
  strip.style.setProperty('--i', String(Math.max(index, 0)));
  strip.classList.toggle('marked', index >= 0);
}

// Нажатый день держит выделение, пока человек сам не поведёт страницу: иначе
// день у конца списка (его не докрутить до верха) отдавал выделение соседу.
var picked = null;

/** К дню — плавной прокруткой (без неё, если в системе «уменьшить движение»). */
function go(strip, date) {
  var cards = document.querySelectorAll('[data-day]');
  var card = null;
  for (var i = 0; i < cards.length && !card; i++) if (cards[i].getAttribute('data-day') >= date) card = cards[i];
  card = card || cards[cards.length - 1];
  if (!card) return;
  var y = card.getBoundingClientRect().top + window.pageYOffset - headBottom() - 8;
  var calm = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  picked = date;
  mark(strip, date);
  try {
    window.scrollTo({ top: Math.max(0, y), behavior: calm ? 'auto' : 'smooth' });
  } catch (e) {
    window.scrollTo(0, Math.max(0, y));
  }
}

function release() { picked = null; }
window.addEventListener('touchstart', release, { passive: true });
window.addEventListener('wheel', release, { passive: true });
window.addEventListener('keydown', release);

// Листают — выделение идёт за днём, который наверху.
var pending = false;
window.addEventListener('scroll', function () {
  if (pending || picked) return;
  pending = true;
  window.requestAnimationFrame(function () {
    pending = false;
    var strip = document.querySelector('.week-strip');
    if (strip && !picked) mark(strip, topDay());
  });
}, { passive: true });

function headBottom() {
  var head = document.querySelector('.head');
  return head ? head.getBoundingClientRect().bottom : 0;
}

/**
 * День наверху — тот, чья карточка на четверти видимой части под шапкой: по
 * самому краю шапки выделение перескакивало от каждого миллиметра прокрутки.
 */
function topDay() {
  var top = headBottom();
  var probe = top + (window.innerHeight - top) / 4;
  var cards = document.querySelectorAll('[data-day]');
  var last = null;
  for (var i = 0; i < cards.length; i++) {
    var rect = cards[i].getBoundingClientRect();
    if (rect.top > probe) break;
    last = cards[i];
    if (rect.bottom > probe) break;
  }
  return last ? last.getAttribute('data-day') : (cards[0] ? cards[0].getAttribute('data-day') : null);
}
