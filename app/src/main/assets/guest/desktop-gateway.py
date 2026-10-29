"""Loopback websockify gateway. Secrets stay in a private file, outside argv/env."""
import base64
import hmac
import json
from websockify.auth_plugins import AuthenticationError
from websockify.websocketproxy import ProxyRequestHandler, WebSocketProxy

ORIGIN = 'http://127.0.0.1:6080'
with open('/desktop-state/private/gateway.json', encoding='utf-8') as stream:
    secrets = json.load(stream)
expected_basic = 'Basic ' + base64.b64encode(('desktop:' + secrets['password']).encode()).decode()


def basic(headers):
    return hmac.compare_digest(headers.get('Authorization', ''), expected_basic)


class LocalAuthentication:
    def authenticate(self, headers, target_host, target_port):
        origin = headers.get('Origin')
        websocket = headers.get('Upgrade', '').lower() == 'websocket'
        if headers.get('Host') != '127.0.0.1:6080' or (origin is not None and origin != ORIGIN) or (websocket and origin != ORIGIN):
            raise AuthenticationError(response_code=403)
        protocols = [item.strip() for item in headers.get('Sec-WebSocket-Protocol', '').split(',')]
        if websocket:
            if not any(hmac.compare_digest(item, 'opencode-desktop.' + secrets['websocket']) for item in protocols):
                raise AuthenticationError(response_code=403)
        elif not basic(headers):
            raise AuthenticationError(response_code=401, response_headers={'WWW-Authenticate': 'Basic realm="Debian Desktop"'})


class DesktopHandler(ProxyRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://127.0.0.1:6080; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'")
        super().end_headers()


WebSocketProxy(DesktopHandler, listen_host='127.0.0.1', listen_port=6080,
               target_host='127.0.0.1', target_port=5901, web='/desktop-state/web',
               web_auth=True, auth_plugin=LocalAuthentication(), heartbeat=30).start_server()
