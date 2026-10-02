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
    'unreachable', 'gone', 'goneHint', 'secondGone', 'lastOk', 'extras', 'extraSchedules'].forEach(store.remove);
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
  assert.ok(requests.includes('/v1/schedule/isp-1?from=2026-09-28&days=14&marks=1'));
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
  assert.ok(requests.includes('/v1/schedule/isp-1?from=2026-10-05&days=14&marks=1'));
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

test('по уведомлению «выберите заново» пропажа — с первого 404, служба уже ждала час', async () => {
  reset();
  server = healthy('G1', []);
  repo.selectGroup({ id: 'old', name: 'Старая' });
  repo.expectGone();
  assert.equal((await repo.refresh(true)).kind, 'gone');
  assert.equal(repo.gone(), true);
  // Подсказка — один раз: следующая группа снова ждёт час.
  repo.selectGroup({ id: 'other', name: 'Другая' });
  assert.equal((await repo.refresh(true)).kind, 'failed');
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

test('другие группы: отдельно от своей, без ответа — прежний снимок, пропажа через час', async () => {
  reset();
  const theirs = schedule('isp-2', 'ИСП-2', 'G1', { days: [{ d: '2026-09-28', l: [{ n: 2, s: 'Химия', r: '5' }] }] });
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-2?', [200, theirs]],
  ]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(true);
  repo.addExtras([{ id: 'isp-2', name: 'ИСП-2' }, { id: 'isp-1', name: 'ИСП-1' }, { id: 'isp-2', name: 'ИСП-2' }]);
  assert.deepEqual(repo.extras(), [{ id: 'isp-2', name: 'ИСП-2' }], 'своя и повтор — не добавляются');
  // Снимка новой группы нет — перезапрос и при том же gen.
  requests = [];
  await repo.refresh(false);
  assert.ok(requests.some((u) => u.indexOf('/v1/schedule/isp-2?') === 0));
  // Своё — только своё; на экране — вместе, с отметками.
  assert.equal(repo.saved().days[0].l.length, 1);
  assert.deepEqual(repo.shown().days[0].l.map((l) => l.slots), [[0], [1]]);

  // Первое 404 группы — своё обновилось, её прежний снимок на месте.
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  await repo.refresh(true);
  assert.equal(repo.shown().days[0].l.length, 2);
  assert.equal(repo.extras()[0].gone, undefined);

  // Через час то же — группы нет в таблице: выбор остаётся, её пар не видно.
  clock += HOUR;
  await repo.refresh(true);
  assert.equal(repo.extras()[0].gone, true);
  assert.equal(repo.shown().days[0].l.length, 1);
  assert.deepEqual(repo.shown().groupNames, ['ИСП-1', 'ИСП-2']);

  // Вернулась — отметка снята.
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-2?', [200, theirs]],
  ]);
  await repo.refresh(true);
  assert.deepEqual(repo.extras(), [{ id: 'isp-2', name: 'ИСП-2' }]);

  // Убрать — сразу, вместе со снимком.
  repo.removeExtra('isp-2');
  assert.deepEqual(repo.extras(), []);
  assert.equal(store.get('extraSchedules'), null);
  assert.equal(repo.shown().days[0].l.length, 1);
});

test('не больше шести групп вместе со своей; новая своя уходит из остальных', () => {
  reset();
  repo.selectGroup({ id: 'g0', name: 'Г/0' });
  repo.addExtras([1, 2, 3, 4, 5, 6, 7].map((i) => ({ id: 'g' + i, name: 'Г/' + i })));
  assert.deepEqual(repo.extras().map((g) => g.id), ['g1', 'g2', 'g3', 'g4', 'g5']);
  repo.selectGroup({ id: 'g3', name: 'Г/3' });
  assert.deepEqual(repo.extras().map((g) => g.id), ['g1', 'g2', 'g4', 'g5']);
});

test('снимок с соседней подгруппой до веб-0.2.0 переводится один раз', () => {
  reset();
  store.set('role', 'student');
  store.set('group', { id: 'isp-1', name: 'ИСП-1' });
  store.set('second', { id: 'isp-2', name: 'ИСП-2' });
  store.set('secondGone', { since: 5, confirmed: false });
  store.set('schedule', schedule('isp-1', 'ИСП-1', 'G1', { days: [{ d: '2026-09-28', l: [
    { n: 1, s: 'Физика', r: '101' },
    { n: 2, s: 'Химия', r: '5', gr: 'ИСП-2' },
  ] }] }));
  store.set('partial', false);
  repo.migrateGroups();
  assert.deepEqual(repo.extras(), [{ id: 'isp-2', name: 'ИСП-2', gone: false, goneSince: 5 }]);
  assert.equal(repo.saved().days[0].l.length, 1);
  assert.equal(store.get('second'), null);
  assert.equal(store.get('partial'), null);
  // Второй раз — ничего: своё добавленное не трогается.
  repo.addExtras([{ id: 'isp-3', name: 'ИСП-3' }]);
  repo.migrateGroups();
  assert.equal(repo.extras().length, 2);
});

test('своя группа, выбранная соседкой в прежней версии, в остальные не переносится', () => {
  reset();
  store.set('role', 'student');
  store.set('group', { id: 'isp-1', name: 'ИСП-1' });
  store.set('second', { id: 'isp-1', name: 'ИСП-1' });
  repo.migrateGroups();
  assert.deepEqual(repo.extras(), []);
  assert.equal(store.get('second'), null);
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

test('404 при сбое сервера — не пропажа: опечатку в таблице не путать с переименованием', async () => {
  reset();
  server = (url) => url === '/v1/meta' ? [200, { gen: 'G1', status: 'stale', since: '2026-09-28T01:00:00Z' }] : [404, {}];
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(true);
  clock += 2 * HOUR;
  assert.equal((await repo.refresh(true)).kind, 'failed');
  assert.equal(repo.gone(), false);
});

test('чужое расписание: 404 — «нет в таблице», а не «нет связи»', async () => {
  reset();
  server = healthy('G1', [['/v1/schedule/isp-2?', [200, schedule('isp-2', 'ИСП-2', 'G1')]]]);
  assert.equal((await repo.otherSchedule('groups', 'nope')).notFound, true);
  assert.equal((await repo.otherSchedule('groups', 'isp-2')).schedule.gn, 'ИСП-2');
  server = () => 'down';
  assert.deepEqual(await repo.otherSchedule('groups', 'isp-2'), {});
});

test('поле не того типа не ломает страницу: приводится при записи и при чтении', async () => {
  reset();
  const odd = schedule('isp-1', 'ИСП-1', 'G1', {
    bells: { 1: '09:00' },
    days: [null, { d: '2026-09-28', l: [{ n: 1, s: 'Физика', r: 101, t: 'Сильверхенд' }, { s: 'без номера' }] }],
  });
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, odd]]]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  assert.equal((await repo.refresh(true)).kind, 'updated');
  const saved = repo.saved();
  assert.deepEqual(saved.days, [{ d: '2026-09-28', l: [{ n: 1, s: 'Физика', r: '101' }] }]);
  assert.deepEqual(saved.bells, {});
  // Испорченное в хранилище — забывается, а не роняет.
  store.set('schedule', { days: 'мусор' });
  assert.equal(repo.saved(), null);
  assert.equal(store.get('schedule'), null);
  // Не похоже на расписание вовсе — обновление не удалось, прежнее не тронуто.
  store.set('schedule', odd);
  server = healthy('G2', [['/v1/schedule/isp-1?', [200, { error: 'странное' }]]]);
  assert.equal((await repo.refresh(true)).kind, 'failed');
  assert.equal(repo.saved().gn, 'ИСП-1');
});

test('закреплённые за переименованием — без повторов', async () => {
  reset();
  store.set('pinnedGroups', ['old', 'new']);
  store.set('pinnedTeachers', []);
  server = healthy('G1', [['/v1/schedule/old?', [200, schedule('new', 'Новое', 'G1')]]]);
  await repo.followRenamedPins([{ id: 'new', name: 'Новое' }], []);
  assert.deepEqual(repo.pinned('groups'), ['new']);
  repo.togglePin('groups', 'new');
  assert.deepEqual(repo.pinned('groups'), []);
});

test('редкие заходы: сбой — от последнего ответа сервера, а не от цепочки неудач', async () => {
  reset();
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(true);
  const answered = clock;
  // Двое суток спустя сервер молчит. Одна неудача — ещё не сбой (своя сеть
  // могла пропасть); повтор через минуту — «не отвечает», с давностью от
  // последнего ответа.
  clock += 48 * HOUR;
  server = () => 'down';
  await repo.refresh(false);
  assert.equal(repo.serverBroken(), false);
  clock += 60 * 1000;
  await repo.refresh(false);
  assert.equal(repo.serverState().status, 'unreachable');
  assert.equal(repo.serverState().since, new Date(answered).toISOString());
});

test('meta сорвался, а расписание пришло — сервер отвечает, «не отвечает» снимается', async () => {
  reset();
  store.set('server', { status: 'unreachable', since: '2026-09-28T01:00:00Z' });
  server = (url) => url === '/v1/meta' ? 'down' : [200, schedule('isp-1', 'ИСП-1', 'G1')];
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  assert.equal((await repo.refresh(true)).kind, 'updated');
  assert.equal(repo.serverBroken(), false);
});

test('другая группа, не пришедшая с новым gen, перезапрашивается; без сегодняшнего дня — нет', async () => {
  // Свежесть снимка группы — по gen, а не по сегодняшнему дню.
  reset();
  const practice = schedule('isp-2', 'ИСП-2', 'G1', { days: [] });
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-2?', [200, practice]],
  ]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  repo.addExtras([{ id: 'isp-2', name: 'ИСП-2' }]);
  assert.equal((await repo.refresh(true)).kind, 'updated');
  requests = [];
  clock += HOUR;
  assert.equal((await repo.refresh(false)).kind, 'fresh', 'группа на практике — не качается заново');
  assert.deepEqual(requests, ['/v1/meta']);

  // Новый gen, а группа не ответила (429) — «не обновилась», и следующий заход
  // при том же gen приносит её.
  server = healthy('G2', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G2')]],
    ['/v1/schedule/isp-2?', [429, { error: 'занят' }]],
  ]);
  const partial = await repo.refresh(false);
  assert.deepEqual(partial, { kind: 'partial', missed: ['ИСП-2'], fresh: [] });
  server = healthy('G2', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G2')]],
    ['/v1/schedule/isp-2?', [200, schedule('isp-2', 'ИСП-2', 'G2')]],
  ]);
  requests = [];
  assert.equal((await repo.refresh(false)).kind, 'updated');
  assert.ok(requests.some((u) => u.indexOf('/v1/schedule/isp-2?') === 0));
});

test('«Убрать» посреди обновления не выбрасывает своё; роль посреди 404 не стирает группы', async () => {
  reset();
  let release;
  const slow = new Promise((resolve) => { release = resolve; });
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-2?', [200, schedule('isp-2', 'ИСП-2', 'G1')]],
  ]);
  const realFetch = globalThis.fetch;
  globalThis.fetch = (url) => (url === '/v1/meta' ? realFetch(url) : slow.then(() => realFetch(url)));
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  repo.addExtras([{ id: 'isp-2', name: 'ИСП-2' }, { id: 'isp-3', name: 'ИСП-3' }]);
  const pending = repo.refresh(true);
  await new Promise((r) => setTimeout(r, 0));
  repo.removeExtra('isp-3');
  release();
  await pending;
  globalThis.fetch = realFetch;
  assert.equal(repo.saved().gn, 'ИСП-1');
  assert.deepEqual(repo.extras().map((g) => g.id), ['isp-2']);

  // 404 другой группы пришло, когда человек уже «Я преподаватель».
  let release2;
  const slow2 = new Promise((resolve) => { release2 = resolve; });
  server = healthy('G2', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G2')]]]);
  globalThis.fetch = (url) => (url === '/v1/meta' ? realFetch(url) : slow2.then(() => realFetch(url)));
  const second = repo.refresh(true);
  await new Promise((r) => setTimeout(r, 0));
  store.set('role', 'teacher');
  release2();
  await second;
  globalThis.fetch = realFetch;
  store.set('role', 'student');
  assert.deepEqual(repo.extras().map((g) => g.id), ['isp-2']);
});

test('принудительное при идущем принудительном встаёт следом', async () => {
  // Второе «Добавить» сразу после смены группы выбрасывалось сверкой.
  reset();
  let release;
  const slow = new Promise((resolve) => { release = resolve; });
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-9?', [200, schedule('isp-9', 'ИСП-9', 'G1')]],
  ]);
  const realFetch = globalThis.fetch;
  let first = true;
  globalThis.fetch = (url) => {
    if (url !== '/v1/meta' && first) { first = false; return slow.then(() => realFetch(url)); }
    return realFetch(url);
  };
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  const a = repo.refresh(true);
  await new Promise((r) => setTimeout(r, 0));
  repo.selectGroup({ id: 'isp-9', name: 'ИСП-9' });
  const b = repo.refresh(true);
  release();
  await a;
  assert.equal((await b).kind, 'updated');
  globalThis.fetch = realFetch;
  assert.equal(repo.saved().gn, 'ИСП-9');
});

test('сервер занят (429 на meta) — ни своего, ни других групп не просим', async () => {
  // При лимите nginx каждая попытка стоила N+2 запросов.
  reset();
  server = (url) => (url === '/v1/meta' ? [429, { error: 'занят' }] : [200, schedule('isp-1', 'ИСП-1', 'G1')]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  repo.addExtras([{ id: 'isp-2', name: 'ИСП-2' }]);
  requests = [];
  const result = await repo.refresh(true);
  assert.equal(result.kind, 'failed');
  assert.equal(result.error.status, 429);
  assert.deepEqual(requests, ['/v1/meta']);
});

test('только что добавленная группа не пришла — «её пары придут», первое 404 — не «не обновилась»', async () => {
  // У новой группы прежних пар нет, а неподтверждённое 404
  // гоняло обновление каждую минуту.
  reset();
  server = healthy('G1', [
    ['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]],
    ['/v1/schedule/isp-2?', [429, { error: 'занят' }]],
  ]);
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  repo.addExtras([{ id: 'isp-2', name: 'ИСП-2' }]);
  assert.deepEqual(await repo.refresh(true), { kind: 'partial', missed: ['ИСП-2'], fresh: ['ИСП-2'] });

  // Группу переименовали: первое 404 — подозрение, заход удачный.
  server = healthy('G1', [['/v1/schedule/isp-1?', [200, schedule('isp-1', 'ИСП-1', 'G1')]]]);
  assert.equal((await repo.refresh(true)).kind, 'updated');
});

test('неудача вечером и утром через ночь — не «не отвечает»', async () => {
  // Цепочка рвётся после трёх часов без проверок.
  reset();
  server = () => 'down';
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  store.set('lastOk', clock - 11 * HOUR);
  await repo.refresh(false);
  clock += 10 * HOUR;
  await repo.refresh(false);
  assert.notEqual(repo.serverState().status, repo.STATUS_UNREACHABLE);
  clock += 60 * 1000;
  await repo.refresh(false);
  assert.equal(repo.serverState().status, repo.STATUS_UNREACHABLE);
});

test('непрочитанные дни: прежним версиям stale, сайту — пометка у дня, без плашки сбоя', async () => {
  reset();
  const meta = { gen: 'G1', status: 'stale', refresh: 'ok', unread: ['2026-09-29'], since: '2026-09-28T01:00:00Z' };
  const body = schedule('isp-1', 'ИСП-1', 'G1', {
    days: [{ d: '2026-09-28', l: [{ n: 1, s: 'Физика', r: '101' }], un: 'kept' }],
  });
  server = (url) => url === '/v1/meta' ? [200, meta] : [200, body];
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(true);
  assert.equal(repo.serverBroken(), false);
  assert.equal(repo.serverState().since, undefined);
  assert.equal(repo.saved().days[0].un, 'kept');
  // Само обновление стоит — это сбой, как и был.
  meta.refresh = 'stale';
  await repo.refresh(true);
  assert.equal(repo.serverBroken(), true);
  assert.equal(repo.serverState().since, '2026-09-28T01:00:00Z');
});

test('расписание, сохранённое прежней версией, перезапрашивается один раз', async () => {
  reset();
  store.remove('format');
  const meta = { gen: 'G1', status: 'ok' };
  server = (url) => url === '/v1/meta' ? [200, meta] : [200, schedule('isp-1', 'ИСП-1', 'G1')];
  repo.selectGroup({ id: 'isp-1', name: 'ИСП-1' });
  await repo.refresh(false);
  // Как оставила прежняя версия: окно и gen те же, отметки формата нет.
  store.remove('format');
  requests = [];
  await repo.refresh(false);
  assert.ok(requests.some((url) => url.startsWith('/v1/schedule')));
  requests = [];
  await repo.refresh(false);
  assert.ok(!requests.some((url) => url.startsWith('/v1/schedule')));
});
