// Версия сайта — поднимать, когда меняется то, что видит человек. Номер сборки
// (хэш файлов) вписывает скрипт выкладки; он же в «Настройки» → «О сайте».
export var VERSION = 'веб-0.2.10';

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
 * Страница одной сборки, а скрипты другой: воркер сменился посреди загрузки
 * страницы. Возвращает номер сборки скриптов, если смесь, иначе null.
 */
export function mixedBuild() {
  var page = build();
  if (page === 'разработка' || SCRIPT_BUILD.indexOf('__') === 0) return null;
  return page !== SCRIPT_BUILD ? SCRIPT_BUILD : null;
}
