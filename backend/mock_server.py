"""Temporary VeriVox API used to test the Android-to-PC connection.

This endpoint intentionally returns a mock result. It does not run the
deepfake model yet. Once the phone can reach this service, we will replace the
response with the real Flask/PyTorch inference code.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time

HOST = "0.0.0.0"
PORT = 5000
MAX_UPLOAD_BYTES = 10 * 1024 * 1024


class VeriVoxHandler(BaseHTTPRequestHandler):
    def _send_json(self, status_code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path == "/health":
            self._send_json(200, {"status": "ok", "service": "verivox-mock"})
            return
        self._send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path != "/v1/analyze":
            self._send_json(404, {"error": "not_found"})
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length <= 0:
            self._send_json(400, {"error": "empty_request"})
            return
        if content_length > MAX_UPLOAD_BYTES + 1024 * 1024:
            self._send_json(413, {"error": "file_too_large"})
            return

        # Consume the multipart body so the HTTP connection completes cleanly.
        self.rfile.read(content_length)

        self._send_json(
            200,
            {
                "request_id": f"mock-{int(time.time() * 1000)}",
                "status": "completed",
                "label": "mock",
                "prob_real": 0.50,
                "prob_fake": 0.50,
                "model_version": "mock-v0",
                "message": "這是連線測試結果，尚未使用真正的 deepfake 模型。",
            },
        )

    def log_message(self, format: str, *args) -> None:
        print(f"[VeriVox] {self.client_address[0]} - {format % args}")


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), VeriVoxHandler)
    print(f"VeriVox mock API listening on http://0.0.0.0:{PORT}")
    print("Health check: http://127.0.0.1:5000/health")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping VeriVox mock API.")
    finally:
        server.server_close()
