// Вкладка «Аудитории»: какие кабинеты свободны по расписанию на паре дня,
// какие заняты и где накладки (docs/api.md, /v1/rooms). «По расписанию» —
// всерьёз: ключей, переносов и мероприятий таблица колледжа не знает.

import { h, actionButton } from './dom.js';
import { addDays, dayTitle, plural } from '../time.js';

function spinner() {
  return h('div', { class: 'centered' }, h('div', { class: 'spinner', role: 'progressbar', 'aria-label': 'Загрузка' }));
}

function minutes(clock) {
  var m = /^(\d{1,2}):(\d{2})/.exec(clock || '');
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

/** Пара по умолчанию: сегодня — идущая или следующая, иначе первая. */
function defaultPair(body, today, nowSec) {
  var numbers = Object.keys(body.bells || {}).map(Number).sort(function (a, b) { return a - b; });
  if (!numbers.length) return 1;
  if (body.date !== today) return numbers[0];
  var now = Math.floor(nowSec / 60);
  for (var i = 0; i < numbers.length; i++) {
    var end = minutes(body.bells[numbers[i]][1]);
    if (now != null && end != null && now < end) return numbers[i];
  }
  return numbers[numbers.length - 1];
}

function chip(label, on, onclick, key) {
  return h('button', { type: 'button', class: 'chip' + (on ? ' on' : ''), 'aria-pressed': on ? 'true' : 'false', 'data-key': key, onclick: onclick }, label);
}

function dayLabel(date, today) {
  if (date === today) return 'Сегодня';
  if (date === addDays(today, 1)) return 'Завтра';
  return dayTitle(date, today).split(',').slice(0, 1)[0];
}

function busyLine(room, entries) {
  return h('div', { class: 'room-busy' },
    h('span', { class: 'room-name' }, room),
    h('span', null, entries.map(function (e) {
      return e.g + ' — ' + e.s + (e.t && e.t.length ? ', ' + e.t[0] : '');
    }).join('; ')));
}

/** Сколько дней показывать: сегодня, завтра и следующий учебный — дальше колледж всё равно переставит. */
var DAYS_SHOWN = 3;
/** Сколько длится появление после смены дня или пары (motion.css, rooms-enter). */
var ENTER_MS = 260;

/** Только что сменили день или пару — то, что зависит от выбора, въезжает; перестройка продолжает с того же места. */
function entering(el, st) {
  var since = Date.now() - (st.changedAt || 0);
  if (since < ENTER_MS) {
    el.classList.add('rooms-enter');
    el.style.setProperty('--shift', -since + 'ms');
  }
  return el;
}

/** Только что выбранная кнопка загорается, а не вспыхивает. */
function chipEntering(el, st) {
  var since = Date.now() - (st.changedAt || 0);
  if (since < ENTER_MS) {
    el.classList.add('chip-enter');
    el.style.setProperty('--shift', -since + 'ms');
  }
  return el;
}

export function roomsView(app) {
  var wrap = h('div', { class: 'rooms' });
  var now = app.now();
  var st = app.state.roomsUi || (app.state.roomsUi = { date: null, pair: null, changedAt: 0 });
  var date = st.date || now.date;
  var body = app.state.rooms[date];

  if (body === undefined || body === null) {
    app.loadRooms(date, false);
    wrap.appendChild(spinner());
    return wrap;
  }
  if (body === 'failed' || body === 'missing') {
    if (body === 'missing' && st.date) {
      st.date = null;
      return roomsView(app);
    }
    wrap.appendChild(h('div', { class: 'load-failed' },
      h('p', null, body === 'missing' ? 'Сегодняшнего дня в таблице колледжа нет.' : 'Аудитории не загрузились. Проверьте интернет.'),
      body === 'failed' ? h('div', null, actionButton('Повторить', function () { app.loadRooms(date, true); })) : null));
    return wrap;
  }

  var numbers = Object.keys(body.bells || {}).map(Number).sort(function (a, b) { return a - b; });
  var days = (body.days && body.days.length ? body.days : [body.date]).slice(0, DAYS_SHOWN);
  // Поздно вечером сегодняшние пары кончились — сразу к следующему дню.
  if (!st.date && body.date === now.date && days.length > 1 && numbers.length) {
    var lastEnd = minutes((body.bells[numbers[numbers.length - 1]] || [])[1]);
    if (lastEnd != null && Math.floor(now.sec / 60) >= lastEnd) {
      st.date = days[1];
      return roomsView(app);
    }
  }

  function pick(change) {
    change();
    st.changedAt = Date.now();
    app.render();
  }

  wrap.appendChild(h('div', { class: 'chips' }, days.map(function (d) {
    var c = chip(dayLabel(d, now.date), d === body.date, function () {
      if (d === body.date) return;
      pick(function () { st.date = d; st.pair = null; });
    }, 'room-day:' + d);
    return d === body.date && d === st.date ? chipEntering(c, st) : c;
  })));

  var pair = st.pair || defaultPair(body, now.date, now.sec);
  wrap.appendChild(h('div', { class: 'chips pairs' }, numbers.map(function (n) {
    var c = chip(String(n), n === pair, function () {
      if (n === pair) return;
      pick(function () { st.pair = n; });
    }, 'room-pair:' + n);
    return n === pair && n === st.pair ? chipEntering(c, st) : c;
  })));
  var span = body.bells[pair] || [];
  wrap.appendChild(entering(h('p', { class: 'room-when' }, pair + ' пара' + (span[0] ? ', ' + span[0] + '–' + (span[1] || '') : '')), st));

  var busy = (body.busy || {})[pair] || {};
  var clashes = (body.clashes || []).filter(function (c) { return c.n === pair; });
  var picked = h('div', { class: 'rooms-picked' });

  (body.buildings || []).forEach(function (b) {
    var free = b.rooms.filter(function (r) { return !busy[r.room]; });
    var floors = {};
    free.forEach(function (r) { (floors[r.floor] = floors[r.floor] || []).push(r.room); });
    picked.appendChild(h('div', { class: 'list-card room-card' },
      h('div', { class: 'room-building' }, b.name,
        h('span', { class: 'muted' }, ' · свободно ' + free.length + ' из ' + b.rooms.length)),
      free.length ? Object.keys(floors).sort().map(function (f) {
        return h('div', { class: 'room-floor' },
          // Без этажа («55/1») — пустая подпись: кабинеты в одну колонку с остальными.
          h('span', { class: 'room-floor-label muted' }, f ? f + ' этаж' : ''),
          h('div', { class: 'room-free' }, floors[f].map(function (room) {
            return h('span', { class: 'room-chip' }, room.replace(/^Восход /, ''));
          })));
      }) : h('p', { class: 'nothing' }, 'Свободных по расписанию нет.')));
  });

  if (clashes.length) {
    picked.appendChild(h('h3', { class: 'section-title' }, 'Накладки — в одном кабинете разные пары'));
    picked.appendChild(h('div', { class: 'list-card' }, clashes.map(function (c) { return busyLine(c.room, c.lessons); })));
  }

  var taken = Object.keys(busy).sort();
  if (taken.length) {
    var list = h('div', { class: 'list-card' }, taken.map(function (room) { return busyLine(room, busy[room]); }));
    picked.appendChild(h('details', { class: 'room-taken' },
      h('summary', { class: 'section-title' }, 'Заняты — ' + taken.length),
      list));
  }
  wrap.appendChild(entering(picked, st));

  wrap.appendChild(h('p', { class: 'room-note' },
    'Свободны по расписанию: ключей, переносов и мероприятий таблица колледжа не знает.' +
    (body.unread && body.unread.length ? ' Не учтены кабинеты ' + plural(body.unread.length, 'группы', 'групп', 'групп') +
      ', у которых день не прочитан: ' + body.unread.join(', ') + '.' : '')));
  return wrap;
}
