// Грузится до страницы, обычным скриптом: тема — до первой отрисовки, чтобы
// тёмная не мигала светлой; и слово для браузера, которому модули не по силам.
(function () {
  var prefix = /^\/tested(\/|$)/.test(location.pathname) ? 'wct:' : 'wc:';
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
