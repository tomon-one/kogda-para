// Хранилище браузера: выбор, последнее расписание, списки, состояние сервера.
//
// localStorage у сайта один на все адреса, поэтому у tested свой префикс:
// выбор группы там не должен задевать основную версию. Браузер может запретить
// хранилище (приватный режим, выключенные данные сайтов) — тогда всё живёт в
// памяти до закрытия страницы, и страница всё равно работает.

export var CHANNEL = typeof location !== 'undefined' && /^\/tested(\/|$)/.test(location.pathname)
  ? 'tested' : 'main';
var PREFIX = CHANNEL === 'tested' ? 'wct:' : 'wc:';

var memory = {};
var storage = null;
try {
  var probe = PREFIX + 'probe';
  localStorage.setItem(probe, '1');
  localStorage.removeItem(probe);
  storage = localStorage;
} catch (e) {
  storage = null;
}

export function persistent() {
  return storage !== null;
}

export function get(key) {
  var raw = null;
  if (memory.hasOwnProperty(key)) {
    raw = memory[key];
  } else if (storage) {
    try { raw = storage.getItem(PREFIX + key); } catch (e) { raw = null; }
  }
  if (raw == null) return null;
  try { return JSON.parse(raw); } catch (e) { return null; }
}

export function set(key, value) {
  if (value == null) return remove(key);
  var raw = JSON.stringify(value);
  if (storage) {
    try {
      storage.setItem(PREFIX + key, raw);
      delete memory[key];
      return;
    } catch (e) {
      // Переполнено или запрещено посреди работы — держим в памяти.
    }
  }
  memory[key] = raw;
}

export function remove(key) {
  delete memory[key];
  if (storage) {
    try { storage.removeItem(PREFIX + key); } catch (e) { /* ничего */ }
  }
}
