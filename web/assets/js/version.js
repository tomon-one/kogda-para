// Версия сайта — поднимать вручную при каждой выкладке, как versionCode у
// приложения. Номер сборки (хэш файлов) скрипт выкладки вписывает сам.
export var VERSION = 'веб-0.1.0';

export function build() {
  var meta = document.querySelector('meta[name="build"]');
  var value = meta ? meta.getAttribute('content') : '';
  return value && value !== '__BUILD__' ? value : 'разработка';
}
