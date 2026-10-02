// Сервис-воркер сайта: что он показывает по пришедшему уведомлению. Запуск:
// node --test web/tests
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const SOURCE = readFileSync(new URL('../sw.js', import.meta.url), 'utf8');
const SCOPE = 'https://kogda-para-nsk.ru/';

function iso(offset) {
  const d = new Date(Date.now() + offset * 86400000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Novosibirsk' }).format(d);
}
const TODAY = iso(0);
const TOMORROW = iso(1);
const YESTERDAY = iso(-1);

/** Воркер с поддельным браузером: уведомления — в списке, окна — в другом. */
function worker() {
  const shown = [];
  const opened = [];
  const posted = [];
  const handlers = {};
  const self = {
    registration: {
      scope: SCOPE,
      showNotification(title, opts) {
        const n = { title, body: opts.body, tag: opts.tag, data: opts.data, closed: false };
        n.close = () => { n.closed = true; };
        shown.push(n);
        return Promise.resolve();
      },
      getNotifications({ tag }) {
        return Promise.resolve(shown.filter((n) => n.tag === tag && !n.closed));
      },
    },
    clients: {
      matchAll: () => Promise.resolve([]),
      openWindow: (url) => { opened.push(url); return Promise.resolve(); },
    },
    addEventListener(type, fn) { handlers[type] = fn; },
  };
  vm.runInNewContext(SOURCE, { self, URL, Intl, Date, Promise, console });
  function push(message) {
    let done;
    handlers.push({ data: { json: () => message }, waitUntil(p) { done = p; } });
    return done;
  }
  function click(n) {
    let done;
    handlers.notificationclick({ notification: n, waitUntil(p) { done = p; } });
    return done;
  }
  return { shown, opened, posted, push, click, live: () => shown.filter((n) => !n.closed) };
}

function changes(lines, who) {
  return { t: 'changes', title: 'Расписание изменилось', who,
    body: lines.map((l) => l[1]).join('\n'), days: lines.map((l) => l[0]) };
}

test('изменения прежней группы не склеиваются с изменениями новой', async () => {
  const w = worker();
  await w.push(changes([[TODAY, 'отменили 1 пару: Физика']], 'group:a'));
  await w.push(changes([[TODAY, 'убрали 2 пару: Химия']], 'group:b'));
  assert.deepEqual(w.live().map((n) => n.body), ['убрали 2 пару: Химия']);
});

test('строки про прошедшие дни не показываются как новость', async () => {
  const w = worker();
  await w.push(changes([[YESTERDAY, 'отменили 1 пару: Физика']], 'group:a'));
  assert.equal(w.live()[0].body, 'Изменения касались прошедших дней.');
});

test('лишние висящие строки уходят с дальнего дня, а не сегодняшние', async () => {
  const w = worker();
  const older = [];
  for (let i = 0; i < 4; i++) older.push([TODAY, 'сегодня ' + i]);
  for (let i = 0; i < 4; i++) older.push([TOMORROW, 'завтра ' + i]);
  await w.push(changes(older, 'group:a'));
  await w.push(changes([[TODAY, 'свежая']], 'group:a'));
  const body = w.live()[0].body.split('\n');
  assert.equal(body.length, 8);
  for (let i = 0; i < 4; i++) assert.ok(body.includes('сегодня ' + i), 'сегодня ' + i);
  assert.ok(body.includes('свежая'));
  assert.equal(body.filter((l) => l.startsWith('завтра')).length, 3);
});

test('напоминание одно: новое закрывает прежнее, кончившееся закрывается любым приходом', async () => {
  const w = worker();
  await w.push({ t: 'lesson', title: '09:00 — Физика', body: 'Каб. 101', end: Date.now() - 1000 });
  await w.push({ t: 'lesson', title: '10:40 — Химия', body: 'Каб. 102', end: Date.now() + 3600000 });
  assert.deepEqual(w.live().map((n) => n.title), ['10:40 — Химия']);
  const ended = worker();
  await ended.push({ t: 'lesson', title: '09:00 — Физика', body: 'Каб. 101', end: Date.now() - 1000 });
  await ended.push(changes([[TODAY, 'отменили 3 пару: Право']], 'group:a'));
  assert.deepEqual(ended.live().map((n) => n.tag), ['changes']);
});

test('«выберите заново» открывает сайт с подсказкой, что служба уже ждала час', async () => {
  const w = worker();
  await w.push({ t: 'gone', title: 'Группы больше нет в таблице', body: 'Откройте сайт…' });
  await w.click(w.live()[0]);
  assert.deepEqual(w.opened, [SCOPE + '?gone=1']);
  const other = worker();
  await other.push({ t: 'hello', title: 'Уведомления включены', body: '' });
  await other.click(other.live()[0]);
  assert.deepEqual(other.opened, [SCOPE]);
});
