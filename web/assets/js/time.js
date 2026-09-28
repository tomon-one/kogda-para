// Время и подписи дат.
//
// Сетка звонков и дни листа — по Новосибирску, и «сейчас» для них — тамошнее:
// у студента в другом поясе подсветка иначе съезжала бы на часы. Моменты вроде
// «проверено в 17:00» и «сбой с …» — по часам человека, то есть по поясу
// браузера. Так же устроено приложение (DayFormat.kt).
//
// Дата везде — строка «2026-09-28»: так она приходит от сервера, так её можно
// сравнивать строками, и никакой пояс её не сдвинет.

export var COLLEGE_ZONE = 'Asia/Novosibirsk';

// Запасной путь для браузера без поясов в Intl: в Новосибирске UTC+7 без
// перехода на летнее время с 2016 года.
var COLLEGE_OFFSET_MS = 7 * 3600 * 1000;

var MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля',
  'августа', 'сентября', 'октября', 'ноября', 'декабря'];
var WEEKDAYS = ['понедельник', 'вторник', 'среда', 'четверг', 'пятница',
  'суббота', 'воскресенье'];

var collegeFormat = null;
try {
  collegeFormat = new Intl.DateTimeFormat('en-US', {
    timeZone: COLLEGE_ZONE, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  if (typeof collegeFormat.formatToParts !== 'function') collegeFormat = null;
} catch (e) {
  collegeFormat = null;
}

function pad(n) {
  return n < 10 ? '0' + n : String(n);
}

/**
 * Момент по часам колледжа: дата строкой и секунды от начала суток.
 * `ms` — миллисекунды эпохи, по умолчанию сейчас.
 */
export function collegeNow(ms) {
  if (ms === undefined) ms = Date.now();
  if (collegeFormat) {
    var parts = {};
    collegeFormat.formatToParts(new Date(ms)).forEach(function (p) { parts[p.type] = p.value; });
    // Chrome с hour12: false пишет полночь как «24».
    var hour = Number(parts.hour) % 24;
    return {
      date: parts.year + '-' + parts.month + '-' + parts.day,
      sec: hour * 3600 + Number(parts.minute) * 60 + Number(parts.second),
    };
  }
  var d = new Date(ms + COLLEGE_OFFSET_MS);
  return {
    date: d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()),
    sec: d.getUTCHours() * 3600 + d.getUTCMinutes() * 60 + d.getUTCSeconds(),
  };
}

/** «2026-09-28» → миллисекунды полуночи UTC этой даты; NaN — не дата. */
function utcOf(date) {
  var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date || '');
  if (!m) return NaN;
  return Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
}

export function isDate(date) {
  return !isNaN(utcOf(date));
}

function dateOfUtc(ms) {
  var d = new Date(ms);
  return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
}

export function addDays(date, n) {
  return dateOfUtc(utcOf(date) + n * 86400000);
}

/** День недели: 0 — понедельник, 6 — воскресенье. */
export function weekday(date) {
  return (new Date(utcOf(date)).getUTCDay() + 6) % 7;
}

/** Номер дня в году, с 1 — как `dayOfYear` в Java. */
export function dayOfYear(date) {
  var year = Number(date.slice(0, 4));
  return Math.round((utcOf(date) - Date.UTC(year, 0, 1)) / 86400000) + 1;
}

/**
 * Понедельник недели, куда входит `date`. Воскресенье — последний день своей
 * недели, а не первый следующей: иначе в воскресенье прожитая неделя пропадала
 * бы с экрана.
 */
export function weekStart(date) {
  return addDays(date, -weekday(date));
}

/** «Сегодня, 28 сентября, понедельник», «29 сентября, вторник». */
export function dayTitle(date, today) {
  var t = utcOf(date);
  if (isNaN(t)) return date;
  var d = new Date(t);
  var text = d.getUTCDate() + ' ' + MONTHS[d.getUTCMonth()] + ', ' + WEEKDAYS[weekday(date)];
  // «Послезавтра» человек и так посчитает по дате, а «вчера» помогает:
  // прошедшие дни остаются в списке.
  var prefix = date === today ? 'сегодня'
    : date === addDays(today, 1) ? 'завтра'
    : date === addDays(today, -1) ? 'вчера' : null;
  return capitalize(prefix ? prefix + ', ' + text : text);
}

export function capitalize(text) {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

/** «28 сентября» по дате-строке. */
export function dayMonth(date) {
  var d = new Date(utcOf(date));
  return d.getUTCDate() + ' ' + MONTHS[d.getUTCMonth()];
}

function localDate(ms) {
  var d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function localTime(ms) {
  var d = new Date(ms);
  return pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function localDayMonth(ms) {
  var d = new Date(ms);
  return d.getDate() + ' ' + MONTHS[d.getMonth()];
}

/**
 * «проверено в 19:12», «проверено вчера в 21:40», «проверено 5 сентября в 08:00».
 * «Проверено», а не «обновлено»: время ставится при каждой удачной проверке.
 */
export function formatFetchedAt(ms, nowMs) {
  if (!ms || ms <= 0) return 'ещё не проверялось';
  if (nowMs === undefined) nowMs = Date.now();
  var day = localDate(ms);
  if (day === localDate(nowMs)) return 'проверено в ' + localTime(ms);
  if (day === addDays(localDate(nowMs), -1)) return 'проверено вчера в ' + localTime(ms);
  return 'проверено ' + localDayMonth(ms) + ' в ' + localTime(ms);
}

/** «dd.MM HH:mm» — для сведений в отчёте. */
export function formatShort(ms) {
  var d = new Date(ms);
  return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + ' ' + localTime(ms);
}

/**
 * Момент от сервера — ISO в UTC: «2026-09-07T10:29:53Z». Дробные секунды
 * длиннее трёх знаков старый Safari не разбирает, поэтому их отрезаем.
 */
export function parseIso(iso) {
  if (!iso) return NaN;
  return Date.parse(String(iso).replace(/(\.\d{3})\d+/, '$1'));
}

/** Русский счёт: 1 минута, 2 минуты, 5 минут, 11 минут. */
export function plural(n, one, few, many) {
  var word;
  if (n % 100 >= 11 && n % 100 <= 14) word = many;
  else if (n % 10 === 1) word = one;
  else if (n % 10 >= 2 && n % 10 <= 4) word = few;
  else word = many;
  return n + ' ' + word;
}

/** «20 минут», «час», «3 часа 5 минут». */
export function formatDurationLong(minutes) {
  var hours = Math.floor(minutes / 60);
  var rest = minutes % 60;
  var h = hours === 1 ? 'час' : plural(hours, 'час', 'часа', 'часов');
  var m = hours === 0 && rest === 1 ? 'минуту' : plural(rest, 'минуту', 'минуты', 'минут');
  if (hours === 0) return m;
  if (rest === 0) return h;
  return h + ' ' + m;
}

/**
 * «11 сентября, 11:00 — уже 2 дня»: с какого момента сервер лежит. Двое суток
 * сбоя не должны выглядеть как минута.
 */
export function formatSince(iso, nowMs) {
  var since = parseIso(iso);
  if (isNaN(since)) return iso;
  if (nowMs === undefined) nowMs = Date.now();
  var when = localDayMonth(since) + ', ' + localTime(since);
  var minutes = Math.max(0, Math.floor((nowMs - since) / 60000));
  var ago;
  if (minutes < 60) ago = plural(Math.max(1, minutes), 'минуту', 'минуты', 'минут');
  else if (minutes < 48 * 60) ago = plural(Math.floor(minutes / 60), 'час', 'часа', 'часов');
  else ago = plural(Math.floor(minutes / 60 / 24), 'день', 'дня', 'дней');
  return when + ' — уже ' + ago;
}

/**
 * «с сегодняшнего дня», «со вчерашнего дня», «с 23 сентября». День начала
 * счёта — по часам человека, «сегодня» — колледжа, как в приложении.
 */
export function tallySince(ms, today) {
  var day = localDate(ms);
  if (day === today) return 'с сегодняшнего дня';
  if (day === addDays(today, -1)) return 'со вчерашнего дня';
  return 'с ' + localDayMonth(ms);
}

/** Пояс браузера для отчёта: «Asia/Novosibirsk» или смещение. */
export function browserZone() {
  try {
    var zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (zone) return zone;
  } catch (e) { /* старый браузер */ }
  var offset = -new Date().getTimezoneOffset();
  return 'UTC' + (offset >= 0 ? '+' : '−') + Math.abs(offset / 60);
}
