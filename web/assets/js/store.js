// Хранилище браузера: выбор, последнее расписание, списки, состояние сервера.
//
// localStorage один на все адреса сайта, поэтому у tested свой префикс. Браузер
// может запретить хранилище (приватный режим) — тогда всё живёт в памяти до
// закрытия страницы.

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

/** Значение строкой, как лежит: по ней сверяют, не изменилось ли, без разбора. */
export function raw(key) {
  if (memory.hasOwnProperty(key)) return memory[key];
  if (!storage) return null;
  try { return storage.getItem(PREFIX + key); } catch (e) { return null; }
}

export function get(key) {
  var text = raw(key);
  if (text == null) return null;
  try { return JSON.parse(text); } catch (e) { return null; }
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
      // Переполнено или запрещено — держим в памяти, а прежнее значение убираем:
      // после перезагрузки старый снимок рядом с новым gen считался бы свежим.
      try { storage.removeItem(PREFIX + key); } catch (e2) { /* ничего */ }
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
