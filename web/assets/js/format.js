// Подписи пары: тип занятия, место, преподаватели, ссылка. Те же правила, что
// у приложения (DayFormat.kt, Dto.kt, WebinarLink.kt, SheetLink.kt).

/** Пара идёт не в аудитории. Ссылка при аудитории — очная пара. */
export function isOnline(lesson) {
  return !!lesson.o || (!!lesson.u && !lesson.r);
}

export function isCancelled(lesson) {
  return !!lesson.x;
}

var KINDS = {
  'лек': 'Лекция',
  'пр': 'Практика',
  'лаб': 'Лабораторная',
  'сем': 'Семинар',
  'конс': 'Консультация',
  'экз': 'Экзамен',
  'зач': 'Зачёт',
  'диф.зач': 'Диф. зачёт',
  'курс.р.': 'Курсовая',
};

/** «Лек» → «Лекция». Незнакомое сокращение — как есть: лучше, чем ничего. */
export function kindName(kind) {
  if (!kind) return null;
  var key = kind.trim().toLowerCase();
  if (!key) return null;
  return KINDS.hasOwnProperty(key) ? KINDS[key] : kind;
}

/** «онлайн» или «онлайн · 12»: у онлайн-пары в `r` номер комнаты, не кабинет. */
export function onlineLabel(lesson) {
  var room = (lesson.r || '').trim();
  return room ? 'онлайн · ' + room : 'онлайн';
}

var ROOM_CHARS = /^[0-9\/\-.абвгАБВГ]+$/;

/**
 * «272» → «каб. 272»; «Спортзал Б.Хмельницкого 2» — как есть. Голый номер в
 * строке читается как что угодно.
 */
export function roomLabel(room) {
  var text = (room || '').trim();
  if (!text) return null;
  return text.length <= 8 && ROOM_CHARS.test(text) ? 'каб. ' + text : text;
}

/** «Нечаев Сергей Алексеевич» → «Нечаев С. А.». */
export function shortenName(fullName) {
  var parts = fullName.trim().split(' ').filter(function (p) { return p; });
  if (parts.length < 2) return fullName;
  return parts[0] + ' ' + parts.slice(1).map(function (p) { return p.charAt(0) + '.'; }).join(' ');
}

/**
 * Площадки вебинаров колледжа. Ссылку в ячейку пишет любой, кто правит
 * таблицу, и хост не отсюда показывается как чужой адрес.
 */
var WEBINAR_DOMAINS = ['mts-link.ru', 'webinar.ru', 'zoom.us'];

/**
 * Разбор адреса — тот же, что у браузера (URL): что он покажет хостом, туда
 * «Открыть» и поведёт. Своя регулярка ошибается на «:443@» и «\», и ссылка на
 * чужой сайт выглядит площадкой колледжа.
 */
function parseUrl(url) {
  var text = (url || '').trim();
  if (!text) return null;
  try {
    var u = new URL(text);
    return { scheme: u.protocol.replace(/:$/, '').toLowerCase(), host: u.hostname.toLowerCase(), path: u.pathname };
  } catch (e) {
    return null;
  }
}

export function isKnownWebinar(url) {
  // Обратную косую браузер читает как «/», а java.net.URI в приложении не
  // разбирает вовсе: и там и тут такой адрес — чужой.
  if ((url || '').indexOf('\\') >= 0) return false;
  var u = parseUrl(url);
  if (!u || u.scheme !== 'https') return false;
  var host = u.host.replace(/\.$/, '');
  return WEBINAR_DOMAINS.some(function (d) {
    return host === d || host.slice(-(d.length + 1)) === '.' + d;
  });
}

/** «https://www.zoom.us/j/1» → «zoom.us» — хост, куда поведёт ссылка. */
export function linkHost(url) {
  var u = parseUrl(url);
  if (!u || !u.host) return url;
  return u.host.indexOf('www.') === 0 ? u.host.slice(4) : u.host;
}

/**
 * Последний кусок пути, не длиннее 16 знаков: по нему отличают три пары подряд
 * в одной комнате от трёх разных.
 */
export function linkEnd(url) {
  var u = parseUrl(url);
  if (!u) return null;
  var segments = u.path.split('/').filter(function (s) { return s.trim(); });
  if (!segments.length) return null;
  var last = segments[segments.length - 1];
  try { last = decodeURIComponent(last); } catch (e) { /* как есть */ }
  return last.slice(-16);
}

/** Открывать ли ссылку вообще: только http и https. */
export function isWebLink(url) {
  var u = parseUrl(url);
  return !!u && (u.scheme === 'https' || u.scheme === 'http');
}

/** Буквы колонки, сдвинутые на `by`: «EQ» + 4 → «EU», «Z» + 1 → «AA». */
export function shiftColumn(letters, by) {
  var number = 0;
  var upper = letters.toUpperCase();
  for (var i = 0; i < upper.length; i++) number = number * 26 + (upper.charCodeAt(i) - 64);
  number += by;
  var out = '';
  while (number > 0) {
    var rem = (number - 1) % 26;
    out = String.fromCharCode(65 + rem) + out;
    number = Math.floor((number - 1) / 26);
  }
  return out;
}

/**
 * Ссылка в таблицу колледжа — к своей ячейке, а не в книгу. У преподавателя
 * своей колонки нет — берётся колонка первой пары дня. Выделение — блок группы
 * из четырёх колонок и все строки дня, по две на пару.
 */
export function sheetLink(schedule, day, fallback) {
  var base = schedule && schedule.src_url;
  if (!base) return fallback || null;
  var days = schedule.days || [];
  var found = null;
  if (day) {
    for (var i = 0; i < days.length && !found; i++) if (days[i].d === day) found = days[i];
    // Дня в ответе нет (воскресенье, каникулы) — ближайший следующий.
    for (var j = 0; j < days.length && !found; j++) if (days[j].d > day) found = days[j];
  }
  var column = schedule.col || null;
  if (!column && found) {
    for (var k = 0; k < (found.l || []).length && !column; k++) column = found.l[k].col || null;
  }
  var row = found && found.row != null ? found.row : null;
  var pairs = 0;
  Object.keys(schedule.bells || {}).forEach(function (key) {
    var n = parseInt(key, 10);
    if (!isNaN(n) && n > pairs) pairs = n;
  });
  if (!pairs) pairs = 6;
  if (column && row != null) {
    return base + '&range=' + column + row + ':' + shiftColumn(column, 3) + (row + 2 * pairs - 1);
  }
  if (column) return base + '&range=' + column + '1';
  if (row != null) return base + '&range=A' + row;
  return base;
}

var ORDINALS = ['Первая', 'Вторая', 'Третья', 'Четвёртая', 'Пятая', 'Шестая'];

/** «Вторая группа», «Третья группа» — заголовок выбора следующей (ordinalGroup в GroupMarks.kt). */
export function ordinalGroup(number) {
  return (ORDINALS[number - 1] || number + '-я') + ' группа';
}

/** «20 мин», «1 ч», «1 ч 30 мин» — formatDurationShort в приложении. */
export function durationShort(minutes) {
  if (minutes < 60) return minutes + ' мин';
  if (minutes % 60 === 0) return (minutes / 60) + ' ч';
  return Math.floor(minutes / 60) + ' ч ' + (minutes % 60) + ' мин';
}

/** Подпись плитки выбора минут: число крупно, единица мелко (tileLabel в ReminderDialog.kt). */
export function tileLabel(minutes) {
  if (minutes < 60) return [String(minutes), 'мин'];
  if (minutes === 60) return ['1', 'час'];
  if (minutes % 60 === 0) return [String(minutes / 60), 'часа'];
  if (minutes % 30 === 0) return [Math.floor(minutes / 60) + ',5', 'часа'];
  return [durationShort(minutes), ''];
}
