"""Compare every module with ReportLab's independent QR implementation."""
from pathlib import Path
import re
from reportlab.graphics.barcode import qrencoder

target = "http://192.168.1.100:8192/#pin=123456"
qr = qrencoder.QRCode(4, qrencoder.QRErrorCorrectLevel.L)
qr.addData(qrencoder.QR8bitByte(target))
qr.makeImpl(False, 0)
actual = [[False] * 33 for _ in range(33)]
svg = (Path(__file__).resolve().parents[1] / "build/qr-test.svg").read_text("utf-8")
for x, y in re.findall(r'<path d="M(\d+) (\d+)h1v1h-1z"', svg):
    actual[int(y)][int(x)] = True
assert actual == qr.modules, "QR differs from independent reference"
print("PASS: all 1,089 QR modules match the independent reference")
