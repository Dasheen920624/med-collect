"""Submit a list of original-document URLs through the collector's normal task API."""
import argparse
import getpass
import hashlib
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from pathlib import Path


def targets(path):
    urls = []
    seen = set()
    for line, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        url = raw.strip()
        if not url or url.startswith("#") or url in seen:
            continue
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ("https", "http") or not parsed.hostname or len(url) > 1000:
            raise ValueError("第 %d 行的来源网址无效" % line)
        seen.add(url)
        urls.append(url)
    if not urls:
        raise ValueError("输入文件中没有来源网址")
    return urls


@contextmanager
def report_lock(path):
    with path.with_suffix(path.suffix + ".lock").open("a+b") as lock:
        if os.name == "nt":
            import msvcrt
            if not lock.tell():
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def save(path, receipt):
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(str(tmp), str(path))


class Collector:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.request("/api/csrf")
        self.request("/api/login", urllib.parse.urlencode({"username": user, "password": password}).encode(),
                     "application/x-www-form-urlencoded")
        if self.request("/api/session").get("username") != user:
            raise ValueError("采集系统登录失败")
        self.csrf = self.request("/api/csrf")

    def request(self, path, payload=None, mime="application/json"):
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers.update({"Content-Type": mime, self.csrf["headerName"]: self.csrf["token"]})
            if not isinstance(payload, bytes):
                payload = json.dumps(payload, ensure_ascii=False).encode()
        req = urllib.request.Request(self.base + path, data=payload, headers=headers)
        with self.opener.open(req, timeout=90) as response:
            body = response.read()
            if body and "json" in (response.headers.get("Content-Type") or ""):
                return json.loads(body.decode("utf-8"))
            return None

    def task(self, name, urls):
        found = self.request("/api/tasks?size=10&query=" + urllib.parse.quote(name))["rows"]
        matches = [r for r in found if r["name"] == name]
        if len(matches) > 1:
            raise ValueError("同名任务超过一个，请在采集工作台核对 " + name)
        if matches:
            return matches[0]["id"]
        task = self.request("/api/tasks", {"name": name, "mode": "URL", "captureMode": "SINGLE",
                            "urls": urls, "resultLimit": len(urls), "intervalMinutes": 0,
                            "scheduleMode": "MANUAL", "enabled": True})
        return task["id"]

    def run(self, name, urls, record, checkpoint):
        if not record.get("taskId"):
            record["taskId"] = self.task(name, urls)
            checkpoint()
        endpoint = "/api/tasks/" + record["taskId"]
        detail = self.request(endpoint)
        task = detail["task"]
        if (task["name"] != name or task["mode"] != "URL" or task["captureMode"] != "SINGLE"
                or task["resultLimit"] != len(urls) or set(detail["urls"]) != set(urls)):
            raise ValueError("现有采集任务的来源或配置已变化 " + name)
        if not record.get("runId"):
            runs = detail["runs"]
            record["runId"] = (runs[0] if runs else self.request(endpoint + "/runs", {}))["id"]
            checkpoint()
        while True:
            result = self.request("/api/runs/" + record["runId"])
            state = result["run"]["status"]
            if state != "RUNNING":
                break
            time.sleep(3)
        record.update(status=state, results=[{k: row.get(k) for k in ("url", "status", "materialId", "note")}
                                             for row in result["results"]])
        checkpoint()
        acquired = {r["url"] for r in record["results"] if r.get("materialId")}
        record["missingOriginals"] = [url for url in urls if url not in acquired]
        checkpoint()
        return len(record["missingOriginals"])


def main():
    parser = argparse.ArgumentParser(description="按来源列表分批采集原件，保存任务和原件回执。")
    parser.add_argument("file", type=Path, help="UTF-8 文本，每行一个原件网址")
    parser.add_argument("--name", required=True, help="工作台里的采集任务名称前缀")
    parser.add_argument("--base", default=os.environ.get("MEDCOLLECT_BASE", "http://127.0.0.1:18085"))
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--report", type=Path, help="可用于断点续跑的 JSON 回执，默认放在网址文件旁")
    parser.add_argument("--plan", action="store_true", help="仅检查列表并显示批次数")
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 100 or not args.name.strip() or len(args.name) > 100:
        parser.error("每批可选 1–100 个网址，名称最多 100 字")
    urls = targets(args.file)
    chunks = [urls[i:i + args.batch_size] for i in range(0, len(urls), args.batch_size)]
    print("%d 个独立网址，%d 个任务" % (len(urls), len(chunks)), flush=True)
    if args.plan:
        return 0
    path = args.report or args.file.with_suffix(args.file.suffix + ".receipt.json")
    if path.resolve() == args.file.resolve():
        raise ValueError("回执和来源列表需要各自的文件路径")
    with report_lock(path):
        signature = hashlib.sha256("\n".join(urls).encode()).hexdigest()
        identity = {"base": args.base.rstrip("/"), "name": args.name, "batchSize": args.batch_size, "sha256": signature}
        receipt = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"input": identity, "batches": {}}
        if receipt.get("input") != identity:
            raise ValueError("回执属于另一份来源或配置，请为这一批指定单独的 --report")
        user = os.environ.get("MEDCOLLECT_REVIEW_USER") or input("采集账号 ").strip()
        password = os.environ.get("MEDCOLLECT_REVIEW_PASSWORD") or getpass.getpass("密码 ")
        client = Collector(args.base, user, password)
        missing = 0
        for index, chunk in enumerate(chunks, 1):
            key = hashlib.sha256("\n".join(chunk).encode()).hexdigest()[:8]
            name = args.name + " · " + key
            record = receipt["batches"].setdefault(key, {})
            missing += client.run(name, chunk, record, lambda: save(path, receipt))
            print("%d/%d  %s  原件 %d/%d" % (index, len(chunks), record["status"],
                  len(chunk) - len(record["missingOriginals"]), len(chunk)), flush=True)
        return 2 if missing else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, urllib.error.HTTPError) as exc:
        sys.exit("采集中断，可使用同一份回执继续。%s" % exc)
