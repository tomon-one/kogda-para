#!/usr/bin/env python3
"""Сайт на этой машине — для разработки.

Отдаёт web/ на / и на /tested/ (как nginx на сервере), а /v1/ пересылает
службе: по умолчанию боевой, --api http://127.0.0.1:8081 — локальной.
Сервис-воркер здесь не ставится: страница видит «__BUILD__» и не регистрирует
его, чтобы правки были видны сразу.

    python3 tools/web_dev.py [--port 8090] [--api https://kogda-para-nsk.ru]
"""

from __future__ import annotations

import argparse
import http.server
import pathlib
import urllib.error
import urllib.request

WEB = pathlib.Path(__file__).resolve().parent.parent / "web"
TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".json": "application/json",
}
PASS_HEADERS = ("ETag", "Cache-Control", "Content-Type")
# Та же политика, что ставит nginx (server/deploy/whensclass.conf): нарушение
# видно здесь, а не на боевом.
CSP = ("default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
       "frame-ancestors 'none'")


def make_handler(api: str, web: pathlib.Path):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 — имя задаёт http.server
            path = self.path.split("?", 1)[0]
            if path.startswith("/v1/") or path == "/healthz":
                self.proxy()
                return
            if path.startswith("/download/"):
                # Сборки здесь нет — за ней на сервер.
                self.send_response(302)
                self.send_header("Location", api + path)
                self.end_headers()
                return
            if path.startswith("/tested/"):
                path = path[len("/tested"):]
            elif path == "/tested":
                self.send_response(301)
                self.send_header("Location", "/tested/")
                self.end_headers()
                return
            if path.endswith("/"):
                path += "index.html"
            file = (web / path.lstrip("/")).resolve()
            status = 200
            if web not in file.parents or not file.is_file() or "tests" in file.relative_to(web).parts:
                # Как nginx: неверный адрес — страница 404 сайта.
                file, status = web / "404.html", 404
            body = file.read_bytes()
            self.send_response(status)
            self.send_header("Content-Type", TYPES.get(file.suffix, "application/octet-stream"))
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", CSP)
            self.end_headers()
            self.wfile.write(body)

        def proxy(self) -> None:
            request = urllib.request.Request(api + self.path)
            if self.headers.get("If-None-Match"):
                request.add_header("If-None-Match", self.headers["If-None-Match"])
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    status, headers, body = response.status, response.headers, response.read()
            except urllib.error.HTTPError as error:
                status, headers, body = error.code, error.headers, error.read()
            except OSError as error:
                self.send_error(502, str(error))
                return
            self.send_response(status)
            for name in PASS_HEADERS:
                if headers.get(name):
                    self.send_header(name, headers[name])
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt: str, *args) -> None:
            print(fmt % args, flush=True)

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--api", default="https://kogda-para-nsk.ru")
    # Каталог со сборкой, как её собирает выкладка, — проверить сервис-воркер.
    parser.add_argument("--root", type=pathlib.Path, default=WEB)
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(args.api.rstrip("/"), args.root.resolve()))
    print(f"http://127.0.0.1:{args.port}/ и /tested/, служба — {args.api}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
