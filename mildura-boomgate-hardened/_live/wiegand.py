"""
wiegand.py — LA5353 / Sebury keypad reader (Wiegand 26-bit)
PIN is sent as a 26-bit frame when # is pressed after the PIN.
Manual: PIN 111111 + # transmits as 00111111 in the 24-bit data field.

Wiring (LA5353):
  Green (D0)  -> BCM17 pin 11
  White (D1)  -> BCM27 pin 13
  Pink (GND signal) -> Pi GND pin 6/9  *** REQUIRED ***
  Red + Black -> 12V supply

Bit polarity:
  Standard Wiegand: D0=0, D1=1.
  Some site builds historically used inverted mapping. Set
  WIEGAND_INVERT_BITS=1 to restore the old inverted behaviour if a
  redeploy suddenly rejects every physical PIN.
"""
from __future__ import annotations

import os
import threading
import time

D0_PIN = 17
D1_PIN = 27
FRAME_GAP = 0.30  # silence (s) marking end of a Wiegand frame
INVERT_BITS = os.environ.get("WIEGAND_INVERT_BITS", "0").strip() in (
    "1",
    "true",
    "TRUE",
    "yes",
    "YES",
)


class WiegandReader:
    def __init__(self, d0_pin=D0_PIN, d1_pin=D1_PIN, callback=None):
        self.d0_pin = d0_pin
        self.d1_pin = d1_pin
        self.callback = callback
        self._bits = []
        self._last = 0.0
        self._lock = threading.Lock()
        self._running = False
        self.available = False

    def _d0(self):
        bit = 1 if INVERT_BITS else 0
        with self._lock:
            self._bits.append(bit)
            self._last = time.time()

    def _d1(self):
        bit = 0 if INVERT_BITS else 1
        with self._lock:
            self._bits.append(bit)
            self._last = time.time()

    def _bits_to_int(self, b):
        return int("".join(str(x) for x in b), 2) if b else 0

    def _parity_ok(self, bits) -> bool:
        # Even parity on bits[0:13], odd parity on bits[13:26]
        even = bits[0]
        odd = bits[25]
        if (sum(bits[0:13]) % 2) != 0:
            # even parity bit already included in bits[0:13]; check data half
            if (sum(bits[1:13]) % 2) != even:
                return False
        if ((sum(bits[13:25]) % 2) ^ 1) != odd:
            return False
        return True

    def _decode(self, bits):
        n = len(bits)
        print(
            f"[WIEGAND DEBUG] {n} bits: {''.join(str(b) for b in bits)} "
            f"invert={int(INVERT_BITS)}",
            flush=True,
        )
        if n == 26:
            # Soft parity: log mismatch but still decode (many keypads ignore std parity)
            try:
                if not self._parity_ok(bits):
                    print("[WIEGAND] parity mismatch — decoding anyway", flush=True)
            except Exception:
                pass
            data = bits[1:25]
            pin = str(self._bits_to_int(data)).lstrip("0") or "0"
            print(f"[WIEGAND] decoded PIN={pin}", flush=True)
            return pin
        if n == 24:
            pin = str(self._bits_to_int(bits)).lstrip("0") or "0"
            return pin
        return None

    def _loop(self):
        while self._running:
            time.sleep(0.01)
            frame = None
            with self._lock:
                if self._bits and (time.time() - self._last) > FRAME_GAP:
                    frame = self._bits[:]
                    self._bits = []
            if frame is not None:
                pin = self._decode(frame)
                if pin:
                    print(f"[WIEGAND] PIN entered: {pin}", flush=True)
                    if self.callback:
                        try:
                            self.callback(pin)
                        except Exception as e:
                            print(f"[WIEGAND] Callback error: {e}", flush=True)

    def start(self) -> bool:
        try:
            os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")
            from gpiozero import Button

            self._btn_d0 = Button(self.d0_pin, pull_up=True, bounce_time=None)
            self._btn_d1 = Button(self.d1_pin, pull_up=True, bounce_time=None)
            self._btn_d0.when_pressed = self._d0
            self._btn_d1.when_pressed = self._d1
            self._running = True
            t = threading.Thread(target=self._loop, daemon=True, name="wiegand-reader")
            t.start()
            self.available = True
            print(
                f"[WIEGAND] Reader started (26-bit) — D0=BCM{self.d0_pin}, "
                f"D1=BCM{self.d1_pin}, invert={int(INVERT_BITS)}"
            )
            return True
        except Exception as e:
            print(f"[WIEGAND] Failed to start: {e}")
            self.available = False
            return False

    def stop(self):
        self._running = False
        self.available = False
