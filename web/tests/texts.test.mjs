// Тексты для людей — дословно те же, что в приложении: описания настроек,
// пояснения уведомлений и экзаменов. Порядок разделов — тоже правило.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('..', import.meta.url));

function source(path) {
  return readFileSync(join(WEB, path), 'utf8');
}

/** Исходник без склейки строк: длинный текст в коде разбит переносами. */
function joined(text) {
  return text.replace(/'\s*\+\s*'/g, '');
}

test('оформление: описания «Стекла», компактного вида и коротких названий', () => {
  const settings = source('assets/js/ui/settings.js');
  assert.ok(settings.includes('Почти как Liquid Glass на iPhone.'));
  assert.ok(settings.includes('Тесные карточки: время и место с преподавателем в одну строку.'));
  // Ударение на «у» — чтобы читалось «у́же», а не «уже́».
  assert.ok(settings.includes('Включается сам, если экран у\u0301же 360 px.'));
  assert.ok(settings.includes("'Короткие названия предметов'"));
  assert.ok(joined(settings).includes('Короткие имена вместо длинных: например, «Алгоритмы» вместо «Основы ' +
    'алгоритмизации и программирования».'));
  // Свои имена можно исправить и с выключенным словарём.
  assert.ok(settings.includes('app.shortSubjects() || Object.keys(app.subjectShorts()).length ? ['));
});

test('настройки: «Исходная таблица» выше групп, группа — «Группы и подгруппы»', () => {
  const settings = source('assets/js/ui/settings.js');
  assert.ok(settings.includes("'Группы и подгруппы'"));
  assert.ok(!settings.includes("'Моя группа'"));
  const data = settings.indexOf("section('Исходная таблица'");
  const groups = settings.indexOf("'Группы и подгруппы'");
  assert.ok(data >= 0 && groups >= 0);
  assert.ok(data < groups, '«Исходная таблица» стоит выше групп');
  // «Сменить» — в разделе групп, рядом со своим названием.
  assert.ok(settings.includes("'Сменить'"));
  // Раздел про таблицу — одна фраза и кнопка; прежних абзацев в нём нет.
  assert.ok(joined(settings).includes('Ваше устройство обращается к серверу каждый час и при каждом ' +
    'открытии, забирая разобранные данные таблицы. Также можно открыть таблицу на своей группе и ' +
    'текущем дне кнопкой ниже.'));
  assert.ok(joined(settings).indexOf('текущем дне кнопкой ниже.') < joined(settings).indexOf("'Открыть таблицу колледжа'"));
  assert.ok(!settings.includes('На сервер уходит'));
  assert.ok(!settings.includes('Что написано в таблице колледжа'));
  // Пометки у подгрупп больше нет.
  assert.ok(!settings.includes('подгруппа вашей группы'));
});

test('уведомления: пояснения видны и с выключенным переключателем', () => {
  const push = source('assets/js/ui/notifications.js');
  const remind = push.indexOf("'Напоминание о первой паре дня или между парами.'");
  const digest = push.indexOf("'Первая пара завтрашнего дня.'");
  const changes = push.indexOf("'Отмены и замены на сегодня и завтра.'");
  assert.ok(remind >= 0 && changes >= 0);
  // Пояснение напоминания — до выключателя минут, а не внутри его условия.
  assert.ok(remind < push.indexOf('if (now.remind > 0)'));
  // Сводка и изменения — без обещаний, которых служба не держит.
  assert.ok(digest >= 0 && digest < push.indexOf('if (now.digest > 0)'));
  assert.ok(!push.includes('В субботу вечером не приходит'));
  assert.ok(!push.includes('нет пар — молчит'));
  assert.ok(!push.includes('С 22:00 до 7:00'));
});

test('экзамены: вместо срока — что графика нет', () => {
  const today = source('assets/js/ui/today.js');
  assert.ok(today.includes("'Действующего графика экзаменов в таблице колледжа нет.'"));
  assert.ok(!today.includes('в декабре'));
});
