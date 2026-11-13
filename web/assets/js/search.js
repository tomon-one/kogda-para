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

var LAYOUT_FROM = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
var LAYOUT_TO = 'йцукенгшщзхъфывапролджэячсмитьбюё';
var TRANSLIT = {
  'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'е': 'e', 'ж': 'zh', 'з': 'z',
  'и': 'i', 'й': 'y', 'к': 'k', 'л': 'l', 'м': 'm', 'н': 'n', 'о': 'o', 'п': 'p',
  'р': 'r', 'с': 's', 'т': 't', 'у': 'u', 'ф': 'f', 'х': 'h', 'ц': 'c', 'ч': 'ch',
  'ш': 'sh', 'щ': 'sch', 'ъ': '', 'ы': 'y', 'ь': '', 'э': 'e', 'ю': 'yu', 'я': 'ya'
};

/** Латиница без разночтений записи: обе стороны сравнения — через неё. */
function looseLatin(text) {
  return text.replace(/shch/g, 'sch').replace(/kh/g, 'h').replace(/ts/g, 'c').replace(/j/g, 'y')
    .replace(/w/g, 'v').replace(/x/g, 'ks').replace(/ia/g, 'ya').replace(/iu/g, 'yu');
}

function mapChars(text, map) {
  var out = '';
  for (var i = 0; i < text.length; i++) out += map(text.charAt(i));
  return out;
}

/** Неверная раскладка («bcg» — «исп») и латиница («alag» — «Алагян»); как в ListParts.kt. */
export function matchesQuery(name, query) {
  var wanted = searchKey(query);
  if (!wanted) return true;
  var key = searchKey(name);
  if (key.indexOf(wanted) >= 0) return true;
  // До searchKey: «;», «,» и «[» в раскладке — буквы.
  var swapped = searchKey(mapChars(query.toLowerCase(), function (ch) {
    var i = LAYOUT_FROM.indexOf(ch);
    return i >= 0 ? LAYOUT_TO.charAt(i) : ch;
  }));
  if (swapped && key.indexOf(swapped) >= 0) return true;
  var latin = looseLatin(wanted);
  if (!/[a-z]/.test(latin)) return false;
  var spelled = mapChars(key, function (ch) { return TRANSLIT.hasOwnProperty(ch) ? TRANSLIT[ch] : ch; });
  return looseLatin(spelled).indexOf(latin) >= 0;
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
