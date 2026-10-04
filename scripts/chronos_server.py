"""A local forecasting service: Chronos, a pretrained time-series model, behind two HTTP routes.

The demand model's contest (DemandTrainer) asks it for a forecast the same way it asks the forest and the
smoothers: weekly units up to a week, and nothing after it. The model is not trained on the tenant's data; it
arrives pretrained and only reads the series it is given.

    POST /forecast   {"contexts": [[units a week, oldest first], ...], "horizon": 4}
                 ->  {"model": "...", "means": [weekly units expected over the horizon, one per context]}
    GET  /health ->  {"status": "UP", "model": "..."}

Run it with the Python that has torch and chronos-forecasting installed:

    python chronos_server.py

CHRONOS_MODEL picks the model (default amazon/chronos-2, about 900 MB of memory; amazon/chronos-bolt-small is the
lighter one at about 600 MB), CHRONOS_PORT the port (default 8090). The
API finds it through CHRONOS_URL=http://localhost:8090; with that unset the contest runs without it.
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import torch
from chronos import BaseChronosPipeline

MODEL = os.environ.get("CHRONOS_MODEL", "amazon/chronos-2")
PORT = int(os.environ.get("CHRONOS_PORT", "8090"))
BATCH = 64
# The model answers in quantiles. Lumpy sales have a median of zero in most weeks, so the middle one alone
# would forecast nothing; the average across the quantiles is the expected units.
QUANTILES = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]

torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
pipeline = BaseChronosPipeline.from_pretrained(MODEL, device_map="cpu", torch_dtype=torch.float32)


def forecast(contexts, horizon):
    means = []
    for start in range(0, len(contexts), BATCH):
        batch = [torch.tensor(c, dtype=torch.float32) for c in contexts[start:start + BATCH]]
        with torch.no_grad():
            quantiles, _ = pipeline.predict_quantiles(batch, prediction_length=horizon, quantile_levels=QUANTILES)
        for i in range(len(batch)):
            # One series: (horizon, quantiles), with a leading axis of one on models that take several variates.
            q = torch.as_tensor(quantiles[i]).float().reshape(-1, len(QUANTILES))[-horizon:]
            means.append(float(q.clamp(min=0).mean()))
    return means


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    return data
                data += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "UP", "model": MODEL})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/forecast":
            self._send(404, {"error": "not found"})
            return
        try:
            request = json.loads(self._body())
            contexts = request["contexts"]
            horizon = int(request.get("horizon", 4))
            if not 1 <= horizon <= 52 or any(len(c) == 0 for c in contexts):
                raise ValueError("horizon must be 1 to 52 and every context needs at least one week")
            self._send(200, {"model": MODEL, "means": forecast(contexts, horizon)})
        except (KeyError, ValueError, TypeError) as ex:
            self._send(400, {"error": str(ex)})

    def log_message(self, fmt, *args):
        sys.stderr.write("chronos %s\n" % (fmt % args))


if __name__ == "__main__":
    print(f"Chronos ({MODEL}) listening on http://localhost:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
