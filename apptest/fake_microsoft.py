import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
state = {"polls": 0, "sent": []}
MSGS = [{"id":"1","messageType":"message","deletedDateTime":None,"createdDateTime":"2026-11-02T10:00:00Z",
         "from":{"user":{"id":"other","displayName":"Anna Test"}},"body":{"contentType":"html","content":"<p>Hallo <b>Markus</b></p>"},
         "attachments":[{"name":"plan.pdf","contentUrl":"https://example.com/plan.pdf"}]},
        {"id":"0","messageType":"systemEventMessage","deletedDateTime":None,"createdDateTime":"2026-11-02T09:00:00Z","body":{"content":""}}]
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code)
        self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        n = int(self.headers.get("Content-Length",0)); body = self.rfile.read(n).decode()
        p = urlparse(self.path).path
        if p.endswith("/devicecode"):
            q = parse_qs(body); assert "Chat.ReadWrite" in q["scope"][0]
            return self.send(200, {"device_code":"DC","user_code":"ABCD-1234","verification_uri":"https://microsoft.com/devicelogin","interval":2,"expires_in":60})
        if p.endswith("/token"):
            q = parse_qs(body)
            if q["grant_type"][0].endswith("device_code"):
                state["polls"] += 1
                if state["polls"] < 2: return self.send(400, {"error":"authorization_pending"})
            return self.send(200, {"access_token":"AT","refresh_token":"RT","expires_in":3600})
        if p.startswith("/v1.0/chats/") and p.endswith("/messages"):
            assert self.headers["Authorization"] == "Bearer AT"
            m = json.loads(body); state["sent"].append(m["body"]["content"])
            MSGS.append({"id":str(len(MSGS)+5),"messageType":"message","deletedDateTime":None,"createdDateTime":"2026-11-02T11:00:00Z",
                         "from":{"user":{"id":"me1","displayName":"Markus"}},"body":m["body"]})
            print("SENT", m, flush=True)
            return self.send(201, {"id":"x"})
        self.send(404, {})
    def do_GET(self):
        p = urlparse(self.path).path
        if self.headers.get("Authorization") != "Bearer AT": return self.send(401, {"error":{"code":"InvalidAuthenticationToken","message":"no"}})
        if p == "/v1.0/me": return self.send(200, {"id":"me1","displayName":"Markus"})
        if p == "/v1.0/me/chats": return self.send(200, {"value":[
            {"id":"c1","topic":None,"lastUpdatedDateTime":"2026-11-02T10:00:00Z","members":[{"userId":"me1","displayName":"Markus"},{"userId":"other","displayName":"Anna Test"}],
             "lastMessagePreview":{"createdDateTime":"2026-11-02T10:00:00Z","body":{"contentType":"html","content":"<p>Hallo Markus</p>"}}},
            {"id":"c2","topic":"Projekt Buch","lastUpdatedDateTime":"2026-11-01T10:00:00Z","members":[],"lastMessagePreview":None}]})
        if p == "/v1.0/chats/c1/messages": return self.send(200, {"value": list(reversed(MSGS))})
        self.send(404, {})
HTTPServer(("127.0.0.1", 8766), H).serve_forever()
