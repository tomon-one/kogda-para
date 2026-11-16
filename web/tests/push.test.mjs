// Выбор уведомлений сайта, пока служба ещё не ответила. Запуск: node --test web/tests
import test from 'node:test';
import assert from 'node:assert/strict';

// Браузер, где разрешение дано, а сервис-воркер не отвечает: смена висит.
globalThis.Notification = { permission: 'granted' };
Object.defineProperty(globalThis, 'navigator', {
  value: { serviceWorker: { getRegistration: () => new Promise(() => {}) } },
  configurable: true,
});

const push = await import('../assets/js/push.js');

test('второй выключатель до ответа службы — от выбора первого, а не от сохранённого', () => {
  assert.deepEqual(push.intended(), push.choice());
  push.update({ changes: true, remind: 0 }, { kind: 'group', id: 'isp-1' });
  // Служба ещё не ответила, сохранённого нет — а выбор уже этот.
  assert.deepEqual(push.choice(), { changes: false, remind: 0, digest: 0 });
  assert.deepEqual(push.intended(), { changes: true, remind: 0 });
});

test('вечерняя сводка: по умолчанию выключена, время — 20:00', () => {
  assert.equal(push.choice().digest, 0);
  assert.equal(push.lastDigest(), 20 * 60);
  assert.equal(push.DIGEST_MIN, 16 * 60);
  assert.equal(push.DIGEST_MAX, 23 * 60 + 59);
});
