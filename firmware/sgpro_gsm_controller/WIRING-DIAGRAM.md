# Thomastown SG-PRO — full wiring diagram

**Print this + open `WIRING-DIAGRAM-FULL.jpg` on site.**

Controller: **ESP32-S3** + **SIM7600G-H DTU** + **2 dry-contact relays** → **Roger B70/2ML**  
Optional: **Freenove 8 RGB** (WS2812) status LEDs  
Firmware: `sgpro_gsm_controller.ino`

---

## 1) Overview (all systems)

```
                    ┌──────────────────┐
                    │  12V DC SUPPLY   │
                    │  (site / cabinet)│
                    └────────┬─────────┘
                             │ 12V + GND
                             ▼
┌─────────────┐      ┌───────────────────┐      USB (program only)
│  SIM7600    │ UART │   ESP32-S3        │◄──── laptop
│  4G DTU     │◄────►│   screw terminal  │
│  + SIM +ant │      │                   │
└─────────────┘      │  IO4 ──► HOLD RLY ──► Roger AP–COM
                     │  IO5 ──► PULSE RLY─► Roger PP–COM
                     │  IO8 ──► LED DIN (optional)
                     │  5V/GND LED
                     └───────────────────┘
                              │ Wi‑Fi
                              ▼
                     Multicom / Firebase
                     (whitelist / SMS jobs)
```

---

## 2) Power

| From | To | Notes |
|------|-----|--------|
| Site **12V+** | SIM7600 DTU **VIN / 12V** | DTU range typically 7–36 V |
| Site **12V GND** | SIM7600 **GND** | |
| SIM7600 **GND** | ESP32 **GND** | **Must share common GND** |
| ESP32 | USB **or** regulated 5V | Board per your supply |
| ESP **5V** | Freenove LED **VCC** | Optional LEDs only |
| ESP **GND** | Freenove LED **GND** | Optional |

**Do not** power the SIM7600 from ESP 3V3/5V.

**Relay coils:** use board that matches ESP GPIO (3.3 V or opto module as fitted). Coil GND common with ESP GND.

**Roger / motor:** high-voltage / motor power is **separate**. ESP relays only touch **low-voltage command terminals**.

---

## 3) Modem UART (SIM7600 ↔ ESP32)

| SIM7600 DTU | ESP32-S3 | Function |
|-------------|----------|----------|
| **TXD** | **IO18** | Modem TX → ESP RX |
| **RXD** | **IO17** | Modem RX ← ESP TX |
| **GND** | **GND** | Common ground |

If `AT` always times out: swap **TXD/RXD** only (firmware also auto-tries swap once).

```
SIM7600 TXD ──────────► ESP IO18 (RX)
SIM7600 RXD ◄────────── ESP IO17 (TX)
SIM7600 GND ─────────── ESP GND
```

---

## 4) Relays → Roger B70/2ML (dry contacts)

Firmware: **ACTIVE HIGH** (GPIO HIGH = coil on = NO contact closed).

### HOLD relay (day open)

| Side | Connection |
|------|------------|
| Coil + | ESP **IO4** (or via module IN) |
| Coil − | ESP **GND** (module GND) |
| Contact **NO** | Roger terminal **16 (AP)** |
| Contact **COM** | Roger terminal **17 (COM)** |

**Use:** 06:00–18:00 Melbourne — HOLD closed → AP held → gate held open.

### PULSE relay (night open pulse)

| Side | Connection |
|------|------------|
| Coil + | ESP **IO5** |
| Coil − | ESP **GND** |
| Contact **NO** | Roger terminal **14 (PP)** |
| Contact **COM** | Roger terminal **17 (COM)** (same COM as AP) |

**Use:** night authorised call → hang-up → **3 s** pulse on PP.

```
                    ┌─ NO ──► Roger 16 AP
ESP IO4 ── HOLD ───┤
                    └─ COM ─► Roger 17 COM ──┐
                                             ├── common COM
                    ┌─ NO ──► Roger 14 PP    │
ESP IO5 ── PULSE ──┤                        │
                    └─ COM ─────────────────┘
```

### Safety

- Contacts are **dry** — command loop only.  
- **Never** put motor mains through these relays.  
- At 18:00 firmware runs multi-strike HOLD **OFF** so AP cannot stick.

---

## 5) Freenove 8 RGB LED (optional)

WS2812-style bar (DIN):

| Freenove | ESP32 |
|----------|--------|
| **VCC** | **5V** |
| **GND** | **GND** |
| **DIN** | **IO8** |

| Colour | Meaning |
|--------|---------|
| **Green** | Open / opening (day HOLD or night PULSE) |
| **Red** | Closing / closed |

Serial: `LED ON` / `LED OFF` / `LED TEST`  
No module fitted: leave unplugged; gate still works.

Library: **Adafruit NeoPixel**.

---

## 6) Pin map (quick reference)

| ESP pin | Function |
|---------|----------|
| **IO17** | Modem TX (to DTU RXD) |
| **IO18** | Modem RX (from DTU TXD) |
| **IO4** | HOLD relay coil |
| **IO5** | PULSE relay coil |
| **IO8** | Status LED DIN (optional) |
| **GND** | Common with DTU, relays, LED |
| **5V** | LED VCC (optional) |
| USB | Programming / serial 115200 |

---

## 7) Behaviour after wiring

| Time / event | Relays | LEDs (if on) |
|--------------|--------|----------------|
| 06:00–18:00 | HOLD ON (AP) | Green |
| 18:00 | HOLD multi-strike OFF | Red (closing) |
| Night whitelist call | 3 s PULSE (PP) | Green then red |
| Day call | Hang-up only | Green (still held) |

---

## 8) Site checklist

- [ ] 12V on DTU, power LED on  
- [ ] GND common: DTU + ESP + relay modules + LED  
- [ ] TXD→IO18, RXD→IO17 (or swapped if AT fails)  
- [ ] HOLD NO/COM → Roger AP–COM  
- [ ] PULSE NO/COM → Roger PP–COM  
- [ ] LED DIN→IO8 (optional)  
- [ ] Serial: `MODEM_RETRY` → clean OK  
- [ ] `DAY` green; `NIGHT` red release; `PULSE` green 3 s  

---

## 9) Files

| File | What |
|------|------|
| `WIRING-DIAGRAM-FULL.jpg` | Picture overview |
| `WIRING-DIAGRAM.md` | This document |
| `FLASH-NOW.txt` | Flash + serial bring-up |
| `sgpro_gsm_controller.ino` | Firmware |

**Device IDs:** sliding `device_commercial_bc_01` · double `#define GATE_UNIT_DOUBLE 1` → `device_commercial_bc_double_01`
