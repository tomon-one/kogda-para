// Поиск и разделы по букве в списках групп и преподавателей (ListParts.kt).

function isLetter(ch) {
  return ch.toLowerCase() !== ch.toUpperCase();
}

function isDigit(ch) {
  return ch >= '0' && ch <= '9';
}

/**
 * Ключ поиска: без регистра, «ё» как «е», только буквы и цифры. Дефис на
 * русской клавиатуре спрятан, и «исп924» должно находить «ИСП-924/1».
 */
function searchKey(text) {
  var lower = text.toLowerCase().replace(/ё/g, 'е');
  var out = '';
  for (var i = 0; i < lower.length; i++) {
    var ch = lower.charAt(i);
    if (isLetter(ch) || isDigit(ch)) out += ch;
  }
  return out;
}

export function matchesQuery(name, query) {
  var wanted = searchKey(query);
  return !wanted || searchKey(name).indexOf(wanted) >= 0;
}

/** Раздел по первой букве: «Б»; имена с цифры — «0–9»; прочее — «#». */
export function letterOf(name) {
  var first = name.replace(/^\s+/, '').charAt(0);
  if (!first) return '#';
  if (isDigit(first)) return '0–9';
  if (isLetter(first)) return first.toUpperCase().replace('Ё', 'Е');
  return '#';
}

/** Разделы в порядке списка: [{letter, rows}]. */
export function lettered(list, name) {
  var out = [];
  var byLetter = {};
  list.forEach(function (row) {
    var letter = letterOf(name(row));
    if (!byLetter[letter]) {
      byLetter[letter] = { letter: letter, rows: [] };
      out.push(byLetter[letter]);
    }
    byLetter[letter].rows.push(row);
  });
  return out;
}
