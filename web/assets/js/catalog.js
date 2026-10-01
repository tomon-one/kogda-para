// Списки групп и преподавателей и закреплённые в них: сохранённые, свежие с
// сервера и закреплённые за переименованием. Наружу — через repo.js.

import * as api from './api.js';
import * as store from './store.js';

export function cachedGroups() {
  return store.get('groups') || [];
}

export function cachedTeachers() {
  return store.get('teachers') || [];
}

/** Свежий список: {list} или {busy} — сервер занят (429), или {} — не ответил. */
function freshList(key, request) {
  return request().then(function (list) {
    var clean = (Array.isArray(list) ? list : []).filter(function (x) {
      return x && typeof x.id === 'string' && typeof x.name === 'string';
    });
    store.set(key, clean);
    return { list: clean };
  }, function (error) {
    return error instanceof api.HttpError && error.status === 429 ? { busy: true } : {};
  });
}

export function freshGroups() {
  return freshList('groups', api.groups);
}

export function freshTeachers() {
  return freshList('teachers', api.teachers);
}

/**
 * Закреплённые — за переименованием: id, которого нет в свежем списке,
 * спрашиваем у сервера, и он по памяти о старом имени отвечает под новым.
 */
export function followRenamedPins(groups, teachers) {
  function follow(key, list, ask) {
    var ids = {};
    list.forEach(function (x) { ids[x.id] = true; });
    var pins = store.get(key) || [];
    return pins.filter(function (id) { return !ids[id]; }).reduce(function (chain, old) {
      return chain.then(function () {
        return ask(old).then(function (body) {
          if (body.g !== old && ids[body.g]) {
            // Новый id мог быть закреплён и сам — без повтора, иначе звезда
            // гасла только со второго нажатия.
            var now = (store.get(key) || []).map(function (id) { return id === old ? body.g : id; });
            store.set(key, now.filter(function (id, i) { return now.indexOf(id) === i; }));
          }
        }, function () { /* не ответил — в другой раз */ });
      });
    }, Promise.resolve());
  }
  return follow('pinnedGroups', groups, api.scheduleOne)
    .then(function () { return follow('pinnedTeachers', teachers, api.teacherOne); });
}

export function pinned(kind) {
  return store.get(kind === 'teachers' ? 'pinnedTeachers' : 'pinnedGroups') || [];
}

export function togglePin(kind, id) {
  var key = kind === 'teachers' ? 'pinnedTeachers' : 'pinnedGroups';
  var list = store.get(key) || [];
  store.set(key, list.indexOf(id) >= 0 ? list.filter(function (x) { return x !== id; }) : list.concat([id]));
}
