// Расчёты сайта — те же правила, что у приложения. Запуск: node --test web/tests
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  addDays, collegeNow, dayOfYear, dayTitle, formatDurationLong, formatSince, hourOf, plural, weekStart,
  weekday,
} from '../assets/js/time.js';
import {
  isKnownWebinar, isOnline, isWebLink, kindName, linkEnd, linkHost, onlineLabel, roomLabel, sheetLink,
  shiftColumn, shortenName, tileLabel,
} from '../assets/js/format.js';
import {
  combineGroups, currentLessonNumber, dayIndex, daysWithGaps, freeDay, lessonTime, ownOnly, shortLabels, subgroupsOf, windowMark,
} from '../assets/js/schedule.js';
import { letterOf, lettered, matchesQuery } from '../assets/js/search.js';

test('время колледжа — Новосибирск, UTC+7', () => {
  // 2026-09-27 20:30:15 UTC — в Новосибирске уже 28-е, 03:30:15.
  const now = collegeNow(Date.UTC(2026, 8, 27, 20, 30, 15));
  assert.equal(now.date, '2026-09-28');
  assert.equal(now.sec, 3 * 3600 + 30 * 60 + 15);
  assert.equal(collegeNow(Date.UTC(2026, 8, 27, 17, 0, 0)).sec, 0);
  // Chrome с hour12: false пишет полночь как «24» — это 0, а не сутки вперёд.
  assert.equal(hourOf('24'), 0);
  assert.equal(hourOf('23'), 23);
});

test('даты строками', () => {
  assert.equal(addDays('2026-09-30', 1), '2026-10-01');
  assert.equal(addDays('2026-03-01', -1), '2026-02-28');
  assert.equal(weekday('2026-09-28'), 0);
  assert.equal(weekday('2026-10-04'), 6);
  // Воскресенье — конец своей недели, не начало следующей.
  assert.equal(weekStart('2026-10-04'), '2026-09-28');
  assert.equal(weekStart('2026-09-28'), '2026-09-28');
  assert.equal(dayOfYear('2026-01-01'), 1);
  assert.equal(dayOfYear('2026-09-29'), 272);
});

test('заголовок дня', () => {
  assert.equal(dayTitle('2026-09-28', '2026-09-28'), 'Сегодня, 28 сентября, понедельник');
  assert.equal(dayTitle('2026-09-29', '2026-09-28'), 'Завтра, 29 сентября, вторник');
  assert.equal(dayTitle('2026-09-27', '2026-09-28'), 'Вчера, 27 сентября, воскресенье');
  assert.equal(dayTitle('2026-10-01', '2026-09-28'), '1 октября, четверг');
});

test('русский счёт и длительности', () => {
  assert.equal(plural(1, 'пара', 'пары', 'пар'), '1 пара');
  assert.equal(plural(3, 'пара', 'пары', 'пар'), '3 пары');
  assert.equal(plural(11, 'пара', 'пары', 'пар'), '11 пар');
  assert.equal(plural(21, 'пара', 'пары', 'пар'), '21 пара');
  assert.equal(formatDurationLong(1), 'минуту');
  assert.equal(formatDurationLong(60), 'час');
  assert.equal(formatDurationLong(61), 'час 1 минуту');
  assert.equal(formatDurationLong(185), '3 часа 5 минут');
});

test('давность сбоя округляется вниз', () => {
  const since = '2026-09-11T04:00:00Z';
  const at = (minutes) => Date.parse(since) + minutes * 60000;
  assert.match(formatSince(since, at(0)), /— уже 1 минуту$/);
  assert.match(formatSince(since, at(59)), /— уже 59 минут$/);
  assert.match(formatSince(since, at(60)), /— уже 1 час$/);
  assert.match(formatSince(since, at(47 * 60 + 59)), /— уже 47 часов$/);
  assert.match(formatSince(since, at(48 * 60)), /— уже 2 дня$/);
});

test('подписи пары', () => {
  assert.equal(kindName('Лек'), 'Лекция');
  assert.equal(kindName('Диф.зач'), 'Диф. зачёт');
  assert.equal(kindName('Новое'), 'Новое');
  assert.equal(kindName(' '), null);
  assert.equal(roomLabel('272'), 'каб. 272');
  assert.equal(roomLabel('308а'), 'каб. 308а');
  assert.equal(roomLabel('Спортзал Б.Хмельницкого 2'), 'Спортзал Б.Хмельницкого 2');
  assert.equal(roomLabel('123456789'), '123456789');
  assert.equal(roomLabel(''), null);
  assert.equal(onlineLabel({ r: '12' }), 'онлайн · 12');
  assert.equal(onlineLabel({}), 'онлайн');
  assert.equal(shortenName('Трухачев Даниил Дмитриевич'), 'Трухачев Д. Д.');
  assert.equal(shortenName('Трухачев'), 'Трухачев');
});

test('онлайн', () => {
  assert.equal(isOnline({ o: 1 }), true);
  assert.equal(isOnline({ u: 'https://x' }), true);
  // Ссылка при кабинете — очная пара, преподаватель на связи.
  assert.equal(isOnline({ u: 'https://x', r: '269' }), false);
});

test('ссылки на вебинары', () => {
  assert.equal(isKnownWebinar('https://my.mts-link.ru/j/1/2'), true);
  assert.equal(isKnownWebinar('https://us06web.zoom.us/j/1'), true);
  assert.equal(isKnownWebinar('http://my.mts-link.ru/j/1'), false);
  assert.equal(isKnownWebinar('https://mts-link.ru.evil.com/j'), false);
  assert.equal(isKnownWebinar('https://notzoom.us/j'), false);
  assert.equal(isKnownWebinar('https://user@zoom.us/j'), true);
  // Хост — как у браузера: всё до «@» — учётные данные, «\» — это «/».
  for (const evil of ['https://my.mts-link.ru:443@evil.example/j/333',
    'https://evil.example\\.mts-link.ru/j/444', 'https://evil.example\\@my.mts-link.ru/j/555']) {
    assert.equal(isKnownWebinar(evil), false, evil);
    assert.equal(linkHost(evil), 'evil.example', evil);
  }
  assert.equal(isWebLink('javascript:alert(1)'), false);
  assert.equal(isWebLink('https://zoom.us/j'), true);
  assert.equal(linkHost('https://www.zoom.us/j/1'), 'zoom.us');
  assert.equal(linkEnd('https://my.mts-link.ru/j/100000004/20000000626'), '20000000626');
  assert.equal(linkEnd('https://my.mts-link.ru/j/12345678901234567890/'), '5678901234567890');
  assert.equal(linkEnd('https://zoom.us'), null);
});

test('ссылка в таблицу — к блоку группы и дню', () => {
  assert.equal(shiftColumn('EQ', 4), 'EU');
  assert.equal(shiftColumn('Z', 1), 'AA');
  assert.equal(shiftColumn('S', 3), 'V');
  const schedule = {
    src_url: 'https://docs.google.com/spreadsheets/d/x/edit#gid=1', col: 'S',
    bells: { 1: ['09:00', '10:30'], 6: ['17:40', '19:10'] },
    days: [{ d: '2026-09-28', row: 76, l: [] }, { d: '2026-09-29', row: 90, l: [] }],
  };
  assert.equal(sheetLink(schedule, '2026-09-28', null), schedule.src_url + '&range=S76:V87');
  // Дня нет — ближайший следующий.
  assert.equal(sheetLink(schedule, '2026-09-27', null), schedule.src_url + '&range=S76:V87');
  assert.equal(sheetLink(null, '2026-09-28', 'https://fallback'), 'https://fallback');
  // У преподавателя колонка — у первой пары дня.
  const teacher = { src_url: 'https://s#gid=1', bells: {}, days: [{ d: '2026-09-28', row: 10, l: [{ col: 'AB' }] }] };
  assert.equal(sheetLink(teacher, '2026-09-28', null), 'https://s#gid=1&range=AB10:AE21');
});

test('окно и пропущенные дни', () => {
  assert.equal(windowMark('2026-09-28'), '2026-09-28/14');
  const schedule = {
    cov: ['2026-09-28', '2026-10-05'],
    days: [{ d: '2026-10-03', l: [] }, { d: '2026-10-05', l: [] }],
  };
  const days = daysWithGaps(schedule);
  assert.deepEqual(days.map((d) => d.d), ['2026-10-03', '2026-10-04', '2026-10-05']);
  assert.equal(days[1].absent, true);
  // За краем cov дни не выдумываются.
  const outside = daysWithGaps({ cov: ['2026-09-28', '2026-10-03'], days: schedule.days });
  assert.deepEqual(outside.map((d) => d.d), ['2026-10-03', '2026-10-05']);
  assert.equal(dayIndex(days, '2026-10-04'), 1);
  assert.equal(dayIndex(days, '2026-10-10'), 2);
  assert.equal(dayIndex(days, '2026-09-01'), 0);
});

test('непрочитанные дни: прежние пары с пометкой, без прежних — пустой день с пометкой', () => {
  const schedule = {
    cov: ['2026-09-28', '2026-09-29'],
    unread: ['2026-09-29', '2026-09-30'],
    days: [{ d: '2026-09-28', l: [] }, { d: '2026-09-29', l: [{ n: 1, s: 'Физика' }] }],
  };
  const days = daysWithGaps(schedule);
  assert.deepEqual(days.map((d) => [d.d, d.unread]), [
    ['2026-09-28', undefined], ['2026-09-29', 'kept'], ['2026-09-30', 'missing'],
  ]);
  // У выбранной вместе группы день не прочитан — пометка на общем дне.
  const merged = combineGroups({ gn: 'А', days: schedule.days }, [['Б', { unread: ['2026-09-28'], days: [] }]]);
  assert.deepEqual(merged.unread, ['2026-09-28']);
});

test('свободный день', () => {
  assert.equal(freeDay({ d: '2026-10-04', l: [] }), 'Выходной');
  assert.equal(freeDay({ d: '2026-10-05', l: [], absent: true }), 'Выходной');
  // 29 сентября — 272-й день: преподавателю 272 % 4 = 0, студенту (ещё одна
  // фраза) 272 % 5 = 2.
  assert.equal(freeDay({ d: '2026-09-29', l: [] }, true), 'Пар нет. Повезло');
  assert.equal(freeDay({ d: '2026-09-30', l: [] }, true), 'Пар нет. Это не ошибка');
  assert.equal(freeDay({ d: '2026-09-29', l: [] }), 'Пар нет. Совсем');
  assert.equal(freeDay({ d: 'не дата', l: [] }), 'Пар нет');
  // Следующий будний тоже пуст; сегодня до полудня — только студенту.
  assert.equal(freeDay({ d: '2026-09-29', l: [] }, false, true), 'Пар нет. Повезло дважды');
  assert.notEqual(freeDay({ d: '2026-09-29', l: [] }, true, true), 'Пар нет. Повезло дважды');
  const morning = { date: '2026-09-29', sec: 9 * 3600 };
  assert.equal(freeDay({ d: '2026-09-29', l: [] }, false, false, morning), 'Пар нет. Можно открыть шторы');
  assert.notEqual(freeDay({ d: '2026-09-29', l: [] }, false, false, { date: '2026-09-29', sec: 13 * 3600 }),
    'Пар нет. Можно открыть шторы');
});

test('идущая пара — конец включительно до секунды', () => {
  const bells = { 1: ['09:00', '10:30'], 2: ['10:40', '12:10'] };
  const at = (h, m, s) => ({ date: '2026-09-28', sec: h * 3600 + m * 60 + s });
  assert.equal(currentLessonNumber(bells, '2026-09-28', at(9, 0, 0)), 1);
  assert.equal(currentLessonNumber(bells, '2026-09-28', at(10, 30, 0)), 1);
  assert.equal(currentLessonNumber(bells, '2026-09-28', at(10, 30, 1)), null);
  assert.equal(currentLessonNumber(bells, '2026-09-28', at(10, 45, 0)), 2);
  assert.equal(currentLessonNumber(bells, '2026-09-29', at(10, 45, 0)), null);
  assert.equal(lessonTime(bells, 1), '09:00–10:30');
  assert.equal(lessonTime({ 1: ['09:00'] }, 1), '09:00');
  assert.equal(lessonTime(bells, 5), null);
});

test('другие группы: склейка, значки, подгруппы, перевод старого снимка', () => {
  const mine = {
    g: 'isp-924-1', gn: 'ИСП-924/1',
    days: [{ d: '2026-09-28', l: [
      { n: 1, s: 'Физика', r: '101' },
      { n: 2, s: 'Химия', r: '102' },
    ] }],
  };
  const theirs = {
    g: 'isp-924-2', gn: 'ИСП-924/2',
    days: [{ d: '2026-09-28', l: [
      { n: 1, s: 'Физика', r: '101' },
      { n: 2, s: 'Химия', r: '103' },
      { n: 3, s: 'Право', o: 1 },
    ] }],
  };
  const third = { g: 'isp-924-3', gn: 'ИСП-924/3', days: [{ d: '2026-09-28', l: [{ n: 2, s: 'Химия', r: '103' }] }] };
  const merged = combineGroups(mine, [['ИСП-924/2', theirs], ['ИСП-924/3', third], ['ИСП-924/4', null]]);
  assert.deepEqual(merged.groupNames, ['ИСП-924/1', 'ИСП-924/2', 'ИСП-924/3', 'ИСП-924/4']);
  // Общая — одна строка с отметками всех; разные кабинеты — разные строки,
  // своя выше; пары без своей — без 0.
  assert.deepEqual(merged.days[0].l.map((l) => [l.n, l.s, l.r || null, l.slots]), [
    [1, 'Физика', '101', [0, 1]],
    [2, 'Химия', '102', [0]],
    [2, 'Химия', '103', [1, 2]],
    [3, 'Право', null, [1]],
  ]);
  assert.equal(combineGroups(mine, []), mine);
  // Подгруппы своей группы — по номеру, без себя и без чужих.
  const groups = ['ИСП-924/2', 'ИСП-924/1', 'ИСП-9241/1', 'ИСП-924', 'ИСП-924/10'].map((n) => ({ id: n, name: n }));
  assert.deepEqual(subgroupsOf('ИСП-924/1', groups).map((g) => g.name), ['ИСП-924/2', 'ИСП-924/10']);
  assert.deepEqual(subgroupsOf('ИСП-924', groups), []);
  // Подписи у пар: подгруппы той же группы, что своя, — коротко.
  assert.deepEqual(shortLabels(['ИСП-924/1', 'ИСП-924/2', 'ИСП-925/1']), ['/1', '/2', 'ИСП-925/1']);
  assert.deepEqual(shortLabels(['ДИ-926', 'ИСП-924/2']), ['ДИ-926', 'ИСП-924/2']);
  // Снимок до веб-0.2.0 лежал склеенным с подписями — свои обратно.
  const old = { g: 'isp-924-1', gn: 'ИСП-924/1', days: [{ d: '2026-09-28', l: [
    { n: 1, s: 'Физика', r: '101' },
    { n: 2, s: 'Химия', r: '102', gr: 'ИСП-924/1' },
    { n: 2, s: 'Химия', r: '103', gr: 'ИСП-924/2' },
    { n: 3, s: 'Право', o: 1, gr: 'ИСП-924/2' },
  ] }] };
  // Свои пары обратно — без чужих и без своей подписи.
  const own = ownOnly(old);
  assert.deepEqual(own.days[0].l, mine.days[0].l);
  // У преподавателя подпись группы — не «чужая пара».
  const teacher = { kind: 'teacher', gn: 'Банкрофт', days: [{ d: 'x', l: [{ n: 1, s: 'А', gr: 'ИСП-1' }] }] };
  assert.equal(ownOnly(teacher).days[0].l.length, 1);
});

test('поиск и буквы', () => {
  assert.equal(matchesQuery('ИСП-924/1', 'исп924'), true);
  assert.equal(matchesQuery('ИСП-924/1', 'исп 924'), true);
  assert.equal(matchesQuery('Чернышёва Анна', 'чернышева'), true);
  assert.equal(matchesQuery('ИСП-924/1', 'дп'), false);
  assert.equal(matchesQuery('что угодно', '  '), true);
  assert.equal(letterOf('Ёлкина'), 'Е');
  assert.equal(letterOf('01-26.РКИ'), '0–9');
  assert.equal(letterOf('«Кавычки»'), '#');
  assert.deepEqual(lettered([{ name: 'Б1' }, { name: 'А1' }, { name: 'Б2' }], (x) => x.name)
    .map((s) => [s.letter, s.rows.length]), [['Б', 2], ['А', 1]]);
});

test('плитки выбора минут — как в приложении (tileLabel в ReminderDialog.kt)', () => {
  const labels = [10, 15, 20, 30, 45, 60, 90, 120, 180, 240].map(tileLabel);
  assert.deepEqual(labels, [
    ['10', 'мин'], ['15', 'мин'], ['20', 'мин'], ['30', 'мин'], ['45', 'мин'],
    ['1', 'час'], ['1,5', 'часа'], ['2', 'часа'], ['3', 'часа'], ['4', 'часа'],
  ]);
});
