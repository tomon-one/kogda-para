// Переходы: адрес после «#», история вкладки и направление въезда экрана.

import * as repo from './repo.js';
import * as store from './store.js';
import { closeDialog } from './ui/dom.js';
import { collegeNow } from './time.js';
import { MAX_GROUPS } from './schedule.js';
import { state, nav, app } from './state.js';
import { render } from './render.js';
import { emptyList, loadLists, loadOther } from './data.js';

/** Адрес, присланный человеку без своего выбора: открыть его после выбора. */
var pendingRoute = null;
var returningHome = false;

/**
 * Сколько шагов сайта над первым в истории вкладки — в history.state, а не в
 * памяти: переживает перезагрузку, и «назад» из одного места не считается
 * дважды.
 */
export function stepsNow() {
  return (history.state && history.state.steps) || 0;
}

/**
 * Адрес — после «#»: «settings», «teachers/<id>», «pick/self». Кнопка «назад»
 * браузера и жест назад на телефоне работают без своего кода.
 */
export function parseRoute() {
  var hash = decodeURIComponentSafe(location.hash.replace(/^#\/?/, ''));
  var parts = hash.split('/');
  var head = parts[0];
  if (head === 'settings') return { screen: 'settings' };
  // «pick/second» — адрес до веб-0.2.0, мог остаться в истории вкладки.
  if (head === 'pick') {
    return { screen: 'pick', mode: parts[1] === 'self' ? 'self' : parts[1] === 'extra' || parts[1] === 'second' ? 'extra' : 'group' };
  }
  if (head === 'teachers' || head === 'groups') {
    // Id строит служба из названия: латиница, цифры, дефис. Прочее из адреса
    // в запрос не пускать: «..%2F» nginx раскодирует в чужой путь.
    var id = parts.slice(1).join('/');
    return { screen: 'main', kind: head, id: /^[a-z0-9-]+$/.test(id) ? id : null };
  }
  return { screen: 'main', kind: null, id: null };
}

function decodeURIComponentSafe(text) {
  try { return decodeURIComponent(text); } catch (e) { return text; }
}

export function go(path, replace) {
  var hash = path ? '#' + path : '';
  var url = location.pathname + location.search + hash;
  // Присланный адрес едет вместе с экраном выбора (приветствие → выбор, свой
  // → чужой режим), и только с ним: после выбора он уже открыт.
  var pending = /^pick/.test(path || '') && history.state ? history.state.pending : undefined;
  if (replace) history.replaceState({ steps: stepsNow(), pending: pending }, '', url);
  else history.pushState({ steps: stepsNow() + 1 }, '', url);
  onRoute();
}

/** Шаг назад по истории сайта; дальше первого — на своё расписание. */
export function back() {
  if (stepsNow() > 0) history.back();
  else go('', true);
}

/**
 * На главный экран, свернув пройденное: «назад» оттуда — вон с сайта, как в
 * приложении. Был присланный адрес — к нему.
 */
export function home() {
  // Присланный адрес лежит в записи истории экрана выбора — и после перезагрузки.
  pendingRoute = (history.state && history.state.pending) || pendingRoute;
  var steps = stepsNow();
  if (steps > 0) {
    returningHome = true;
    history.go(-steps);
  } else {
    var target = pendingRoute;
    pendingRoute = null;
    go(target || '', true);
  }
}

export function onRoute() {
  var route = parseRoute();
  var chosen = repo.chosen();
  // Приветствие — один раз; без выбора — сразу к выбору.
  if (!chosen && route.screen !== 'pick') {
    // Ссылку на чужое расписание из чата не терять: откроется после выбора.
    var pending = route.kind && route.id ? route.kind + '/' + route.id : (history.state && history.state.pending) || null;
    route = { screen: 'pick', mode: 'group' };
    history.replaceState({ steps: stepsNow(), pending: pending }, '', location.pathname + location.search + '#pick');
  }
  // Больше шести групп не выбрать: жест «вперёд» открывал «7-я группа», а
  // нажатие молча ничего не добавляло.
  if (route.screen === 'pick' && route.mode === 'extra' &&
      (repo.isTeacher() || repo.extras().length >= MAX_GROUPS - 1)) {
    route = { screen: 'settings' };
    history.replaceState({ steps: stepsNow() }, '', location.pathname + location.search + '#settings');
  }
  var wasOwn = app.route.screen === 'main' && !app.route.kind;
  var isOwn = route.screen === 'main' && !route.kind;
  var enteredList = route.screen === 'main' && (route.kind !== app.route.kind || route.id !== app.route.id);
  var oldRoute = app.route;
  app.route = route;
  // Глубже — справа, назад — слева. Внутри главного экрана шапка и вкладки
  // стоят на месте, едет только содержимое: иначе смена вкладки выглядела как
  // перезагрузка страницы.
  var depth = depthOf(route);
  var oldTab = nav.depth == null ? null : tabOf(oldRoute);
  var newTab = tabOf(route);
  if (oldTab && newTab && oldTab !== newTab) {
    nav.cls = tabIndex(newTab) > tabIndex(oldTab) ? 'tab-right' : 'tab-left';
  } else if (oldTab && newTab) {
    nav.cls = depth >= nav.depth ? 'content-forward' : 'content-back';
  } else {
    nav.cls = nav.depth == null || depth === nav.depth ? 'enter-fade'
      : depth > nav.depth ? 'enter-forward' : 'enter-back';
  }
  // Дни всплывают после выбора группы; при переходах внутри экрана хватает сдвига.
  nav.appear = nav.depth != null && nav.depth < 2 && depth >= 2;
  nav.depth = depth;
  if ((isOwn && !wasOwn) || (route.id && enteredList)) state.wanted = collegeNow().date;
  if (route.id) loadOther(route.kind, route.id);
  // Экран выбора — всегда за свежими (ответ по ETag, без изменений — пустой):
  // после «Выбрать заново» в старом списке нет новой группы.
  if (route.screen === 'pick') loadLists(true);
  else if (route.kind) loadLists(emptyList(state.groups) || emptyList(state.teachers));
  closeDialog();
  render(true);
}

/** Вкладка главного экрана, где этот адрес; null — не главный экран. */
function tabOf(route) {
  if (route.screen !== 'main') return null;
  if (repo.isTeacher()) return route.kind === 'groups' ? 'students' : 'teachers';
  return route.kind === 'teachers' ? 'teachers' : 'students';
}

/** Порядок вкладок: у преподавателя его раздел первым. */
function tabIndex(tab) {
  return (repo.isTeacher() ? ['teachers', 'students'] : ['students', 'teachers']).indexOf(tab);
}

function depthOf(route) {
  if (!store.get('welcome') && !repo.chosen()) return 0;
  if (route.screen === 'pick') return 1;
  if (route.screen === 'settings' || route.id) return 3;
  return 2;
}

window.addEventListener('popstate', function () {
  if (returningHome) {
    returningHome = false;
    var target = pendingRoute;
    pendingRoute = null;
    history.replaceState({ steps: 0 }, '', location.pathname + location.search + (target ? '#' + target : ''));
  }
  onRoute();
});
