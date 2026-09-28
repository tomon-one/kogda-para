// Грузится до страницы, обычным скриптом: тема — до первой отрисовки, чтобы
// тёмная не мигала светлой; и слово для браузера, которому модули не по силам.
(function () {
  var tested = /^\/tested(\/|$)/.test(location.pathname);
  var prefix = tested ? 'wct:' : 'wc:';
  // У tested на домашнем экране своё имя — иначе два одинаковых значка
  // (аудит сайта, W8). Теги стоят в <head> выше этого скрипта.
  if (tested) {
    var manifest = document.querySelector('link[rel="manifest"]');
    if (manifest) manifest.setAttribute('href', 'manifest-tested.json');
    var title = document.querySelector('meta[name="apple-mobile-web-app-title"]');
    if (title) title.setAttribute('content', 'Когда пара? tested');
    document.title = 'Когда пара? tested';
  }
  try {
    var theme = JSON.parse(localStorage.getItem(prefix + 'theme') || 'null');
    if (theme === 'dark' || theme === 'light') document.documentElement.setAttribute('data-theme', theme);
  } catch (e) { /* хранилище запрещено — тема системная */ }

  var OLD = 'Браузер слишком старый для этого сайта. Обновите его или откройте сайт ' +
    'в Chrome, Safari, Firefox или Яндекс Браузере.';

  function say(text) {
    var app = document.getElementById('app');
    if (!app) return;
    app.innerHTML = '';
    var p = document.createElement('p');
    p.className = 'boot';
    p.appendChild(document.createTextNode(text));
    app.appendChild(p);
  }

  var modules = 'noModule' in document.createElement('script');
  document.addEventListener('DOMContentLoaded', function () {
    if (!modules) {
      say(OLD);
      return;
    }
    // Модуль не запустился — синтаксис не по силам браузеру или файл не дошёл.
    setTimeout(function () {
      if (!window.__whensclassStarted) {
        say('Страница не загрузилась. Обновите её; не поможет — возможно, браузер слишком ' +
          'старый: откройте сайт в Chrome, Safari, Firefox или Яндекс Браузере.');
      }
    }, 15000);
  });
})();
