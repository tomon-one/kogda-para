// Обновление расписания против поддельного сервера: когда качать, когда
// «уже свежее», пропажа группы через час, «не отвечает» через полчаса.
import test from 'node:test';
import assert from 'node:assert/strict';

import * as repo from '../assets/js/repo.js';
import * as store from '../assets/js/store.js';

// Понедельник, 28 сентября 2026, 12:00 по Новосибирску.
const MONDAY_NOON = Date.UTC(2026, 8, 28, 5, 0, 0);
const HOUR = 3600 * 1000;

let clock = MONDAY_NOON;
const realNow = Date.now;
Date.now = () => clock;

let server;
let requests;

function reply(status, body) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) });
}

globalThis.fetch = (url) => {
  requests.push(url);
  const handler = server(url);
  if (handler === 'down') return Promise.reject(new TypeError('Failed to fetch'));
  return reply(handler[0], handler[1]);
};

function schedule(id, name, gen, extra) {
  return Object.assign({
    v: 1, g: id, gn: name, gen, cov: ['2026-09-28', '2026-10-03'], bells: { 1: ['09:00', '10:30'] },
    days: [{ d: '2026-09-28', l: [{ n: 1, s: 'Физика', r: '101' }] }],
  }, extra || {});
}

function reset() {
  ['role', 'group', 'teacher', 'second', 'schedule', 'gen', 'fetchedAt', 'window', 'partial', 'server',
    'unreachable', 'gone', 'secondGone'].forEach(store.remove);
  clock = MONDAY_NOON;
  requests = [];
}

function healthy(gen, routes) {
  return (url) => {
    if (url === '/v1/meta') return [200, { v: 1, gen, status: 'ok', src_url: 'https://sheet#gid=1' }];
    for (const [prefix, answer] of routes) if (url.indexOf(prefix) === 0) return answer;
    return [404, { error: 'нет' }];
  };
}

test.after(() => { Date.now = realNow; });

test('первый заход качает, второй с тем же gen — нет', async () => {
  reset();
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  assert.equal((await repo.refresh(false)).kind, 'updated');
  assert.ok(requests.includes('/v1/schedule/isp-1?from=2026-09-28&days=14'));
  assert.equal(store.get('window'), '2026-09-28/14');
  assert.equal(repo.saved().gn, 'ИСП-1');

  requests = [];
  clock += HOUR;
  assert.equal((await repo.refresh(false)).kind, 'fresh');
  assert.deepEqual(requests, ['/v1/meta']);
  assert.equal(repo.fetchedAt(), clock);

  // Принудительное — качает и при том же gen.
  requests = [];
  assert.equal((await repo.refresh(true)).kind, 'updated');
  assert.equal(requests.length, 2);
});

test('новая неделя — перезапрос при том же gen', async () => {
  reset();
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1', {
    days: [{ d: '2026-09-28', l: [] }, { d: '2026-10-05', l: [] }],
  })]]]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(false);
  clock += 7 * 24 * HOUR;
  requests = [];
  assert.equal((await repo.refresh(false)).kind, 'updated');
  assert.ok(requests.includes('/v1/schedule/isp-1?from=2026-10-05&days=14'));
});

test('404 — пропажа только повтором через час и при здоровом сервере', async () => {
  reset();
  server = healthy('G1', []);
  repo.selectGroup({ id: 'old', name: 'Старая' });
  let result = await repo.refresh(true);
  assert.equal(result.kind, 'failed');
  assert.equal(repo.gone(), false);
  clock += 59 * 60 * 1000;
  assert.equal((await repo.refresh(true)).kind, 'failed');
  clock += 60 * 1000;
  assert.equal((await repo.refresh(true)).kind, 'gone');
  assert.equal(repo.gone(), true);
  // Новый выбор снимает отметку.
  repo.selectGroup({ id: 'new', name: 'Новая' });
  assert.equal(repo.gone(), false);
});

test('сервер молчит — сбой через полчаса цепочки', async () => {
  reset();
  server = () => 'down';
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(false);
  assert.equal(repo.serverBroken(), false);
  clock += 29 * 60 * 1000;
  await repo.refresh(false);
  assert.equal(repo.serverBroken(), false);
  clock += 60 * 1000;
  await repo.refresh(false);
  assert.equal(repo.serverState().status, 'unreachable');
  assert.equal(repo.serverState().since, new Date(MONDAY_NOON).toISOString());
  // Ответил — снова ok.
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  await repo.refresh(false);
  assert.equal(repo.serverBroken(), false);
  assert.equal(repo.serverState().src_url, 'https://sheet#gid=1');
});

test('переименование группы — выбор переписывается', async () => {
  reset();
  server = healthy('G2', [['/v1/schedule/old?', [200, schedule('new', 'Новое имя', 'G2')]]]);
  repo.selectGroup({ id: 'old', name: 'Старое имя' });
  await repo.refresh(true);
  assert.deepEqual(store.get('group'), { id: 'new', name: 'Новое имя' });
});

test('подгруппа: склейка, неполное без ответа, пропажа через час', async () => {
  reset();
  const theirs = schedule('isp-2', 'ИСП-2', 'G1', { days: [{ d: '2026-09-28', l: [{ n: 2, s: 'Химия', r: '5' }] }] });
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-2?', [200, theirs]],
  ]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  repo.selectSecond({ id: 'isp-2', name: 'ИСП-2' });
  await repo.refresh(true);
  assert.equal(repo.saved().days[0].l.length, 2);
  assert.equal(store.get('partial'), false);

  // Первое 404 соседки — своё без её пар, помечено неполным: следующий заход
  // перезапросит и при том же gen.
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  await repo.refresh(true);
  assert.equal(repo.saved().days[0].l.length, 1);
  assert.equal(store.get('partial'), true);
  assert.equal(repo.secondGone(), false);

  // Через час то же — соседки нет в таблице; выбор остаётся, расписание целое.
  clock += HOUR;
  requests = [];
  await repo.refresh(false);
  assert.ok(requests.some((u) => u.indexOf('/v1/schedule/isp-1?') === 0), 'неполное перезапрашивается');
  assert.equal(repo.secondGone(), true);
  assert.equal(store.get('partial'), false);
  assert.deepEqual(repo.second(), { id: 'isp-2', name: 'ИСП-2' });

  // Снятие подгруппы оставляет свои пары.
  repo.selectSecond(null);
  assert.equal(repo.saved().days[0].l.length, 1);
  assert.equal(repo.second(), null);
});

test('смена группы стирает прежнее расписание, повторный выбор — нет', async () => {
  reset();
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(true);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  assert.ok(repo.saved());
  repo.selectGroup({ id: 'isp-9', name: 'ИСП-9' });
  assert.equal(repo.saved(), null);
  assert.equal(repo.fetchedAt(), 0);
});

test('ответ на прежний выбор не записывается', async () => {
  reset();
  let release;
  const slow = new Promise((resolve) => { release = resolve; });
  server = (url) => url === '/v1/meta' ? [200, { gen: 'G1', status: 'ok' }] : [200, schedule('isp-1', 'ИСП-1', 'G1')];
  const realFetch = globalThis.fetch;
  globalThis.fetch = (url) => (url === '/v1/meta' ? realFetch(url) : slow.then(() => realFetch(url)));
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  const pending = repo.refresh(true);
  await new Promise((r) => setTimeout(r, 0));
  repo.selectGroup({ id: 'isp-9', name: 'ИСП-9' });
  release();
  await pending;
  globalThis.fetch = realFetch;
  assert.equal(repo.saved(), null);
});
