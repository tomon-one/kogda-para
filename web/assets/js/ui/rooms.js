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

export function roomsView(app) {
  var wrap = h('div', { class: 'rooms' });
  var now = app.now();
  var st = app.state.roomsUi || (app.state.roomsUi = { date: now.date, pair: null });
  var body = app.state.rooms[st.date];

  wrap.appendChild(h('h2', { class: 'retakes-title' }, 'Аудитории'));
  wrap.appendChild(h('p', { class: 'room-note' },
    'Свободны — по расписанию: ключей, переносов и мероприятий таблица колледжа не знает.'));

  if (body === undefined || body === null) {
    app.loadRooms(st.date, false);
    wrap.appendChild(spinner());
    return wrap;
  }
  if (body === 'failed' || body === 'missing') {
    if (body === 'missing' && st.date !== now.date) {
      st.date = now.date;
      return roomsView(app);
    }
    wrap.appendChild(h('div', { class: 'load-failed' },
      h('p', null, body === 'missing' ? 'Сегодняшнего дня в таблице колледжа нет.' : 'Аудитории не загрузились. Проверьте интернет.'),
      body === 'failed' ? h('div', null, actionButton('Повторить', function () { app.loadRooms(st.date, true); })) : null));
    return wrap;
  }

  var days = body.days && body.days.length ? body.days : [body.date];
  wrap.appendChild(h('div', { class: 'chips' }, days.map(function (d) {
    return chip(dayLabel(d, now.date), d === st.date, function () {
      st.date = d;
      st.pair = null;
      app.render();
    }, 'room-day:' + d);
  })));

  var numbers = Object.keys(body.bells || {}).map(Number).sort(function (a, b) { return a - b; });
  var pair = st.pair || defaultPair(body, now.date, now.sec);
  wrap.appendChild(h('div', { class: 'chips' }, numbers.map(function (n) {
    var span = body.bells[n] || [];
    return chip(n + ' пара' + (span[0] ? ' · ' + span[0] : ''), n === pair, function () {
      st.pair = n;
      app.render();
    }, 'room-pair:' + n);
  })));

  var busy = (body.busy || {})[pair] || {};
  var clashes = (body.clashes || []).filter(function (c) { return c.n === pair; });

  if (body.unread && body.unread.length) {
    wrap.appendChild(h('p', { class: 'room-note' }, 'У ' + plural(body.unread.length, 'группы', 'групп', 'групп') +
      ' день не прочитан — их кабинеты не учтены: ' + body.unread.join(', ') + '.'));
  }

  (body.buildings || []).forEach(function (b) {
    var free = b.rooms.filter(function (r) { return !busy[r.room]; });
    var floors = {};
    free.forEach(function (r) { (floors[r.floor] = floors[r.floor] || []).push(r.room); });
    var card = h('div', { class: 'list-card room-card' },
      h('div', { class: 'room-building' }, b.name,
        h('span', { class: 'muted' }, ' · свободно ' + free.length + ' из ' + b.rooms.length)),
      free.length ? Object.keys(floors).sort().map(function (f) {
        return h('div', { class: 'room-floor' },
          f ? h('span', { class: 'room-floor-label muted' }, f + ' этаж') : null,
          h('div', { class: 'room-free' }, floors[f].map(function (room) {
            return h('span', { class: 'room-chip' }, room.replace(/^Восход /, ''));
          })));
      }) : h('p', { class: 'nothing' }, 'Свободных по расписанию нет.'));
    wrap.appendChild(card);
  });

  if (clashes.length) {
    wrap.appendChild(h('h3', { class: 'section-title' }, 'Накладки — в одном кабинете разные пары'));
    wrap.appendChild(h('div', { class: 'list-card' }, clashes.map(function (c) { return busyLine(c.room, c.lessons); })));
  }

  var taken = Object.keys(busy).sort();
  if (taken.length) {
    var list = h('div', { class: 'list-card' }, taken.map(function (room) { return busyLine(room, busy[room]); }));
    wrap.appendChild(h('details', { class: 'room-taken' },
      h('summary', { class: 'section-title' }, 'Заняты — ' + taken.length),
      list));
  }
  return wrap;
}
