// Версия сайта — поднимать, когда меняется то, что видит человек; какие
// именно файлы выложены, говорит номер сборки (хэш файлов) — его вписывает
// скрипт выкладки, и он же стоит в «Настройки» → «О сайте».
export var VERSION = 'веб-0.2.4';

export function build() {
  var meta = document.querySelector('meta[name="build"]');
  var value = meta ? meta.getAttribute('content') : '';
  return value && value !== '__BUILD__' ? value : 'разработка';
}
