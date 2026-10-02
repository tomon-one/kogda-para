"""Запись файлов состояния: целиком, со своим временным файлом, только службе."""

import json
import stat
import threading

from whensclass.storage import atomic
from whensclass.storage.atomic import write_json


def test_file_is_private_and_whole(tmp_path):
    """0600: в push.json адреса подписок и их ключи, а машина общая."""
    path = tmp_path / "push.json"
    write_json(path, {"a": 1})
    assert json.loads(path.read_text("utf-8")) == {"a": 1}
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert [p.name for p in tmp_path.iterdir()] == ["push.json"], "временный файл не остался"


def test_each_write_has_its_own_temporary_file(tmp_path, monkeypatch):
    """Служба и канарейка пишут одни файлы: с общим «*.tmp» одна усекала бы
    то, что другая переименовывает. Две записи, вклинившиеся друг в друга,
    обе доходят целыми."""
    path = tmp_path / "snapshot.json"
    real = atomic.os.replace
    first_ready, second_done = threading.Event(), threading.Event()
    names = []

    def slow_replace(src, dst):
        names.append(src)
        if len(names) == 1:
            first_ready.set()
            second_done.wait(5)
        real(src, dst)

    monkeypatch.setattr(atomic.os, "replace", slow_replace)
    one = threading.Thread(target=write_json, args=(path, {"who": "служба"}))
    one.start()
    first_ready.wait(5)
    write_json(path, {"who": "канарейка"})
    second_done.set()
    one.join(5)
    assert len(set(names)) == 2, "у двух записей — два временных файла"
    assert json.loads(path.read_text("utf-8")) == {"who": "служба"}
