"""
本地预览用的反向代理。

为什么需要它：
Web 版跑在 http://127.0.0.1:8899，而站点在 https://rule34video.com。
浏览器同源策略会把 fetch 拦掉，表现为界面能渲染但一张卡都没有，
控制台报 "TypeError: Failed to fetch"。

这个脚本在本地起一个转发：浏览器 → 本地代理（补 CORS 头）→ 站点。
只用于 `flutter run -d chrome` 预览，安卓包不需要它，也不走它。

用法：
    python tool/preview_proxy.py            # 默认 8898 端口
    python tool/preview_proxy.py 9000       # 自定义端口
"""
import http.server
import socketserver
import sys
import urllib.error
import urllib.request

UPSTREAM = 'https://rule34video.com'
UA = ('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36')

# 只转发这些路径，避免把本地服务变成开放代理
ALLOW_PREFIXES = (
    '/latest-updates/', '/top-rated/', '/most-popular/', '/search/',
    '/tags/', '/categories/', '/models/', '/video/', '/contents/',
    '/get_file/', '/static/', '/player/',
)
ALLOW_EXACT = ('/', '/categories', '/tags', '/latest-updates')


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        sys.stderr.write('[proxy] ' + (fmt % args) + '\n')

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.send_header(
            'Access-Control-Expose-Headers',
            'Content-Length, Content-Range, Accept-Ranges, Content-Type',
        )

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header('Content-Length', '0')
        self.end_headers()

    def _allowed(self, path):
        if path in ALLOW_EXACT:
            return True
        return any(path.startswith(p) for p in ALLOW_PREFIXES)

    def _fail(self, code, msg):
        body = msg.encode()
        self.send_response(code)
        self._cors()
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        # 关键：告诉客户端这条连接用完就关，别等 keep-alive 超时
        self.send_header('Connection', 'close')
        self.close_connection = True
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def _proxy(self):
        bare = self.path.split('?')[0]
        if not self._allowed(bare):
            self._fail(403, 'proxy: path not allowed')
            return

        req = urllib.request.Request(UPSTREAM + self.path, method='GET')
        req.add_header('User-Agent', UA)
        req.add_header('Accept', '*/*')
        req.add_header('Accept-Language', 'en-US,en;q=0.9')
        req.add_header('Referer', UPSTREAM + '/')
        # 明确要求不压缩：否则上游给 gzip、去掉 Content-Length，客户端只能等连接关闭
        req.add_header('Accept-Encoding', 'identity')
        rng = self.headers.get('Range')
        if rng:
            req.add_header('Range', rng)

        try:
            with urllib.request.urlopen(req, timeout=45) as up:
                length = up.headers.get('Content-Length')
                self.send_response(up.status)
                self._cors()
                ct = up.headers.get('Content-Type')
                if ct:
                    self.send_header('Content-Type', ct)
                for h in ('Content-Range', 'Accept-Ranges'):
                    v = up.headers.get(h)
                    if v:
                        self.send_header(h, v)
                if length:
                    self.send_header('Content-Length', length)
                else:
                    # 上游没给长度：必须用 close 定界，否则客户端会一直挂着
                    self.send_header('Connection', 'close')
                    self.close_connection = True
                self.end_headers()

                if self.command == 'HEAD':
                    return
                while True:
                    chunk = up.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except urllib.error.HTTPError as e:
            body = e.read()[:500].decode('utf-8', 'replace')
            self._fail(e.code, body or f'upstream {e.code}')
        except Exception as e:  # noqa: BLE001
            self._fail(502, f'proxy error: {e}')

    def do_GET(self):
        self._proxy()

    def do_HEAD(self):
        self._proxy()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8898
    print(f'预览代理已启动: http://127.0.0.1:{port}  ->  {UPSTREAM}')
    print('在另一个终端跑: flutter run -d chrome --dart-define=R34V_API_BASE=http://127.0.0.1:%d' % port)
    print('按 Ctrl+C 停止。')
    with Server(('127.0.0.1', port), Handler) as httpd:
        httpd.serve_forever()
