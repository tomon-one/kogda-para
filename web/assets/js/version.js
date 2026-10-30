// Версия сайта — поднимать, когда меняется то, что видит человек; какие
// именно файлы выложены, говорит номер сборки (хэш файлов) — его вписывает
// скрипт выкладки, и он же стоит в «Настройки» → «О сайте».
export var VERSION = 'веб-0.2.8';

// Тот же номер, вписанный в скрипты (publish_web.sh): страница и скрипты разных
// сборок — признак смеси (main.js, mixedBuild).
var SCRIPT_BUILD = '__BUILD__';

export function build() {
  var meta = document.querySelector('meta[name="build"]');
  var value = meta ? meta.getAttribute('content') : '';
  // По частям: скрипт выкладки вписывает номер сборки вместо каждого целого маркера.
  return value && value !== '__' + 'BUILD__' ? value : 'разработка';
}

/**
 * Страница одной сборки, а скрипты — другой: воркер сменился посреди загрузки
 * старой страницы (старая разметка без новых стилей — дни без
 * карточек). Возвращает номер сборки скриптов, если смесь, иначе null.
 */
export function mixedBuild() {
  var page = build();
  if (page === 'разработка' || SCRIPT_BUILD.indexOf('__') === 0) return null;
  return page !== SCRIPT_BUILD ? SCRIPT_BUILD : null;
}
