#!/usr/bin/env python3
"""Temporary collaborative review room for GitHub Actions + TryCloudflare.

State is stored in SQLite on the runner disk. There is intentionally no message
retention cap; clients page through history by message id. The runner disk and
GitHub Actions lifetime are the only practical bounds.
"""
from __future__ import annotations

import json
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

from flask import Flask, Response, abort, jsonify, request, send_from_directory
from werkzeug.utils import secure_filename

ROOT = Path(os.environ.get("REVIEW_ROOM_DIR", Path(__file__).parent / "runtime"))
DB_PATH = ROOT / "room.sqlite3"
FILES_DIR = ROOT / "files"
DURATION_SECONDS = int(os.environ.get("REVIEW_ROOM_DURATION_SECONDS", "1800"))
STARTED_AT = time.time()
EXPIRES_AT = STARTED_AT + DURATION_SECONDS

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = None  # no application-level attachment size cap


def utc_now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def room_expired() -> bool:
    return time.time() >= EXPIRES_AT


def remaining_seconds() -> int:
    return max(0, int(EXPIRES_AT - time.time()))


@contextmanager
def db() -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(DB_PATH, timeout=20)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    FILES_DIR.mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.executescript(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS members (
              number INTEGER PRIMARY KEY AUTOINCREMENT,
              session TEXT UNIQUE NOT NULL,
              name TEXT NOT NULL,
              joined_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              member_number INTEGER,
              author TEXT NOT NULL,
              kind TEXT NOT NULL,
              body TEXT NOT NULL,
              created_at TEXT NOT NULL,
              attachment_id TEXT,
              FOREIGN KEY(member_number) REFERENCES members(number)
            );
            CREATE TABLE IF NOT EXISTS attachments (
              id TEXT PRIMARY KEY,
              original_name TEXT NOT NULL,
              stored_name TEXT NOT NULL,
              content_type TEXT NOT NULL,
              byte_size INTEGER NOT NULL,
              uploaded_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_messages_id ON messages(id);
            """
        )
        count = conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
        if count == 0:
            conn.execute(
                "INSERT INTO messages(member_number, author, kind, body, created_at) VALUES (?, ?, ?, ?, ?)",
                (None, "system", "system", "اتاق review آریا آماده است. موضوع فقط پروژه Arya است. هر عضو نام و شماره خودکار می‌گیرد. برای Bash راهنمای /bash را باز کنید.", utc_now()),
            )


def reject_if_expired() -> Response | None:
    if room_expired():
        return jsonify(error="این اتاق ۳۰ دقیقه‌ای منقضی شده است."), 410
    return None


def clean_name(value: object) -> str:
    name = " ".join(str(value or "").strip().split())[:64]
    return name or "بدون نام"


def clean_body(value: object) -> str:
    body = str(value or "").strip()
    if not body:
        raise ValueError("پیام خالی است.")
    # No arbitrary message-count or length retention cap. SQLite/runner disk is
    # the practical limit; this only rejects malformed request bodies.
    return body


def message_dict(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "member": row["member_number"],
        "author": row["author"],
        "kind": row["kind"],
        "body": row["body"],
        "created_at": row["created_at"],
        "attachment_id": row["attachment_id"],
    }


def session_member(session: str | None) -> sqlite3.Row:
    if not session:
        abort(401, "session موقت ارسال نشده است")
    with db() as conn:
        member = conn.execute("SELECT * FROM members WHERE session = ?", (session,)).fetchone()
    if not member:
        abort(401, "session نامعتبر است")
    return member


def insert_message(member: sqlite3.Row | None, author: str, kind: str, body: str, attachment_id: str | None = None) -> dict:
    with db() as conn:
        cursor = conn.execute(
            "INSERT INTO messages(member_number, author, kind, body, created_at, attachment_id) VALUES (?, ?, ?, ?, ?, ?)",
            (member["number"] if member else None, author, kind, body, utc_now(), attachment_id),
        )
        row = conn.execute("SELECT * FROM messages WHERE id = ?", (cursor.lastrowid,)).fetchone()
    return message_dict(row)


@app.get("/health")
def health() -> Response:
    with db() as conn:
        members = conn.execute("SELECT COUNT(*) FROM members").fetchone()[0]
        messages = conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    return jsonify(ok=not room_expired(), remaining_seconds=remaining_seconds(), members=members, messages=messages, storage="sqlite runner disk")


@app.get("/")
def index() -> Response:
    if room_expired():
        return Response("اتاق منقضی شده است.", status=410, content_type="text/plain; charset=utf-8")
    return Response(PAGE, content_type="text/html; charset=utf-8")


@app.get("/bash")
def bash_guide() -> Response:
    origin = request.url_root.rstrip("/")
    return Response(bash_text(origin), content_type="text/plain; charset=utf-8")


@app.post("/api/join")
def join() -> Response:
    expired = reject_if_expired()
    if expired:
        return expired
    data = request.get_json(silent=True) or {}
    name = clean_name(data.get("name"))
    session = secrets.token_urlsafe(24)
    with db() as conn:
        cursor = conn.execute("INSERT INTO members(session, name, joined_at) VALUES (?, ?, ?)", (session, name, utc_now()))
        number = cursor.lastrowid
        conn.execute(
            "INSERT INTO messages(member_number, author, kind, body, created_at) VALUES (?, ?, ?, ?, ?)",
            (number, "system", "system", f"عضو {number} — {name} از Bash/Web متصل شد.", utc_now()),
        )
        last_id = conn.execute("SELECT MAX(id) FROM messages").fetchone()[0] or 0
    return jsonify(ok=True, member={"number": number, "name": name}, session=session, last_message_id=last_id, poll_every_seconds=20, remaining_seconds=remaining_seconds()), 201


@app.post("/api/message")
def send_message() -> Response:
    expired = reject_if_expired()
    if expired:
        return expired
    data = request.get_json(silent=True) or {}
    member = session_member(data.get("session"))
    try:
        body = clean_body(data.get("body"))
    except ValueError as exc:
        return jsonify(error=str(exc)), 400
    kind = data.get("kind") if data.get("kind") in {"message", "code", "status"} else "message"
    message = insert_message(member, f"عضو {member['number']} — {member['name']}", kind, body)
    return jsonify(ok=True, message=message)


@app.get("/api/poll")
def poll() -> Response:
    expired = reject_if_expired()
    if expired:
        return expired
    member = session_member(request.args.get("session"))
    after = max(0, request.args.get("after", default=0, type=int) or 0)
    # Page size is transport/UI pagination, not a retention cap.
    limit = max(1, min(request.args.get("limit", default=200, type=int) or 200, 10000))
    with db() as conn:
        rows = conn.execute("SELECT * FROM messages WHERE id > ? ORDER BY id ASC LIMIT ?", (after, limit)).fetchall()
    messages = [message_dict(row) for row in rows]
    last_id = messages[-1]["id"] if messages else after
    return jsonify(ok=True, member={"number": member["number"], "name": member["name"]}, messages=messages, last_message_id=last_id, remaining_seconds=remaining_seconds())


@app.get("/api/history")
def history() -> Response:
    expired = reject_if_expired()
    if expired:
        return expired
    before = request.args.get("before", type=int)
    limit = max(1, min(request.args.get("limit", default=200, type=int) or 200, 10000))
    with db() as conn:
        if before:
            rows = conn.execute("SELECT * FROM messages WHERE id < ? ORDER BY id DESC LIMIT ?", (before, limit)).fetchall()
        else:
            rows = conn.execute("SELECT * FROM messages ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    messages = [message_dict(row) for row in reversed(rows)]
    return jsonify(messages=messages, next_before=messages[0]["id"] if messages else None, remaining_seconds=remaining_seconds())


@app.post("/api/upload")
def upload() -> Response:
    expired = reject_if_expired()
    if expired:
        return expired
    member = session_member(request.form.get("session"))
    file = request.files.get("file")
    if not file or not file.filename:
        return jsonify(error="فایل انتخاب نشده است."), 400
    attachment_id = uuid.uuid4().hex
    original = secure_filename(file.filename) or "upload.bin"
    stored = f"{attachment_id}_{original}"
    destination = FILES_DIR / stored
    file.save(destination)  # runner disk is the practical size bound; no app cap
    size = destination.stat().st_size
    with db() as conn:
        conn.execute(
            "INSERT INTO attachments(id, original_name, stored_name, content_type, byte_size, uploaded_at) VALUES (?, ?, ?, ?, ?, ?)",
            (attachment_id, original, stored, file.mimetype or "application/octet-stream", size, utc_now()),
        )
    message = insert_message(member, f"عضو {member['number']} — {member['name']}", "file", f"فایل «{original}» را ارسال کرد.", attachment_id)
    return jsonify(ok=True, message=message, attachment={"id": attachment_id, "name": original, "size": size})


@app.get("/api/file/<attachment_id>")
def download(attachment_id: str) -> Response:
    with db() as conn:
        attachment = conn.execute("SELECT * FROM attachments WHERE id = ?", (attachment_id,)).fetchone()
    if not attachment:
        abort(404)
    return send_from_directory(FILES_DIR, attachment["stored_name"], as_attachment=True, download_name=attachment["original_name"], mimetype=attachment["content_type"])


def bash_text(origin: str) -> str:
    return f'''# Arya review room — Bash client

BASE="{origin}"
AGENT_NAME="YourName"

# Join once. The room assigns a stable member number and temporary session.
JOIN=$(curl -fsS -X POST "$BASE/api/join" \\
  -H 'content-type: application/json' \\
  --data "{{\"name\":\"$AGENT_NAME\"}}")

export SESSION=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["session"])' <<< "$JOIN")
export MEMBER=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["member"]["number"])' <<< "$JOIN")
export LAST=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["last_message_id"])' <<< "$JOIN")

echo "I am member $MEMBER"

# First message
python3 - <<'PY' >/tmp/arya-first-message.json
import json, os
print(json.dumps({{
  "session": os.environ["SESSION"],
  "body": f"#agent {{os.environ['MEMBER']}}\\nHi — I am ready.",
  "kind": "message"
}}))
PY
curl -fsS -X POST "$BASE/api/message" -H 'content-type: application/json' --data @/tmp/arya-first-message.json

# 30-minute room worker. Run this with nohup; do not Ctrl+C it.
while true; do
  POLL=$(curl -fsS "$BASE/api/poll?session=$SESSION&after=$LAST&limit=200")
  echo "$POLL" | python3 -c '
import json,sys
for m in json.load(sys.stdin).get("messages", []):
 print(f"[{{m[\"id\"]}}] {{m[\"author\"]}}: {{m[\"body\"]}}")
'
  LAST=$(echo "$POLL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["last_message_id"])')
  sleep 20
done

# Send a status/result after independent research:
python3 - <<'PY' >/tmp/arya-result.json
import json, os
print(json.dumps({{
  "session": os.environ["SESSION"],
  "kind": "message",
  "body": f"#agent {{os.environ['MEMBER']}}\\nStatus: result\\nFinding: ...\\nEvidence: file/class/function\\nImpact: ...\\nProposal: ..."
}}))
PY
curl -fsS -X POST "$BASE/api/message" -H 'content-type: application/json' --data @/tmp/arya-result.json

# Upload any runner-disk-sized file:
curl -fsS -X POST "$BASE/api/upload" -F "session=$SESSION" -F "file=@./review.md"

Rules:
- Topic is only Arya.
- Keep #agent NUMBER stable.
- Keep the Bash worker alive while researching; send Status then Result.
- There is no application-level message retention cap.
'''


PAGE = r'''<!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Arya Review Room</title>
<style>
:root{--bg:#0a1020;--card:#151e38;--line:#2d3c62;--ink:#edf3ff;--muted:#aab9da;--accent:#8df0b3;--blue:#63b3ff}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:linear-gradient(135deg,#090e1b,#15213e);color:var(--ink);font:15px system-ui,-apple-system,sans-serif}main{max-width:1150px;margin:auto;padding:20px}.head{display:flex;justify-content:space-between;gap:18px;border-bottom:1px solid var(--line);padding-bottom:14px}h1{margin:0;font-size:26px}.sub,.meta,small{color:var(--muted)}.badge{background:#20345f;color:var(--accent);padding:9px 12px;border-radius:9px;font-weight:700;white-space:nowrap}.notice{margin:15px 0;padding:12px;border:1px solid #6a5c25;background:#302a12;color:#fff0ae;border-radius:10px;line-height:1.7}.setup{display:flex;gap:8px;background:var(--card);border:1px solid var(--line);padding:10px;border-radius:10px}.setup input{flex:1}.chat{margin-top:14px;background:rgba(21,30,56,.95);border:1px solid var(--line);border-radius:12px;overflow:hidden}.toolbar{padding:10px;border-bottom:1px solid var(--line);display:flex;gap:8px;align-items:center}.messages{height:50vh;min-height:380px;overflow:auto;padding:14px}.message{background:#101a32;border-right:3px solid #5a80dc;border-radius:8px;padding:10px 12px;margin-bottom:10px;line-height:1.8;white-space:pre-wrap}.message.code{border-right-color:var(--blue);font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.message.file{border-right-color:var(--accent)}.message.system{border-right-color:#f2cd5e;background:#28230f}.who{color:var(--accent);font-weight:700}.composer{padding:12px;border-top:1px solid var(--line);display:grid;grid-template-columns:130px 1fr auto;gap:8px}textarea,input,select,button{font:inherit;border-radius:8px;border:1px solid var(--line);padding:10px;color:var(--ink);background:#0c152a}textarea{min-height:75px;resize:vertical}button{background:#27674e;border-color:#499c77;font-weight:700;cursor:pointer}button.secondary{background:#24375f;border-color:#456ba7}.upload{padding:0 12px 12px;display:flex;gap:8px;align-items:center}@media(max-width:700px){main{padding:12px}.head{display:block}.badge{display:inline-block;margin-top:10px}.composer{grid-template-columns:1fr}.setup{flex-direction:column}.messages{height:46vh}}</style></head>
<body><main><section class="head"><div><h1>گروه review پروژه Arya</h1><div class="sub">چت، کد، فایل، تاریخچهٔ SQLite و Bash API برای بررسی زندهٔ پروژه.</div></div><div id="badge" class="badge">در حال اتصال…</div></section>
<div class="notice">برای Bash/CLI: <a href="/bash">/bash</a> را باز کن. برای تحقیق، worker Bash را باز نگه دار، پیام Status بفرست و بعد Result را ثبت کن. پیام‌ها retention cap ندارند؛ فقط نمایش صفحه‌ای است.</div>
<section id="setup" class="setup"><input id="name" placeholder="نام دلخواه"><button id="join">ورود به گروه</button><span id="status"></span></section>
<section class="chat"><div class="toolbar"><button id="older" class="secondary">پیام‌های قدیمی‌تر</button><small id="olderNote"></small></div><div id="messages" class="messages"></div><div class="composer"><select id="kind"><option value="message">پیام</option><option value="code">کد</option><option value="status">Status</option></select><textarea id="body" placeholder="پیام یا کد خود را بنویس… Ctrl+Enter برای ارسال"></textarea><button id="send">ارسال</button></div><div class="upload"><input id="file" type="file"><button id="upload">آپلود فایل</button></div></section></main>
<script>
let session=null,member=null,last=0,oldest=null;const seen=new Set(),$=x=>document.getElementById(x),esc=s=>String(s||'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function render(m,prepend=false){if(!m||seen.has(m.id))return;seen.add(m.id);last=Math.max(last,m.id);oldest=oldest===null?m.id:Math.min(oldest,m.id);let e=document.createElement('article');e.className='message '+m.kind;let file=m.attachment_id?'<br><a href="/api/file/'+esc(m.attachment_id)+'">📎 دانلود فایل</a>':'';e.innerHTML='<div class="meta"><span class="who">'+esc(m.author)+'</span> · '+esc(m.created_at)+'</div>'+esc(m.body)+file;let box=$('messages');if(prepend)box.prepend(e);else{box.appendChild(e);box.scrollTop=box.scrollHeight}}
async function loadLatest(){let d=await fetch('/api/history?limit=200').then(r=>r.json());d.messages.forEach(render)}
async function join(){let name=$('name').value.trim()||'بدون نام';let r=await fetch('/api/join',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({name})});let d=await r.json();if(!r.ok){$('status').textContent=d.error;return}session=d.session;member=d.member;last=d.last_message_id;$('badge').textContent='عضو '+member.number+' — '+member.name;$('setup').style.display='none';await loadLatest();poll()}
async function send(){if(!session)return;let body=$('body').value.trim();if(!body)return;let r=await fetch('/api/message',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({session,body,kind:$('kind').value})});let d=await r.json();if(!r.ok){$('status').textContent=d.error;return}$('body').value='';render(d.message)}
async function poll(){if(!session)return;try{let d=await fetch('/api/poll?session='+encodeURIComponent(session)+'&after='+last+'&limit=200').then(r=>r.json());if(d.error){$('status').textContent=d.error;return}(d.messages||[]).forEach(render)}catch(_){$('status').textContent='ارتباط موقتاً قطع است'}setTimeout(poll,2000)}
async function older(){if(!oldest)return;let d=await fetch('/api/history?before='+oldest+'&limit=200').then(r=>r.json());if(!d.messages.length){$('older').disabled=true;$('olderNote').textContent='پیام قدیمی‌تری نیست';return}let h=$('messages').scrollHeight;for(let i=d.messages.length-1;i>=0;i--)render(d.messages[i],true);$('messages').scrollTop=$('messages').scrollHeight-h}
async function upload(){if(!session)return;let f=$('file').files[0];if(!f)return;let form=new FormData();form.append('session',session);form.append('file',f);let r=await fetch('/api/upload',{method:'POST',body:form});let d=await r.json();if(!r.ok){$('status').textContent=d.error;return}render(d.message);$('file').value=''}
$('join').onclick=join;$('send').onclick=send;$('older').onclick=older;$('upload').onclick=upload;$('body').onkeydown=e=>{if(e.ctrlKey&&e.key==='Enter'){e.preventDefault();send()}};loadLatest();
</script></body></html>'''


if __name__ == "__main__":
    init_db()
    app.run(host="127.0.0.1", port=int(os.environ.get("PORT", "8765")), threaded=True)
