"""Открытый код объясняет, почему он такой, а не кто и когда это решил.

История решений — номера находок, проверки, имена, даты обсуждений — живёт
вне репозитория; в комментарии остаётся причина. Тест держит это правило для
всего, что лежит в git, кроме данных: фикстуры таблицы, эталоны, ресурсы.
"""

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Конфиги сервера ставятся на сервер копированием, и правка в них расходится с
# тем, что стоит, — их чистят вместе со следующей настоящей правкой.
SKIP = re.compile(
    r"^(server/tests/fixtures/|server/tests/golden/|android/app/src/main/res/|"
    r"docs/screenshots/|server/deploy/|server/tests/test_public_text\.py$)"
)
TEXT = re.compile(r"\.(py|kt|kts|mjs|js|sh|css|html|json|toml|txt|md|yml|properties)$")

FORBIDDEN = {
    "имя": re.compile(r"Tomon"),
    "аудит": re.compile(r"[Аа]удит(?!ор)|прогон[ауео]?\s+\d|присест|подсадн"),
    "номер находки": re.compile(r"(?<![\w.-])[КВМНW]\d{1,3}(?![\w.-])"),
    "рабочий файл": re.compile(
        r"handoff\.md|current\.md|bugs\.md|docs/deploy\.md|audit-prep|"
        r"easter-eggs|hardening-20|\.claude/"
    ),
}

# Подпись автора на экране — текст для людей, а не история.
ALLOWED = re.compile(r"Создано Tomon")


def _tracked() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [p for p in out.splitlines() if TEXT.search(p) and not SKIP.match(p)]


def test_no_history_in_public_files():
    found = []
    for rel in _tracked():
        path = ROOT / rel
        if not path.is_file():
            continue
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if ALLOWED.search(line):
                continue
            for what, rx in FORBIDDEN.items():
                if rx.search(line):
                    found.append(f"{rel}:{n}: {what}: {line.strip()[:100]}")
    assert not found, f"{len(found)} строк:\n" + "\n".join(found)
