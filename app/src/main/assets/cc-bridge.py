#!/usr/bin/env python3
"""
Arin's Claude Code bridge: lets Claude Code (which speaks Anthropic's Messages API) use any
OpenAI-compatible API as its brain. Runs inside Arin's Linux on 127.0.0.1 only.

  Claude Code --(Anthropic /v1/messages)--> this bridge --(OpenAI /chat/completions)--> your API

Translates system prompts, text, images, tool definitions, tool calls and tool results, and turns the
OpenAI stream back into Anthropic stream events (text deltas and tool_use input_json deltas).
Standard library only.
"""
import json
import os
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = os.environ.get("ARIN_UPSTREAM", "").rstrip("/")   # e.g. https://api.example.com/v1
KEY = os.environ.get("ARIN_KEY", "")
PORT = int(os.environ.get("ARIN_PORT", "8787"))
THINK_LESS = os.environ.get("ARIN_THINK_LESS", "0") == "1"   # Arin: Settings → Think less


def as_blocks(content):
    if isinstance(content, str):
        return [{"type": "text", "text": content}]
    return content or []


def block_text(blocks):
    return "\n".join(b.get("text", "") for b in blocks if b.get("type") == "text")


def to_openai(req):
    msgs = []
    system = req.get("system")
    if system:
        text = system if isinstance(system, str) else block_text(system)
        if text:
            msgs.append({"role": "system", "content": text})
    for m in req.get("messages", []):
        blocks = as_blocks(m.get("content"))
        if m.get("role") == "user":
            parts = []
            tool_images = []
            for b in blocks:
                t = b.get("type")
                if t == "tool_result":
                    c = b.get("content")
                    if isinstance(c, list):
                        # Images a tool returned (e.g. Claude Code reading a photo) can't go in a tool message:
                        # send them right after, as a user message.
                        for x in c:
                            src = x.get("source", {}) if x.get("type") == "image" else {}
                            if src.get("type") == "base64":
                                tool_images.append({"type": "image_url", "image_url": {
                                    "url": f"data:{src.get('media_type')};base64,{src.get('data')}"}})
                        c = block_text(c) or ("[image]" if tool_images else "")
                    msgs.append({"role": "tool", "tool_call_id": b.get("tool_use_id"),
                                 "content": ("ERROR: " if b.get("is_error") else "") + (c or "")})
                elif t == "text":
                    parts.append({"type": "text", "text": b.get("text", "")})
                elif t == "image":
                    s = b.get("source", {})
                    if s.get("type") == "base64":
                        parts.append({"type": "image_url",
                                      "image_url": {"url": f"data:{s.get('media_type')};base64,{s.get('data')}"}})
            if tool_images:
                msgs.append({"role": "user", "content": [{"type": "text", "text": "The image from the tool result:"}] + tool_images})
            if parts:
                if all(p["type"] == "text" for p in parts):
                    msgs.append({"role": "user", "content": "\n".join(p["text"] for p in parts)})
                else:
                    msgs.append({"role": "user", "content": parts})
        else:
            text = "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
            calls = [{"id": b.get("id"), "type": "function",
                      "function": {"name": b.get("name"), "arguments": json.dumps(b.get("input", {}))}}
                     for b in blocks if b.get("type") == "tool_use"]
            msg = {"role": "assistant", "content": text or None}
            if calls:
                msg["tool_calls"] = calls
            msgs.append(msg)
    out = {"model": req.get("model"), "messages": msgs, "stream": True}
    if THINK_LESS:
        # Brief reasoning: thinking is billed as output. DeepSeek: thinking off.
        out["reasoning_effort"] = "low"
        if "deepseek" in (req.get("model") or "").lower():
            out["thinking"] = {"type": "disabled"}
    if req.get("max_tokens"):
        out["max_tokens"] = req["max_tokens"]
    if req.get("temperature") is not None:
        out["temperature"] = req["temperature"]
    tools = [t for t in (req.get("tools") or []) if t.get("name") and t.get("input_schema")]
    if tools:
        out["tools"] = [{"type": "function", "function": {
            "name": t["name"], "description": t.get("description", ""), "parameters": t["input_schema"]}} for t in tools]
        tc = (req.get("tool_choice") or {}).get("type")
        if tc == "any":
            out["tool_choice"] = "required"
        elif tc == "tool":
            out["tool_choice"] = {"type": "function", "function": {"name": req["tool_choice"].get("name")}}
    return out


def upstream_stream(body):
    data = json.dumps(body).encode()
    r = urllib.request.Request(UPSTREAM + "/chat/completions", data=data, method="POST", headers={
        "Content-Type": "application/json", "Authorization": f"Bearer {KEY}", "Accept": "text/event-stream"})
    return urllib.request.urlopen(r, timeout=600)


def translate(resp, model, emit):
    """Read the OpenAI stream and call emit(event, data) with Anthropic stream events."""
    emit("message_start", {"type": "message_start", "message": {
        "id": "msg_" + uuid.uuid4().hex[:24], "type": "message", "role": "assistant", "model": model,
        "content": [], "stop_reason": None, "stop_sequence": None,
        "usage": {"input_tokens": 0, "output_tokens": 0}}})
    state = {"idx": -1, "open": None}
    tools = {}
    stop = "end_turn"
    usage_out = 0

    def close():
        if state["open"] is not None:
            emit("content_block_stop", {"type": "content_block_stop", "index": state["open"]})
            state["open"] = None

    def start(block):
        close()
        state["idx"] += 1
        state["open"] = state["idx"]
        emit("content_block_start", {"type": "content_block_start", "index": state["idx"], "content_block": block})
        return state["idx"]

    text_idx = None
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]":
            break
        try:
            j = json.loads(payload)
        except ValueError:
            continue
        if j.get("usage"):
            usage_out = j["usage"].get("completion_tokens") or usage_out
        for ch in j.get("choices") or []:
            d = ch.get("delta") or {}
            if d.get("content"):
                if text_idx is None or state["open"] != text_idx:
                    text_idx = start({"type": "text", "text": ""})
                emit("content_block_delta", {"type": "content_block_delta", "index": text_idx,
                                             "delta": {"type": "text_delta", "text": d["content"]}})
            for tc in d.get("tool_calls") or []:
                ti = tc.get("index", 0)
                fn = tc.get("function") or {}
                if ti not in tools:
                    text_idx = None
                    tools[ti] = start({"type": "tool_use", "id": tc.get("id") or "toolu_" + uuid.uuid4().hex[:20],
                                       "name": fn.get("name", ""), "input": {}})
                if fn.get("arguments"):
                    emit("content_block_delta", {"type": "content_block_delta", "index": tools[ti],
                                                 "delta": {"type": "input_json_delta", "partial_json": fn["arguments"]}})
            fr = ch.get("finish_reason")
            if fr:
                stop = {"tool_calls": "tool_use", "function_call": "tool_use", "length": "max_tokens"}.get(fr, "end_turn")
    close()
    if tools and stop == "end_turn":
        stop = "tool_use"
    emit("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None},
                           "usage": {"output_tokens": usage_out}})
    emit("message_stop", {"type": "message_stop"})


def collect(events):
    """Build a whole (non-streamed) Anthropic message from the stream events."""
    msg, blocks, partial = None, {}, {}
    for name, data in events:
        if name == "message_start":
            msg = data["message"]
        elif name == "content_block_start":
            blocks[data["index"]] = dict(data["content_block"])
        elif name == "content_block_delta":
            dl = data["delta"]
            if dl["type"] == "text_delta":
                blocks[data["index"]]["text"] += dl["text"]
            else:
                partial[data["index"]] = partial.get(data["index"], "") + dl["partial_json"]
        elif name == "message_delta":
            msg["stop_reason"] = data["delta"]["stop_reason"]
            msg["usage"]["output_tokens"] = data["usage"]["output_tokens"]
    for i, js in partial.items():
        try:
            blocks[i]["input"] = json.loads(js or "{}")
        except ValueError:
            blocks[i]["input"] = {}
    msg["content"] = [blocks[i] for i in sorted(blocks)]
    return msg


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self.send_json(200, {"ok": True, "upstream": UPSTREAM})
        else:
            self.send_json(404, {"type": "error", "error": {"type": "not_found_error", "message": self.path}})

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self.send_json(400, {"type": "error", "error": {"type": "invalid_request_error", "message": "bad JSON"}})
        path = self.path.split("?")[0]
        if path.endswith("/count_tokens"):
            return self.send_json(200, {"input_tokens": max(1, len(json.dumps(req)) // 4)})
        if not path.endswith("/messages"):
            return self.send_json(404, {"type": "error", "error": {"type": "not_found_error", "message": path}})
        try:
            body = to_openai(req)
            try:
                resp = upstream_stream(body)
            except urllib.error.HTTPError as e:
                # The API refused the think-less fields: send again without them.
                detail = e.read().decode("utf-8", "replace")
                if e.code == 400 and ("reasoning_effort" in body or "thinking" in body) and any(
                        w in detail.lower() for w in ("reasoning", "thinking", "unrecognized", "unknown", "unsupported", "extra")):
                    body.pop("reasoning_effort", None)
                    body.pop("thinking", None)
                    resp = upstream_stream(body)
                else:
                    raise urllib.error.HTTPError(e.url, e.code, e.msg, e.hdrs, __import__("io").BytesIO(detail.encode()))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:2000]
            kind = {401: "authentication_error", 403: "permission_error", 404: "not_found_error",
                    429: "rate_limit_error"}.get(e.code, "api_error")
            return self.send_json(e.code, {"type": "error", "error": {"type": kind, "message": detail or str(e)}})
        except Exception as e:
            return self.send_json(502, {"type": "error", "error": {"type": "api_error", "message": f"upstream: {e}"}})

        model = req.get("model", "")
        if req.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            def emit(name, data):
                self.wfile.write(f"event: {name}\ndata: {json.dumps(data)}\n\n".encode())
                self.wfile.flush()
            try:
                translate(resp, model, emit)
            except Exception as e:
                emit("error", {"type": "error", "error": {"type": "api_error", "message": str(e)}})
        else:
            events = []
            translate(resp, model, lambda n, d: events.append((n, d)))
            self.send_json(200, collect(events))


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
