// Сверки без браузера: список файлов сервис-воркера и синтаксис для старых
// браузеров.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('..', import.meta.url));

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'tests' ? [] : walk(path);
    return [relative(WEB, path)];
  });
}

// Превью ссылки берут мессенджеры, странице оно не нужно — в кэш не идёт.
const NOT_CACHED = ['sw.js', 'assets/og.jpg'];

test('сервис-воркер сохраняет все файлы сайта, и только их', () => {
  const sw = readFileSync(join(WEB, 'sw.js'), 'utf8');
  const listed = JSON.parse(sw.match(/var FILES = (\[[\s\S]*?\]);/)[1].replace(/'/g, '"').replace(/,\s*\]/, ']'));
  const onDisk = walk(WEB).filter((f) => !NOT_CACHED.includes(f)).map((f) => (f === 'index.html' ? './' : f));
  assert.deepEqual([...listed].sort(), [...onDisk].sort());
});

// Модули понимают Safari 11, Chrome 61, Firefox 60 — ES2017. Синтаксис новее
// там роняет модуль целиком, и страница остаётся пустой. Разборщика под рукой
// нет, поэтому — по приметам.
const NEWER = [
  [/\?\.[\w$[(]/, 'необязательная цепочка ?.'],
  [/\?\?/, 'оператор ??'],
  [/\.\.\./, 'разворот ...'],
  [/catch\s*\{/, 'catch без параметра'],
  // clients.matchAll сервис-воркера — не String.prototype.matchAll.
  [/(?<!clients)\.(flat|flatMap|matchAll|replaceAll|at|findLast)\(/, 'метод новее ES2017'],
  [/Object\.(fromEntries|hasOwn)\b/, 'метод новее ES2017'],
  [/\\p\{/, 'классы Юникода в регулярке'],
  [/\bimport\(/, 'динамический импорт'],
  [/\bglobalThis\b/, 'globalThis'],
  [/\*\*/, 'возведение в степень **'],
  [/(^|[^\w$])#[a-zA-Z_]\w*\s*[=(;]/, 'закрытые поля класса'],
  [/\d_\d/, 'разделители в числах 1_000'],
  [/\(\?<[=!a-zA-Z]/, 'просмотр назад или именованные группы в регулярке'],
  [/(\|\||&&)=/, 'логическое присваивание ||= и &&='],
  [/\bimport\.meta\b/, 'import.meta'],
  [/\bclass\s/, 'классы — без них проще'],
  [/=>/, 'стрелки — пишем function, как весь код'],
];

test('синтаксис не новее ES2017', () => {
  const files = walk(WEB).filter((f) => f.endsWith('.js'));
  const problems = [];
  for (const file of files) {
    // Блочные комментарии — пробелами, строки остаются на местах.
    const text = readFileSync(join(WEB, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
    const lines = text.split('\n');
    lines.forEach((line, i) => {
      const code = line.replace(/\/\/.*$/, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
      for (const [pattern, what] of NEWER) {
        if (pattern.test(code)) problems.push(`${file}:${i + 1}: ${what}`);
      }
    });
  }
  assert.deepEqual(problems, []);
});

// boot.js — не модуль: он должен выполниться и в браузере, которому модули не
// по силам, чтобы сказать «слишком старый». Значит, ES5 (аудит сайта, W3).
test('boot.js — ES5', () => {
  const code = readFileSync(join(WEB, 'assets/boot.js'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '').replace(/'(?:[^'\\]|\\.)*'/g, "''");
  for (const [pattern, what] of [[/\b(const|let|class)\s/, 'const, let, class'], [/=>/, 'стрелки'], [/`/, 'шаблонные строки'],
    [/\.\.\./, 'разворот']]) {
    assert.doesNotMatch(code, pattern, what);
  }
});
