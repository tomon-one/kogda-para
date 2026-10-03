// Пересылка подписки и повтор отписки против поддельного браузера и службы.
// Запуск: node --test web/tests
import test from 'node:test';
import assert from 'node:assert/strict';

let current = null;
const registration = {
  pushManager: {
    getSubscription: () => Promise.resolve(current),
    subscribe: () => Promise.resolve(current),
  },
};
globalThis.window = { isSecureContext: true, PushManager: function () {}, Notification: {} };
globalThis.Notification = { permission: 'granted' };
Object.defineProperty(globalThis, 'navigator', {
  value: {
    userAgent: 'Mozilla/5.0 (Linux; Android 12) Chrome/129.0',
    serviceWorker: {
      getRegistration: () => Promise.resolve(registration),
      ready: Promise.resolve(registration),
    },
  },
  configurable: true,
});

let calls = [];
let down = false;
globalThis.fetch = (path, init) => {
  calls.push({ path, body: init && init.body ? JSON.parse(init.body) : null });
  if (down) return Promise.reject(new TypeError('Failed to fetch'));
  const body = path === '/v1/push/key' ? { key: 'AAAA' } : {};
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
};

const store = await import('../assets/js/store.js');
const push = await import('../assets/js/push.js');

function browserSubscription(endpoint) {
  return {
    endpoint,
    toJSON: () => ({ endpoint, keys: { p256dh: 'p', auth: 'a' } }),
    unsubscribe: () => { current = null; return Promise.resolve(true); },
  };
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

test('выключили без сети — отписка у службы повторяется при следующем открытии', async () => {
  store.set('push', { changes: true, remind: 0, kind: 'group', id: 'isp-1', site: 'main', sent: Date.now(),
    endpoint: 'https://fcm.googleapis.com/a', key: 'AAAA' });
  current = browserSubscription('https://fcm.googleapis.com/a');
  calls = [];
  down = true;
  await push.disable();
  assert.deepEqual(calls.map((c) => c.path), ['/v1/push/remove']);
  assert.equal(current, null, 'браузер отписан и без ответа службы');

  down = false;
  calls = [];
  assert.equal(await push.sync({ kind: 'group', id: 'isp-1' }), false);
  await tick();
  assert.deepEqual(calls, [{ path: '/v1/push/remove', body: { endpoint: 'https://fcm.googleapis.com/a' } }]);
  // Дошло — больше не повторяется.
  calls = [];
  await push.sync({ kind: 'group', id: 'isp-1' });
  await tick();
  assert.deepEqual(calls, []);
});

test('браузер сменил подписку сам — новый адрес пересылается сразу, не через сутки', async () => {
  store.set('push', { changes: true, remind: 0, kind: 'group', id: 'isp-1', site: 'main', sent: Date.now(),
    endpoint: 'https://fcm.googleapis.com/old', key: 'AAAA' });
  current = browserSubscription('https://fcm.googleapis.com/new');
  calls = [];
  assert.equal(await push.sync({ kind: 'group', id: 'isp-1' }), true);
  const sent = calls.filter((c) => c.path === '/v1/push/subscribe');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].body.endpoint, 'https://fcm.googleapis.com/new');
  assert.equal(store.get('push').endpoint, 'https://fcm.googleapis.com/new');
  // Адрес тот же и сутки не прошли — молчим.
  calls = [];
  assert.equal(await push.sync({ kind: 'group', id: 'isp-1' }), false);
  assert.deepEqual(calls, []);
});
