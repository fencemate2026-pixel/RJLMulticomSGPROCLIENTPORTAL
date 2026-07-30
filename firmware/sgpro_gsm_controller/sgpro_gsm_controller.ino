/**
 * RJL Multicom SG-PRO — Production controller
 * ESP32-S3 + Waveshare SIM7600G-H 4G DTU
 *
 * SCHEDULE (local Melbourne time, NTP) — TWO RELAYS into Roger B70/2ML:
 *   06:00 → 18:00  DAY  — HOLD relay (IO4) constant ON → AP–COM
 *   18:00 → 06:00  NIGHT — PULSE relay (IO5) 3s on whitelist call → PP–COM
 *
 * Portal add/delete → Firebase whitelistVersion++
 * ESP32 polls ~60s → validate temp → atomic activate local list
 * Night call → local list only → AT+CHUP → 3s relay if authorised
 * Day call → hang-up only (gate already held); no extra pulse
 * Welcome SMS → gsmSmsQueue jobs PENDING→SENDING→SENT|FAILED
 *
 * TWO UNITS: set GATE_UNIT below before flash (sliding vs double).
 * Secrets: Serial "PROVISION <secret>" → NVS. Never commit DEVICE_SECRET.
 *
 * Libraries: ArduinoJson, Preferences, WiFi, HTTPClient, mbedTLS
 * Optional: Adafruit NeoPixel (Freenove 8 RGB WS2812 status bar)
 */

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <mbedtls/md.h>
#include <esp_random.h>
#include <time.h>

// Freenove 8 RGB (WS2812) status bar — built in; hardware optional.
// GREEN = open/opening, RED = close/closing. DIN→IO8, VCC→5V, GND→GND.
// Compile-time off: #define STATUS_LED_ENABLE 0  (no Adafruit NeoPixel needed)
// Runtime off (no reflash): Serial  LED OFF   |  LED ON  |  LED TEST
#ifndef STATUS_LED_ENABLE
#define STATUS_LED_ENABLE 1
#endif

#if STATUS_LED_ENABLE
#include <Adafruit_NeoPixel.h>
#endif

// Explicit prototypes (do not rely on Arduino auto-prototype ordering).
void resetIncomingCallState();
void onHangupFinished(const char *how);
void hangUpCall();
void setRelaysSafeOff();
void ensureMelbourneTz();
void syncNtpClock();
void updateDayHoldSchedule();
void startRelayPulse();
void updateRelay();
void statusLedsInit();
void serviceStatusLeds();
bool sendATWait(const char *cmd, uint32_t waitMs = 3000);
bool configureModem();
bool detectModemBaud();
bool bringUpModem(bool runConfigure);
String normalizePhoneNumber(const String &raw);

// --- Pins (ESP32-S3 Screw Terminal Board) ---
// Silkscreen usually says IO17 / IO18 / IO5 (or G17 / G18 / G5) — not "GPIO18".
//
//   SIM7600G-H DTU TXD  →  board IO18  (ESP32 RX  = MODEM_RX_PIN)
//   SIM7600G-H DTU RXD  →  board IO17  (ESP32 TX  = MODEM_TX_PIN)
//   SIM7600G-H DTU GND  →  board GND
//
// TWO dry-contact relays into Roger B70/2ML (low-voltage command terminals):
//   Relay HOLD  (IO4) NO/COM  →  Roger 16 (AP) and 17 (COM)   day hold open
//   Relay PULSE (IO5) NO/COM  →  Roger 14 (PP) and 17 (COM)   night 3s step/open
//   Relay GND common with ESP GND (coil side only — contacts float dry to motor)
//
// Power DTU from 12V (7–36V), NOT from the ESP32 3V3/5V.
constexpr uint8_t MODEM_RX_PIN = 18;  // ESP32 receives from modem TXD
constexpr uint8_t MODEM_TX_PIN = 17;  // ESP32 sends to modem RXD
// Runtime UART pins (detectModemBaud may swap if wiring is reversed).
uint8_t modemRxPin = MODEM_RX_PIN;
uint8_t modemTxPin = MODEM_TX_PIN;
// Two relays. Polarity MUST be a preprocessor #define so #if works
// (constexpr bool is NOT visible to the preprocessor — was compiling active-LOW always).
constexpr uint8_t RELAY_HOLD_PIN = 4;   // day: constant → Roger AP–COM
constexpr uint8_t RELAY_PULSE_PIN = 5;  // night: 3s pulse → Roger PP–COM
#ifndef RELAY_ACTIVE_HIGH
#define RELAY_ACTIVE_HIGH 1  // 1 = GPIO HIGH closes NO; 0 = active-low modules
#endif

// Freenove 8 RGB LED module (WS2812 DIN) — status only, not gate control
//   VCC→5V  GND→GND  DIN→IO8
constexpr uint8_t STATUS_LED_PIN = 8;
constexpr uint8_t STATUS_LED_COUNT = 8;
constexpr uint8_t STATUS_LED_BRIGHTNESS = 40;  // 0–255; keep modest in cabinet
constexpr uint32_t STATUS_LED_CLOSING_MS = 4000;  // show red "closing" after open ends
constexpr uint32_t STATUS_LED_TICK_MS = 40;

constexpr uint32_t USB_BAUD = 115200;
constexpr uint32_t MODEM_BAUD_DEFAULT = 115200;
constexpr uint32_t RELAY_PULSE_MS = 3000;
constexpr uint32_t CALL_LOCKOUT_MS = 10000;
constexpr uint32_t CLCC_QUERY_INTERVAL_MS = 250;
constexpr uint32_t CLCC_RECOVERY_MS = 1800;
constexpr uint32_t CALL_FAILSAFE_MS = 2200;
constexpr uint32_t HANGUP_RETRY_MS = 250;
constexpr uint8_t HANGUP_MAX_ATTEMPTS = 8;
constexpr uint32_t WHITELIST_POLL_MS = 60UL * 1000UL;
constexpr uint32_t HEARTBEAT_MS = 5UL * 60UL * 1000UL;
constexpr uint32_t SMS_COOLDOWN_MS = 10000;
constexpr uint32_t SMS_COMMAND_TIMEOUT_MS = 5000;
constexpr uint32_t SMS_SUBMIT_TIMEOUT_MS = 30000;
constexpr uint32_t SCHEDULE_TICK_MS = 1000UL;

// Day hold window (local Australia/Melbourne): 06:00 inclusive → 18:00 exclusive
constexpr int HOLD_OPEN_HOUR = 6;
constexpr int HOLD_OPEN_MIN = 0;
constexpr int HOLD_CLOSE_HOUR = 18;
constexpr int HOLD_CLOSE_MIN = 0;

// ── Dual gate units (flash each board with the matching define) ─────────────
// Sliding (default): leave GATE_UNIT_DOUBLE undefined.
// Double/swing:      add -DGATE_UNIT_DOUBLE=1 in Arduino build flags, or
//                    uncomment the next line before upload to the double-gate ESP.
// #define GATE_UNIT_DOUBLE 1

// Non-secret firmware config only (safe in source control)
// Deployed on project iiii-7b9e8 (Blaze) — Cloud Run URL for gen2 function.
// Classic: https://australia-southeast1-iiii-7b9e8.cloudfunctions.net/gsmDeviceApi
// See docs/ALTERNATE_PROJECT_DEPLOY.md
const char *API_BASE =
    "https://gsmdeviceapi-2vin7lgmjq-ts.a.run.app";
#if defined(GATE_UNIT_DOUBLE)
const char *DEVICE_ID = "device_commercial_bc_double_01";
const char *GATE_LABEL = "DOUBLE/SWING";
#else
const char *DEVICE_ID = "device_commercial_bc_01";  // sliding (Settlement Rd live id)
const char *GATE_LABEL = "SLIDING";
#endif

static const char GTS_ROOT_R1[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
MIIFVzCCAz+gAwIBAgINAgPlk28xsBNJiGuiFzANBgkqhkiG9w0BAQwFADBHMQsw
CQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZpY2VzIExMQzEU
MBIGA1UEAxMLR1RTIFJvb3QgUjEwHhcNMTYwNjIyMDAwMDAwWhcNMzYwNjIyMDAw
MDAwWjBHMQswCQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZp
Y2VzIExMQzEUMBIGA1UEAxMLR1RTIFJvb3QgUjEwggIiMA0GCSqGSIb3DQEBAQUA
A4ICDwAwggIKAoICAQC2EQKLHuOhd5s73L+UPreVp0A8of2C+X0yBoJx9vaMf/vo
27xqLpeXo4xL+Sv2sfnOhB2x+cWX3u+58qPpvBKJXqeqUqv4IyfLpLGcY9vXmX7w
Cl7raKb0xlpHDU0QM+NOsROjyBhsS+z8CZDfnWQpJSMHobTSPS5g4M/SCYe7zUjw
TcLCeoiKu7rPWRnWr4+wB7CeMfGCwcDfLqZtbBkOtdh+JhpFAz2weaSUKK0Pfybl
qAj+lug8aJRT7oM6iCsVlgmy4HqMLnXWnOunVmSPlk9orj2XwoSPwLxAwAtcvfaH
szVsrBhQf4TgTM2S0yDpM7xSma8ytSmzJSq0SPly4cpk9+aCEI3oncKKiPo4Zor8
Y/kB+Xj9e1x3+naH+uzfsQ55lVe0vSbv1gHR6xYKu44LtcXFilWr06zqkUspzBmk
MiVOKvFlRNACzqrOSbTqn3yDsEB750Orp2yjj32JgfpMpf/VjsPOS+C12LOORc92
wO1AK/1TD7Cn1TsNsYqiA94xrcx36m97PtbfkSIS5r762DL8EGMUUXLeXdYWk70p
aDPvOmbsB4om3xPXV2V4J95eSRQAogB/mqghtqmxlbCluQ0WEdrHbEg8QOB+DVrN
VjzRlwW5y0vtOUucxD/SVRNuJLDWcfr0wbrM7Rv1/oFB2ACYPTrIrnqYNxgFlQID
AQABo0IwQDAOBgNVHQ8BAf8EBAMCAYYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4E
FgQU5K8rJnEaK0gnhS9SZizv8IkTcT4wDQYJKoZIhvcNAQEMBQADggIBAJ+qQibb
C5u+/x6Wki4+omVKapi6Ist9wTrYggoGxval3sBOh2Z5ofmmWJyq+bXmYOfg6LEe
QkEzCzc9zolwFcq1JKjPa7XSQCGYzyI0zzvFIoTgxQ6KfF2I5DUkzps+GlQebtuy
h6f88/qBVRRiClmpIgUxPoLW7ttXNLwzldMXG+gnoot7TiYaelpkttGsN/H9oPM4
7HLwEXWdyzRSjeZ2axfG34arJ45JK3VmgRAhpuo+9K4l/3wV3s6MJT/KYnAK9y8J
ZgfIPxz88NtFMN9iiMG1D53Dn0reWVlHxYciNuaCp+0KueIHoI17eko8cdLiA6Ef
MgfdG+RCzgwARWGAtQsgWSl4vflVy2PFPEz0tv/bal8xa5meLMFrUKTX5hgUvYU/
Z6tGn6D/Qqc6f1zLXbBwHSs09dR2CQzreExZBfMzQsNhFRAbd03OIozUhfJFfbdT
6u9AWpQKXCBfTkBdYiJ23//OYb2MI3jSNwLgjt7RETeJ9r/tSQdirpLsQBqvFAnZ
0E6yove+7u7Y/9waLd64NnHi/Hm3lCXRSHNboTXns5lndcEZOitHTtNCjv0xyBZm
2tIMPNuzjsmhDYAPexZ3FL//2wmUspO8IFgV6dtxQ/PeEMMA3KgqlbbC1j+Qa3bb
bP6MvPJwNQzcmRk13NfIRmPVNnGuV/u3gm3c
-----END CERTIFICATE-----
)EOF";
// Wi-Fi SSID/password and DEVICE_SECRET are NEVER committed -- provision via Serial.

// -- Runtime ----------------------------------------
HardwareSerial modem(1);
Preferences prefs;
String modemLine;

bool relayActive = false;       // short night pulse in progress
uint32_t relayOffAt = 0;
bool dayHoldActive = false;     // ESP constant-ON day hold (06:00–18:00 local)
// Status LEDs: track open→close so bar shows green open / red closing
bool statusLedWasOpen = false;
uint32_t statusLedClosingUntil = 0;
uint32_t lastStatusLedTick = 0;
bool statusLedsUserEnabled = true;  // NVS "ledOn"; Serial LED ON/OFF
#if STATUS_LED_ENABLE
Adafruit_NeoPixel statusLeds(STATUS_LED_COUNT, STATUS_LED_PIN, NEO_GRB + NEO_KHZ800);
#endif
// After day HOLD ends: multi-strike SAFE-OFF so AP cannot stick (Mildura-class)
uint8_t postHoldCutStrikesLeft = 0;
uint32_t nextPostHoldCutAt = 0;
constexpr uint8_t POST_HOLD_CUT_STRIKES = 20;       // 20 hard OFF writes
constexpr uint32_t POST_HOLD_CUT_INTERVAL_MS = 100;  // over ~2 s
// After night pulse ends: same multi-strike on PP
uint8_t postPulseCutStrikesLeft = 0;
uint32_t nextPostPulseCutAt = 0;
constexpr uint8_t POST_PULSE_CUT_STRIKES = 10;
constexpr uint32_t POST_PULSE_CUT_INTERVAL_MS = 50;
uint32_t lastScheduleTick = 0;
bool ringPending = false;
bool firstRingObserved = false;
uint32_t ringStartedAt = 0;
bool hasHandledCall = false;
uint32_t lastCallHandledAt = 0;
bool hasEndedCall = false;
uint32_t lastCallEndedAt = 0;
uint32_t lastClccQueryAt = 0;
bool clccReplyPending = false;
bool modemLineCorrupted = false;
bool modemHexDiagnostics = false;
bool hangupPending = false;
bool relayQueuedAfterHangup = false;
bool smsCommandMode = false;
enum class SmsState : uint8_t {
  idle, waitCmgf, waitCscs, waitPrompt, waitSubmit, acknowledge
};
struct SmsJob {
  String jobId;
  String phone;
  String message;
  String cmgsReference;
  String error;
  bool ok;
  int64_t sentAtEpoch;
};
SmsState smsState = SmsState::idle;
SmsJob activeSms;
uint32_t smsStateStartedAt = 0;
uint32_t lastSmsFinishedAt = 0;
bool smsResponseOk = false;
bool smsResponseError = false;
bool smsPromptSeen = false;
bool smsCmgsSeen = false;
uint32_t lastHangupAttemptAt = 0;
uint8_t hangupAttempts = 0;
uint32_t activeModemBaud = 0;
bool modemReady = false;
uint32_t lastModemRetryAt = 0;
constexpr uint32_t MODEM_RETRY_MS = 60UL * 1000UL;  // re-probe if cable plugged later
String temporaryCallerNumber;
bool callLockout = false;
uint32_t callLockoutStartedAt = 0;
bool atCommandPending = false;
bool atCommandCorrupted = false;
int8_t atCommandResult = 0; // 0 waiting, 1 OK, -1 ERROR
String atCommandResponse;
String pendingAtCommand;
uint32_t lastWhitelistPull = 0;
uint32_t lastHeartbeat = 0;
int64_t localWhitelistVersion = -1;
String localWhitelistChecksum;
uint32_t secretVersion = 1; // must match gsmDeviceCredentials.secretVersion

int modemSignalStrength = -1;
bool modemNetworkRegistered = false;
String modemOperator;
String modemRadioTechnology;
struct GnssFix {
  bool valid = false;
  double latitude = 0;
  double longitude = 0;
  double altitudeMetres = 0;
  double speedKnots = 0;
  double headingDegrees = 0;
  int64_t capturedAtEpoch = 0;
};
GnssFix lastGnssFix;

String deviceSecret; // NVS only
String wifiSsid;     // NVS only
String wifiPass;     // NVS only
// Cellular data (optional — gate open works without PDP if Wi‑Fi carries API)
String cellularApn;   // NVS: e.g. live.vodafone.com — never commit user/pass
String cellularUser;  // NVS optional PDP user
String cellularPass;  // NVS optional PDP password

// Install / debug: override day-hold schedule until AUTO
enum class ScheduleOverride : uint8_t { Auto = 0, ForceDay = 1, ForceNight = 2 };
ScheduleOverride scheduleOverride = ScheduleOverride::Auto;

constexpr size_t MAX_CALLERS = 120;
struct CallerEntry {
  char e164[24];
  char id[40];
  char name[40];
  bool enabled;
  int64_t validUntilEpoch;
};
CallerEntry callers[MAX_CALLERS];
size_t callerCount = 0;

// ── Two relays (Roger B70/2ML) ───────────────────────────────────────────────
// HOLD  IO4 → AP–COM  day constant closed contact (blocks auto-reclose)
// PULSE IO5 → PP–COM  night 3s closed contact (step/open pulse)
// RELAY_ACTIVE_HIGH is a #define (see pins). Do not use constexpr with #if.

static inline void writeRelayPin(uint8_t pin, bool on) {
#if RELAY_ACTIVE_HIGH
  digitalWrite(pin, on ? HIGH : LOW);
#else
  digitalWrite(pin, on ? LOW : HIGH);
#endif
}

void setHoldRelay(bool on) {
  writeRelayPin(RELAY_HOLD_PIN, on);
}

void setPulseRelay(bool on) {
  writeRelayPin(RELAY_PULSE_PIN, on);
}

// Force BOTH relays safe/off (boot / emergency only). Does not take on=true.
void setRelaysSafeOff() {
  setHoldRelay(false);
  setPulseRelay(false);
  dayHoldActive = false;
  relayActive = false;
}

void relaysInit() {
  pinMode(RELAY_HOLD_PIN, OUTPUT);
  pinMode(RELAY_PULSE_PIN, OUTPUT);
  setHoldRelay(false);
  setPulseRelay(false);
}

// ── Status LEDs (Freenove 8× WS2812): GREEN open/opening, RED close/closing ──
// Built into firmware. Module optional — leave unplugged if unused.
// Gate "open command" = day HOLD (AP) or night PULSE (PP). Not motor limit switches.
void statusLedsInit() {
#if STATUS_LED_ENABLE
  statusLeds.begin();
  statusLeds.setBrightness(STATUS_LED_BRIGHTNESS);
  statusLeds.clear();
  statusLeds.show();
  Serial.printf(
      "LED: built-in status IO%u — GREEN=open RED=close | user=%s | LED ON/OFF/TEST\n",
      (unsigned)STATUS_LED_PIN,
      statusLedsUserEnabled ? "ON" : "OFF");
#else
  Serial.println("LED: compiled out (STATUS_LED_ENABLE=0)");
#endif
}

static void statusLedsFill(uint8_t r, uint8_t g, uint8_t b) {
#if STATUS_LED_ENABLE
  const uint32_t c = statusLeds.Color(r, g, b);
  for (uint8_t i = 0; i < STATUS_LED_COUNT; i++) statusLeds.setPixelColor(i, c);
  statusLeds.show();
#else
  (void)r;
  (void)g;
  (void)b;
#endif
}

static void statusLedsBlank() {
#if STATUS_LED_ENABLE
  statusLeds.clear();
  statusLeds.show();
#endif
}

void serviceStatusLeds() {
#if STATUS_LED_ENABLE
  if (millis() - lastStatusLedTick < STATUS_LED_TICK_MS) return;
  lastStatusLedTick = millis();

  // User (or NVS) turned bar off — stay dark; gate logic unchanged
  if (!statusLedsUserEnabled) {
    statusLedsBlank();
    return;
  }

  // Commanded open: day hold or night open pulse
  const bool openCmd = dayHoldActive || relayActive;
  const bool releasing =
      postHoldCutStrikesLeft > 0 || postPulseCutStrikesLeft > 0;

  if (openCmd) {
    statusLedWasOpen = true;
    statusLedClosingUntil = 0;
    if (relayActive && !dayHoldActive) {
      statusLedsFill(0, 255, 0);  // opening pulse
    } else {
      statusLedsFill(0, 200, 0);  // day hold open
    }
    return;
  }

  // Transition open → off: show closing red for a few seconds + during cut strikes
  if (statusLedWasOpen || releasing) {
    if (statusLedWasOpen) {
      statusLedWasOpen = false;
      statusLedClosingUntil = millis() + STATUS_LED_CLOSING_MS;
      Serial.println("LED: CLOSING (red)");
    }
    if (releasing ||
        (statusLedClosingUntil != 0 &&
         (int32_t)(millis() - statusLedClosingUntil) < 0)) {
      statusLedsFill(255, 0, 0);  // closing
      return;
    }
    statusLedClosingUntil = 0;
  }

  // Steady closed / night idle: dim red
  statusLedsFill(80, 0, 0);
#endif
}

// Always re-apply: configTime() can reset TZ to UTC on ESP32.
void ensureMelbourneTz() {
  // AEST UTC+10 / AEDT UTC+11 (first Sunday Oct → first Sunday Apr)
  setenv("TZ", "AEST-10AEDT,M10.1.0,M4.1.0/3", 1);
  tzset();
}

// NTP then Melbourne local — call this instead of bare configTime().
void syncNtpClock() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  ensureMelbourneTz();
}

bool clockLooksValid() {
  return time(nullptr) > 1700000000L;  // after ~2023-11
}

// True during 06:00–18:00 local Melbourne. If clock invalid → night-safe (dial only).
bool isDayHoldWindow() {
  ensureMelbourneTz();
  if (!clockLooksValid()) return false;
  time_t now = time(nullptr);
  struct tm t;
  localtime_r(&now, &t);
  const int mins = t.tm_hour * 60 + t.tm_min;
  const int openM = HOLD_OPEN_HOUR * 60 + HOLD_OPEN_MIN;
  const int closeM = HOLD_CLOSE_HOUR * 60 + HOLD_CLOSE_MIN;
  return mins >= openM && mins < closeM;
}

// Drive both dry-contact relays OFF (command side). Multi-write for sticky drivers.
void forceRelaysAllOffPins() {
  setHoldRelay(false);
  setPulseRelay(false);
  setHoldRelay(false);
  setPulseRelay(false);
}

// Start thorough post-hold release: many OFF strikes so AP cannot stick open.
void beginPostHoldReleaseReset(const char *reason) {
  relayActive = false;
  postPulseCutStrikesLeft = 0;
  postHoldCutStrikesLeft = POST_HOLD_CUT_STRIKES;
  nextPostHoldCutAt = millis();
  forceRelaysAllOffPins();
  Serial.printf(
      "RELAY: HOLD RELEASE RESET start (%s) — %u hard OFF strikes on AP+PP\n",
      reason ? reason : "hold_off",
      (unsigned)POST_HOLD_CUT_STRIKES);
}

void beginPostPulseReleaseReset() {
  postPulseCutStrikesLeft = POST_PULSE_CUT_STRIKES;
  nextPostPulseCutAt = millis();
  setPulseRelay(false);
  if (!dayHoldActive) setHoldRelay(false);
  Serial.printf("RELAY: PULSE RELEASE RESET start — %u hard OFF strikes on PP\n",
                (unsigned)POST_PULSE_CUT_STRIKES);
}

void serviceReleaseResets() {
  // Post day-hold: keep hammering both OFF for ~2s (Mildura stuck-open class)
  if (postHoldCutStrikesLeft > 0 && !dayHoldActive) {
    if ((int32_t)(millis() - nextPostHoldCutAt) >= 0) {
      nextPostHoldCutAt = millis() + POST_HOLD_CUT_INTERVAL_MS;
      relayActive = false;
      forceRelaysAllOffPins();
      postHoldCutStrikesLeft--;
      if (postHoldCutStrikesLeft == 0) {
        Serial.println("RELAY: HOLD RELEASE RESET complete — AP+PP confirmed OFF path");
      }
    }
  } else if (dayHoldActive) {
    postHoldCutStrikesLeft = 0;  // day again cancels night release sequence
  }

  // Post night pulse: hammer PP OFF (and HOLD off if night)
  if (postPulseCutStrikesLeft > 0 && !relayActive) {
    if ((int32_t)(millis() - nextPostPulseCutAt) >= 0) {
      nextPostPulseCutAt = millis() + POST_PULSE_CUT_INTERVAL_MS;
      setPulseRelay(false);
      if (!dayHoldActive) setHoldRelay(false);
      postPulseCutStrikesLeft--;
      if (postPulseCutStrikesLeft == 0) {
        Serial.println("RELAY: PULSE RELEASE RESET complete — PP OFF path");
      }
    }
  }
}

void applyDayHold(bool want) {
  if (want == dayHoldActive) {
    if (want) {
      setHoldRelay(true);   // re-assert hold contact
      setPulseRelay(false);
    }
    return;
  }
  dayHoldActive = want;
  if (dayHoldActive) {
    // Enter day: kill any night pulse, cancel release sequences, assert AP hold
    postHoldCutStrikesLeft = 0;
    postPulseCutStrikesLeft = 0;
    relayActive = false;
    setPulseRelay(false);
    setHoldRelay(true);
    Serial.println("SCHED: DAY — HOLD relay ON (AP–COM constant)");
  } else {
    // Leave day hold → thorough unit reset of both command relays
    dayHoldActive = false;
    beginPostHoldReleaseReset("day_hold_ended");
    Serial.println("SCHED: NIGHT — HOLD released; thorough SAFE-OFF; dial uses PULSE 3s");
  }
}

void updateDayHoldSchedule() {
  if (scheduleOverride == ScheduleOverride::ForceDay) {
    applyDayHold(true);
    return;
  }
  if (scheduleOverride == ScheduleOverride::ForceNight) {
    applyDayHold(false);
    return;
  }
  applyDayHold(isDayHoldWindow());
}

void startRelayPulse() {
  if (dayHoldActive) {
    // Day: AP already held — do not pulse PP (not needed; avoid extra cycles)
    setHoldRelay(true);
    Serial.println("RELAY: day hold active — skip PP pulse");
    return;
  }
  if (relayActive) return;  // one pulse per call window
  // Cancel any leftover release strikes so pulse can engage
  postHoldCutStrikesLeft = 0;
  postPulseCutStrikesLeft = 0;
  relayActive = true;
  relayOffAt = millis() + RELAY_PULSE_MS;
  setHoldRelay(false);
  setPulseRelay(true);
  Serial.printf("RELAY: PULSE ON IO%u  %lums (PP–COM)\n",
                (unsigned)RELAY_PULSE_PIN, (unsigned long)RELAY_PULSE_MS);
}

void updateRelay() {
  serviceReleaseResets();

  // Mildura lesson: always drive known-safe GPIO state every tick.
  // Never leave HOLD (AP) or PULSE (PP) "assumed off" after a one-shot write.
  if (dayHoldActive) {
    setHoldRelay(true);       // day: AP held
    setPulseRelay(false);     // day: PP must stay open (no sticky pulse)
    relayActive = false;      // cancel any night pulse state during day window
    return;
  }
  // Night: AP must NEVER stick closed (would hold gate open like Mildura)
  setHoldRelay(false);
  if (relayActive) {
    if ((int32_t)(millis() - relayOffAt) >= 0) {
      relayActive = false;
      setPulseRelay(false);
      Serial.println("RELAY: PULSE OFF (PP–COM open)");
      beginPostPulseReleaseReset();
    } else {
      setPulseRelay(true);  // re-assert during timed pulse
    }
  } else if (postHoldCutStrikesLeft == 0 && postPulseCutStrikesLeft == 0) {
    setPulseRelay(false);   // steady night idle: PP open
  }
}

// -- NVS secrets + whitelist ----------------------------------------
void loadCredentialsFromNvs() {
  prefs.begin("sgpro", true);
  deviceSecret = prefs.getString("secret", "");
  secretVersion = prefs.getUInt("secretVer", 1);
  wifiSsid = prefs.getString("wifiSsid", "");
  wifiPass = prefs.getString("wifiPass", "");
  cellularApn = prefs.getString("apn", "");
  cellularUser = prefs.getString("apnUser", "");
  cellularPass = prefs.getString("apnPass", "");
  localWhitelistVersion = prefs.getLong64("wl_ver", -1);
  localWhitelistChecksum = prefs.getString("wl_cksum", "");
  statusLedsUserEnabled = prefs.getBool("ledOn", true);
  prefs.end();
}

void saveStatusLedEnabled(bool on) {
  statusLedsUserEnabled = on;
  prefs.begin("sgpro", false);
  prefs.putBool("ledOn", on);
  prefs.end();
}

// Small NVS update only (not the dual-slot caller payload).
void persistWhitelistMeta(int64_t version, const String &checksum) {
  prefs.begin("sgpro", false);
  prefs.putLong64("wl_ver", version);
  if (checksum.length() == 64) prefs.putString("wl_cksum", checksum);
  prefs.end();
}

bool saveApnToNvs(const String &apn, const String &user, const String &pass) {
  prefs.begin("sgpro", false);
  prefs.putString("apn", apn);
  prefs.putString("apnUser", user);
  prefs.putString("apnPass", pass);
  prefs.end();
  cellularApn = apn;
  cellularUser = user;
  cellularPass = pass;
  return true;
}

bool saveSecretToNvs(const String &secret, uint32_t version = 1) {
  if (secret.length() < 16) return false;
  prefs.begin("sgpro", false);
  prefs.putString("secret", secret);
  prefs.putUInt("secretVer", version);
  prefs.end();
  deviceSecret = secret;
  secretVersion = version;
  return true;
}

bool saveWifiToNvs(const String &ssid, const String &pass) {
  if (ssid.length() < 1) return false;
  prefs.begin("sgpro", false);
  prefs.putString("wifiSsid", ssid);
  prefs.putString("wifiPass", pass);
  prefs.end();
  wifiSsid = ssid;
  wifiPass = pass;
  return true;
}

bool writeWhitelistSlot(
    uint8_t slot,
    const CallerEntry *entries,
    size_t count,
    int64_t version
) {
  const char *nameSpace = slot == 0 ? "sgpro_wl0" : "sgpro_wl1";
  prefs.begin(nameSpace, false);
  prefs.clear();
  prefs.putBool("valid", false);
  bool ok = prefs.putUInt("count", (uint32_t)count) == sizeof(uint32_t);
  for (size_t i = 0; i < count && ok; i++) {
    String k = "c" + String(i);
    // Every field must succeed; otherwise keep last-known-good (do not mark valid).
    ok = prefs.putString((k + "p").c_str(), entries[i].e164) > 0;
    // id/name may be empty; putString returns 0 for empty string — treat empty as OK
    if (ok) {
      const size_t idLen = strlen(entries[i].id);
      const size_t wroteId = prefs.putString((k + "i").c_str(), entries[i].id);
      ok = (idLen == 0) ? true : (wroteId > 0);
    }
    if (ok) {
      const size_t nameLen = strlen(entries[i].name);
      const size_t wroteName = prefs.putString((k + "n").c_str(), entries[i].name);
      ok = (nameLen == 0) ? true : (wroteName > 0);
    }
    if (ok) {
      // Preferences::putBool returns size written; require success
      ok = prefs.putBool((k + "e").c_str(), entries[i].enabled) > 0;
    }
    if (ok) {
      ok = prefs.putLong64((k + "u").c_str(), entries[i].validUntilEpoch) == sizeof(int64_t);
    }
  }
  ok = ok && prefs.putLong64("ver", version) == sizeof(int64_t);
  if (ok) prefs.putBool("valid", true);
  prefs.end();
  if (!ok) return false;

  prefs.begin(nameSpace, true);
  const bool verified =
      prefs.getBool("valid", false) &&
      prefs.getUInt("count", UINT32_MAX) == count &&
      prefs.getLong64("ver", INT64_MIN) == version;
  prefs.end();
  return verified;
}

bool persistWhitelistAtomic(
    const CallerEntry *entries,
    size_t count,
    int64_t version
) {
  prefs.begin("sgpro", true);
  const uint8_t active = prefs.getUChar("wl_slot", 0xFF);
  prefs.end();
  const uint8_t target = active == 0 ? 1 : 0;

  if (!writeWhitelistSlot(target, entries, count, version)) {
    Serial.println("Whitelist: NVS staging failed - keeping last-known-good");
    return false;
  }

  prefs.begin("sgpro", false);
  const bool committed = prefs.putUChar("wl_slot", target) == sizeof(uint8_t);
  prefs.end();
  if (!committed) {
    Serial.println("Whitelist: NVS commit failed - keeping last-known-good");
    return false;
  }
  Serial.printf(
      "Whitelist saved slot=%u v%lld n=%u\n",
      target,
      (long long)version,
      (unsigned)count);
  return true;
}

bool loadWhitelistSlot(uint8_t slot) {
  const char *nameSpace = slot == 0 ? "sgpro_wl0" : "sgpro_wl1";
  prefs.begin(nameSpace, true);
  if (!prefs.getBool("valid", false)) {
    prefs.end();
    return false;
  }

  uint32_t stored = prefs.getUInt("count", UINT32_MAX);
  if (stored > MAX_CALLERS) {
    prefs.end();
    return false;
  }

  size_t loaded = 0;
  bool corrupt = false;
  for (uint32_t i = 0; i < stored; i++) {
    String k = "c" + String(i);
    String phone = prefs.getString((k + "p").c_str(), "");
    if (phone.length() < 8) {
      corrupt = true;
      break;
    }
    strncpy(callers[loaded].e164, phone.c_str(), sizeof(callers[loaded].e164) - 1);
    callers[loaded].e164[sizeof(callers[loaded].e164) - 1] = 0;
    String id = prefs.getString((k + "i").c_str(), "");
    strncpy(callers[loaded].id, id.c_str(), sizeof(callers[loaded].id) - 1);
    callers[loaded].id[sizeof(callers[loaded].id) - 1] = 0;
    String name = prefs.getString((k + "n").c_str(), "");
    strncpy(callers[loaded].name, name.c_str(), sizeof(callers[loaded].name) - 1);
    callers[loaded].name[sizeof(callers[loaded].name) - 1] = 0;
    // Missing/corrupt authorization state must fail closed.
    callers[loaded].enabled = prefs.getBool((k + "e").c_str(), false);
    callers[loaded].validUntilEpoch = prefs.getLong64((k + "u").c_str(), 0);
    loaded++;
  }
  const int64_t version = prefs.getLong64("ver", INT64_MIN);
  prefs.end();

  if (corrupt || version == INT64_MIN) return false;
  callerCount = loaded;
  localWhitelistVersion = version;
  Serial.printf(
      "Whitelist restored slot=%u v%lld n=%u\n",
      slot,
      (long long)localWhitelistVersion,
      (unsigned)callerCount);
  return true;
}

bool loadLegacyWhitelistFromNvs() {
  prefs.begin("sgpro_wl", true);
  if (!prefs.getBool("valid", false)) {
    prefs.end();
    return false;
  }
  uint32_t stored = prefs.getUInt("count", 0);
  if (stored > MAX_CALLERS) stored = MAX_CALLERS;
  size_t loaded = 0;
  for (uint32_t i = 0; i < stored; i++) {
    String k = "c" + String(i);
    String phone = prefs.getString((k + "p").c_str(), "");
    if (phone.length() < 8) continue;
    strncpy(callers[loaded].e164, phone.c_str(), sizeof(callers[loaded].e164) - 1);
    callers[loaded].e164[sizeof(callers[loaded].e164) - 1] = 0;
    String id = prefs.getString((k + "i").c_str(), "");
    strncpy(callers[loaded].id, id.c_str(), sizeof(callers[loaded].id) - 1);
    callers[loaded].id[sizeof(callers[loaded].id) - 1] = 0;
    String name = prefs.getString((k + "n").c_str(), "");
    strncpy(callers[loaded].name, name.c_str(), sizeof(callers[loaded].name) - 1);
    callers[loaded].name[sizeof(callers[loaded].name) - 1] = 0;
    // Legacy lists were already filtered by the backend when downloaded.
    callers[loaded].enabled = true;
    callers[loaded].validUntilEpoch = 0;
    loaded++;
  }
  const int64_t version = prefs.getLong64("ver", -1);
  prefs.end();
  if (version < 0) return false;
  callerCount = loaded;
  localWhitelistVersion = version;
  Serial.printf(
      "Legacy whitelist restored v%lld n=%u; migrating on next sync\n",
      (long long)localWhitelistVersion,
      (unsigned)callerCount);
  return true;
}

bool loadWhitelistFromNvs() {
  prefs.begin("sgpro", true);
  const uint8_t active = prefs.getUChar("wl_slot", 0xFF);
  prefs.end();

  if (active <= 1 && loadWhitelistSlot(active)) return true;
  if (active <= 1 && loadWhitelistSlot(active == 0 ? 1 : 0)) return true;
  if (loadWhitelistSlot(0) || loadWhitelistSlot(1)) return true;
  return loadLegacyWhitelistFromNvs();
}

// -- Modem ----------------------------------------
void processModemLine(const String &rawLine, bool framingCorrupted = false);

void writeModem(const uint8_t *data, size_t length) {
  modem.write(data, length);
}

void writeModem(const char *text) {
  writeModem(reinterpret_cast<const uint8_t *>(text), strlen(text));
}

void writeModem(const String &text) {
  writeModem(reinterpret_cast<const uint8_t *>(text.c_str()), text.length());
}

void writeModemByte(uint8_t value) {
  modem.write(value);
}

void printModemHex(const String &raw) {
  Serial.print("MODEM HEX:");
  for (size_t i = 0; i < raw.length(); ++i) {
    Serial.printf(" %02X", static_cast<uint8_t>(raw.charAt(i)));
  }
  Serial.println();
}

void ingestModemByte(uint8_t value) {
  if (smsState == SmsState::waitPrompt && value == '>') smsPromptSeen = true;
  const char c = static_cast<char>(value);
  if (c == '\r' || c == '\n') {
    if (modemLine.length() || modemLineCorrupted) {
      processModemLine(modemLine, modemLineCorrupted);
      modemLine = "";
      modemLineCorrupted = false;
    }
    return;
  }
  // SIM7600 command/URC lines are printable ASCII. Mark the complete frame bad
  // without appending damaged bytes, so corrupt input can never become a
  // command result, URC, caller ID, or part of the next line.
  if (value < 0x20 || value > 0x7e) {
    modemLineCorrupted = true;
    return;
  }
  if (!modemLineCorrupted && modemLine.length() < 1023) modemLine += c;
  else modemLineCorrupted = true;
}

// The only function allowed to read the modem UART. Every consumer receives the
// same bytes, and every byte also reaches the persistent line/URC parser.
void readModemBytes(String *capture = nullptr, bool *sawPrompt = nullptr) {
  while (modem.available()) {
    const int value = modem.read();
    if (value < 0) break;
    const uint8_t byte = static_cast<uint8_t>(value);
    if (capture) *capture += static_cast<char>(byte);
    if (sawPrompt && byte == '>') *sawPrompt = true;
    ingestModemByte(byte);
  }
}

void sendAT(const char *cmd) {
  Serial.print("AT> ");
  // The Vodafone PDP authentication command contains credentials. Preserve it
  // on the wire but never reveal it through production logs.
  if (!strncmp(cmd, "AT+CGAUTH=", 10)) Serial.println("AT+CGAUTH=<redacted>");
  else Serial.println(cmd);
  writeModem(cmd);
  writeModem("\r\n");
}

const char *safeAtLogLabel(const char *cmd) {
  return !strncmp(cmd, "AT+CGAUTH=", 10) ? "AT+CGAUTH=<redacted>" : cmd;
}

bool sendATWait(const char *cmd, uint32_t waitMs) {
  if (atCommandPending) {
    Serial.printf("AT: %s not sent; command already pending\n", cmd);
    return false;
  }
  atCommandPending = true;
  atCommandCorrupted = false;
  atCommandResult = 0;
  atCommandResponse = "";
  pendingAtCommand = cmd;
  sendAT(cmd);
  const uint32_t startedAt = millis();
  while (atCommandResult == 0 && millis() - startedAt < waitMs) {
    readModemBytes();
    updateRelay();
    delay(2);
  }
  const bool ok = atCommandResult == 1 && !atCommandCorrupted;
  if (ok) {
    Serial.printf("AT: %s success\n", safeAtLogLabel(cmd));
  } else if (atCommandResult < 0) {
    Serial.printf("AT: %s failed (ERROR)\n", safeAtLogLabel(cmd));
  } else if (atCommandCorrupted) {
    Serial.printf("AT: %s failed (corrupted/non-ASCII response)\n",
                  safeAtLogLabel(cmd));
  } else {
    Serial.printf("AT: %s failed (timeout)\n", safeAtLogLabel(cmd));
  }
  atCommandPending = false;
  pendingAtCommand = "";
  return ok;
}

void beginModemUart(uint32_t baud) {
  // Do not kill day-hold AP contact while probing UART; only clear pulse channel.
  setPulseRelay(false);
  relayActive = false;
  modem.end();
  delay(20);
  modemLine = "";
  modemLineCorrupted = false;
  modem.begin(baud, SERIAL_8N1, modemRxPin, modemTxPin);
  activeModemBaud = baud;
  // Drop any stale RX before AT so timeouts are clean.
  while (modem.available()) (void)modem.read();
  Serial.printf("MODEM: probing %lu baud  ESP RX=IO%u TX=IO%u\n",
                static_cast<unsigned long>(baud),
                (unsigned)modemRxPin, (unsigned)modemTxPin);
}

// True if modem answered AT with clean OK on current modemRxPin/modemTxPin.
bool detectModemBaudOnCurrentPins() {
  static const uint32_t baudRates[] = {115200, 9600, 57600, 38400, 19200};
  bool sawAnyRx = false;
  for (uint32_t baud : baudRates) {
    beginModemUart(baud);
    if (sendATWait("AT", 1500)) {
      Serial.printf("MODEM: clean OK detected at %lu baud (RX=IO%u TX=IO%u)\n",
                    static_cast<unsigned long>(baud),
                    (unsigned)modemRxPin, (unsigned)modemTxPin);
      return true;
    }
    // sendATWait drains UART into atCommandResponse — use that, not available().
    if (atCommandResponse.length() > 0 || atCommandCorrupted) {
      sawAnyRx = true;
      Serial.printf(
          "MODEM: RX at %lu baud but not clean OK (len=%u corrupt=%d) RX=IO%u\n",
          static_cast<unsigned long>(baud),
          (unsigned)atCommandResponse.length(),
          (int)atCommandCorrupted,
          (unsigned)modemRxPin);
    }
  }
  if (!sawAnyRx) {
    Serial.printf(
        "MODEM: total silence on ESP RX=IO%u (no bytes any baud this map)\n",
        (unsigned)modemRxPin);
  }
  return false;
}

// Full detect + optional configure. Used at boot and periodic retry on site.
bool bringUpModem(bool runConfigure) {
  if (!detectModemBaud()) {
    modemReady = false;
    return false;
  }
  modemReady = true;
  if (runConfigure) {
    const bool configured = configureModem();
    Serial.printf("MODEM: initialization %s\n",
                  configured ? "complete" : "completed with failures");
  }
  return true;
}

bool detectModemBaud() {
  modemRxPin = MODEM_RX_PIN;
  modemTxPin = MODEM_TX_PIN;
  Serial.printf(
      "MODEM UART expected: modem TXD->ESP IO%u | modem RXD<-ESP IO%u | GND common\n",
      (unsigned)MODEM_RX_PIN, (unsigned)MODEM_TX_PIN);
  if (detectModemBaudOnCurrentPins()) return true;

  // Install mistake: data wires swapped. Try once before giving up.
  Serial.println("MODEM: no OK on default pins — trying TX/RX swap once...");
  modemRxPin = MODEM_TX_PIN;  // IO17 as RX
  modemTxPin = MODEM_RX_PIN;  // IO18 as TX
  if (detectModemBaudOnCurrentPins()) {
    Serial.println("MODEM: OK on SWAPPED pins — leave wires as-is or hard-wire "
                   "TXD->IO17 RXD->IO18 and reflash default");
    return true;
  }

  modemRxPin = MODEM_RX_PIN;
  modemTxPin = MODEM_TX_PIN;
  Serial.println("MODEM: baud detection failed; no clean ASCII OK on either pin map");
  Serial.println("MODEM: check 12V DTU, common GND, TXD/RXD to IO18/IO17, then RESET ESP");
  return false;
}

bool configureModem() {
  // Voice path first: never auto-answer (product hangs up via AT+CHUP).
  static const char *const baseCommands[] = {
      "ATE0",
      "AT+CMEE=2",
      "AT+CPIN?",
      "AT+CFUN=1",
      "ATS0=0",
      "AT+CLIP=1",
      "AT+CSQ",
      "AT+CREG?",
      "AT+CEREG?",
  };
  bool allOk = true;
  for (const char *command : baseCommands) {
    if (!sendATWait(command, 5000)) allOk = false;
  }

  // Optional cellular data (API usually goes over Wi‑Fi). APN from NVS only —
  // never hardcode carrier user/password in source.
  if (cellularApn.length() > 0) {
    String cgdcont = "AT+CGDCONT=1,\"IP\",\"" + cellularApn + "\"";
    if (!sendATWait(cgdcont.c_str(), 5000)) allOk = false;
    if (cellularUser.length() > 0) {
      // PAP auth type 1 — credentials redacted in sendAT logs
      String cgauth = "AT+CGAUTH=1,1,\"" + cellularUser + "\",\"" + cellularPass + "\"";
      if (!sendATWait(cgauth.c_str(), 5000)) allOk = false;
    }
    if (!sendATWait("AT+CGATT=1", 30000)) allOk = false;
    if (!sendATWait("AT+CGACT=1,1", 30000)) allOk = false;
    if (!sendATWait("AT+CGPADDR=1", 5000)) allOk = false;
    if (!sendATWait("AT+NETOPEN", 30000)) allOk = false;
  } else {
    Serial.println("MODEM: no APN in NVS — skip PDP (Serial: APN \"name\" [user] [pass])");
  }

  // SMS + GNSS (voice/gate still work if these fail)
  if (!sendATWait("AT+CMGF=1", 5000)) allOk = false;
  if (!sendATWait("AT+CSCS=\"GSM\"", 5000)) allOk = false;
  sendATWait("AT+CGPS=1", 5000);
  return allOk;
}

String responseLineStartingWith(const String &prefix) {
  int from = 0;
  while (from < atCommandResponse.length()) {
    int end = atCommandResponse.indexOf('\n', from);
    if (end < 0) end = atCommandResponse.length();
    String line = atCommandResponse.substring(from, end);
    line.trim();
    if (line.startsWith(prefix)) return line;
    from = end + 1;
  }
  return "";
}

bool parseRegistered(const String &line) {
  const int colon = line.indexOf(':');
  if (colon < 0) return false;
  String payload = line.substring(colon + 1);
  payload.trim();
  const int lastComma = payload.lastIndexOf(',');
  String status = lastComma >= 0 ? payload.substring(lastComma + 1) : payload;
  status.trim();
  return status == "1" || status == "5";
}

double gnssDegrees(const String &raw, const String &hemisphere) {
  if (!raw.length()) return NAN;
  const double value = raw.toDouble();
  const int degrees = (int)(value / 100.0);
  const double minutes = value - degrees * 100.0;
  if (minutes < 0 || minutes >= 60) return NAN;
  double result = degrees + minutes / 60.0;
  if (hemisphere == "S" || hemisphere == "W") result = -result;
  return result;
}

bool parseGnssInfo(const String &line) {
  const int colon = line.indexOf(':');
  if (colon < 0) return false;
  String payload = line.substring(colon + 1);
  payload.trim();
  String fields[9];
  int fieldIndex = 0;
  int start = 0;
  while (fieldIndex < 9 && start <= payload.length()) {
    int comma = payload.indexOf(',', start);
    if (comma < 0) comma = payload.length();
    fields[fieldIndex] = payload.substring(start, comma);
    fields[fieldIndex].trim();
    fieldIndex++;
    start = comma + 1;
  }
  if (fieldIndex < 4 || !fields[0].length() || !fields[2].length()) return false;
  const double latitude = gnssDegrees(fields[0], fields[1]);
  const double longitude = gnssDegrees(fields[2], fields[3]);
  if (isnan(latitude) || isnan(longitude) ||
      latitude < -90 || latitude > 90 ||
      longitude < -180 || longitude > 180) return false;
  lastGnssFix.valid = true;
  lastGnssFix.latitude = latitude;
  lastGnssFix.longitude = longitude;
  if (fieldIndex > 6) lastGnssFix.altitudeMetres = fields[6].toDouble();
  if (fieldIndex > 7) lastGnssFix.speedKnots = fields[7].toDouble();
  if (fieldIndex > 8) lastGnssFix.headingDegrees = fields[8].toDouble();
  lastGnssFix.capturedAtEpoch = (int64_t)time(nullptr);
  return true;
}

void queryLiveModemTelemetry() {
  if (ringPending || hangupPending || relayActive || smsState != SmsState::idle) return;

  if (sendATWait("AT+CSQ", 1500)) {
    const String line = responseLineStartingWith("+CSQ:");
    int colon = line.indexOf(':');
    int comma = line.indexOf(',', colon + 1);
    if (colon >= 0) {
      String raw = line.substring(colon + 1, comma >= 0 ? comma : line.length());
      raw.trim();
      const int value = raw.toInt();
      modemSignalStrength = value >= 0 && value <= 31 ? value : -1;
    }
  }
  if (ringPending || hangupPending) return;
  if (sendATWait("AT+CEREG?", 1500)) {
    modemNetworkRegistered = parseRegistered(responseLineStartingWith("+CEREG:"));
  }
  if (ringPending || hangupPending) return;
  if (sendATWait("AT+COPS?", 1500)) {
    String line = responseLineStartingWith("+COPS:");
    int firstQuote = line.indexOf('"');
    int secondQuote = firstQuote >= 0 ? line.indexOf('"', firstQuote + 1) : -1;
    modemOperator =
        firstQuote >= 0 && secondQuote > firstQuote
            ? line.substring(firstQuote + 1, secondQuote)
            : "";
  }
  if (ringPending || hangupPending) return;
  if (sendATWait("AT+CPSI?", 1500)) {
    modemRadioTechnology = responseLineStartingWith("+CPSI:");
    if (modemRadioTechnology.length() > 80) {
      modemRadioTechnology = modemRadioTechnology.substring(0, 80);
    }
  }
  if (ringPending || hangupPending) return;
  if (sendATWait("AT+CGPSINFO", 2500)) {
    parseGnssInfo(responseLineStartingWith("+CGPSINFO:"));
  }
}

// -- Phone ----------------------------------------
String normalizePhoneNumber(const String &raw) {
  String cleaned;
  for (size_t i = 0; i < raw.length(); i++) {
    char c = raw.charAt(i);
    if (isDigit((unsigned char)c)) cleaned += c;
    else if (c == '+' && cleaned.length() == 0) cleaned += c;
  }
  if (cleaned.isEmpty()) return "";

  // Already E.164: + then 8–15 digits (ITU max 15 national digits)
  if (cleaned.startsWith("+")) {
    const size_t digits = cleaned.length() - 1;
    if (digits < 8 || digits > 15) return "";
    return cleaned;
  }
  // International prefix 00…
  if (cleaned.startsWith("00")) {
    if (cleaned.length() < 10 || cleaned.length() > 17) return "";
    return "+" + cleaned.substring(2);
  }
  // AU national trunk: 0 + 9-digit NSN (e.g. 04xx xxx xxx)
  if (cleaned.startsWith("0") && cleaned.length() == 10) {
    return "+61" + cleaned.substring(1);
  }
  // AU mobile without trunk or country: 4xxxxxxxx (9 digits)
  if (cleaned.startsWith("4") && cleaned.length() == 9) {
    return "+61" + cleaned;
  }
  // Country code without + : only if length looks like a full AU number
  // (61 + 9-digit NSN = 11; allow up to 13 for other 61-area lengths)
  if (cleaned.startsWith("61") && cleaned.length() >= 11 && cleaned.length() <= 13) {
    return "+" + cleaned;
  }
  // Never return bare non-E.164 digits (call path / SMS require leading +).
  return "";
}

String extractCallerNumber(const String &clipLine) {
  int a = clipLine.indexOf('"');
  if (a < 0) return "";
  int b = clipLine.indexOf('"', a + 1);
  if (b < 0) return "";
  return clipLine.substring(a + 1, b);
}

String extractPhoneCandidate(const String &line) {
  String longest;
  String current;
  for (size_t i = 0; i < line.length(); i++) {
    const char c = line.charAt(i);
    if (c == '+' && current.length() == 0) {
      current += c;
    } else if (isDigit((unsigned char)c)) {
      current += c;
    } else {
      const size_t digits = current.startsWith("+") ? current.length() - 1 : current.length();
      const size_t bestDigits = longest.startsWith("+") ? longest.length() - 1 : longest.length();
      if (digits >= 9 && digits <= 15 && digits > bestDigits) longest = current;
      current = "";
    }
  }
  const size_t digits = current.startsWith("+") ? current.length() - 1 : current.length();
  const size_t bestDigits = longest.startsWith("+") ? longest.length() - 1 : longest.length();
  if (digits >= 9 && digits <= 15 && digits > bestDigits) longest = current;
  return longest;
}

int findCallerIndex(const String &e164) {
  const time_t now = time(nullptr);
  for (size_t i = 0; i < callerCount; i++) {
    if (!callers[i].enabled) continue;
    if (callers[i].validUntilEpoch > 0 &&
        (now < 1700000000 || (int64_t)now > callers[i].validUntilEpoch)) continue;
    if (e164.equals(callers[i].e164)) return (int)i;
  }
  return -1;
}

int64_t parseIsoUtcEpoch(const char *iso) {
  if (!iso || !iso[0]) return 0;
  int year, month, day, hour, minute, second;
  if (sscanf(iso, "%d-%d-%dT%d:%d:%d", &year, &month, &day,
             &hour, &minute, &second) != 6) return -1;
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour < 0 ||
      hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59)
    return -1;
  // Gregorian UTC date to Unix epoch; avoids timezone-dependent mktime().
  year -= month <= 2;
  const int era = (year >= 0 ? year : year - 399) / 400;
  const unsigned yoe = (unsigned)(year - era * 400);
  const unsigned doy = (153U * (month + (month > 2 ? -3 : 9)) + 2U) / 5U +
                       (unsigned)day - 1U;
  const unsigned doe = yoe * 365U + yoe / 4U - yoe / 100U + doy;
  const int64_t days = (int64_t)era * 146097LL + doe - 719468LL;
  return days * 86400LL + hour * 3600LL + minute * 60LL + second;
}

// -- SMS ----------------------------------------
// Returns true only after +CMGS: <n> and OK
bool isValidAustralianMobile(const String &number) {
  if (number.length() != 12 || !number.startsWith("+614")) return false;
  for (size_t i = 1; i < number.length(); ++i) {
    if (!isDigit((unsigned char)number.charAt(i))) return false;
  }
  return true;
}

void resetSmsResponse() {
  smsResponseOk = false;
  smsResponseError = false;
  smsPromptSeen = false;
  smsCmgsSeen = false;
}

void finishSms(bool ok, const char *error = nullptr) {
  smsCommandMode = false;
  activeSms.ok = ok;
  activeSms.error = error ? error : "";
  if (ok) {
    activeSms.sentAtEpoch = (int64_t)time(nullptr);
    prefs.begin("sgpro_sms", false);
    prefs.putString("doneId", activeSms.jobId);
    prefs.putString("doneRef", activeSms.cmgsReference);
    prefs.putLong64("doneAt", activeSms.sentAtEpoch);
    prefs.end();
  }
  smsState = SmsState::acknowledge;
  smsStateStartedAt = millis();
}

bool beginSmsJob(const char *jobId, const char *rawPhone, const char *message) {
  if (smsState != SmsState::idle || ringPending || hangupPending ||
      relayActive || millis() - lastSmsFinishedAt < SMS_COOLDOWN_MS) return false;
  const String phone = normalizePhoneNumber(rawPhone);
  activeSms = {};
  activeSms.jobId = jobId;
  activeSms.phone = phone;
  activeSms.message = message;
  prefs.begin("sgpro_sms", true);
  const String completedJobId = prefs.getString("doneId", "");
  if (completedJobId == activeSms.jobId) {
    activeSms.cmgsReference = prefs.getString("doneRef", "");
    activeSms.sentAtEpoch = prefs.getLong64("doneAt", 0);
    prefs.end();
    activeSms.ok = true;
    smsState = SmsState::acknowledge;
    smsStateStartedAt = millis();
    Serial.printf("SMS: job=%s already completed locally; acknowledging only\n", jobId);
    return true;
  }
  prefs.end();
  if (!isValidAustralianMobile(phone)) {
    finishSms(false, "invalid_number");
    return true;
  }
  Serial.printf("SMS: starting job=%s to=%s\n", jobId, phone.c_str());
  resetSmsResponse();
  sendAT("AT+CMGF=1");
  smsState = SmsState::waitCmgf;
  smsStateStartedAt = millis();
  return true;
}

void serviceSmsModem() {
  if (smsState == SmsState::idle || smsState == SmsState::acknowledge) return;
  if (smsState != SmsState::waitSubmit &&
      (ringPending || hangupPending || relayActive)) {
    if (smsCommandMode) writeModemByte(27);
    finishSms(false, "call_preempted");
    return;
  }
  const uint32_t elapsed = millis() - smsStateStartedAt;
  if (smsResponseError) {
    finishSms(false, "modem_error");
    return;
  }
  switch (smsState) {
    case SmsState::waitCmgf:
      if (smsResponseOk) {
        resetSmsResponse();
        sendAT("AT+CSCS=\"GSM\"");
        smsState = SmsState::waitCscs;
        smsStateStartedAt = millis();
      } else if (elapsed >= SMS_COMMAND_TIMEOUT_MS) finishSms(false, "cmgf_timeout");
      break;
    case SmsState::waitCscs:
      if (smsResponseOk) {
        resetSmsResponse();
        smsCommandMode = true;
        writeModem("AT+CMGS=\"");
        writeModem(activeSms.phone);
        writeModem("\"\r");
        smsState = SmsState::waitPrompt;
        smsStateStartedAt = millis();
      } else if (elapsed >= SMS_COMMAND_TIMEOUT_MS) finishSms(false, "cscs_timeout");
      break;
    case SmsState::waitPrompt:
      if (smsPromptSeen) {
        resetSmsResponse();
        writeModem(activeSms.message);
        writeModemByte(0x1A);
        smsCommandMode = false;
        smsState = SmsState::waitSubmit;
        smsStateStartedAt = millis();
      } else if (elapsed >= SMS_COMMAND_TIMEOUT_MS) finishSms(false, "prompt_timeout");
      break;
    case SmsState::waitSubmit:
      if (smsCmgsSeen && smsResponseOk) finishSms(true);
      else if (elapsed >= SMS_SUBMIT_TIMEOUT_MS) finishSms(false, "submit_timeout");
      break;
    default:
      break;
  }
}

// -- Auth + HTTP ----------------------------------------
String randomNonceHex(size_t bytes = 12) {
  String s;
  for (size_t i = 0; i < bytes; i++) {
    uint8_t b = (uint8_t)esp_random();
    char h[3];
    sprintf(h, "%02x", b);
    s += h;
  }
  return s;
}

String sha256Hex(const String &value) {
  byte out[32];
  mbedtls_md_context_t ctx;
  const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, info, 0);
  mbedtls_md_starts(&ctx);
  mbedtls_md_update(&ctx, (const unsigned char *)value.c_str(), value.length());
  mbedtls_md_finish(&ctx, out);
  mbedtls_md_free(&ctx);
  char hex[65];
  for (int i = 0; i < 32; i++) sprintf(hex + i * 2, "%02x", out[i]);
  hex[64] = 0;
  return String(hex);
}

String hmacSha256Hex(const String &key, const String &msg) {
  byte out[32];
  mbedtls_md_context_t ctx;
  const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, info, 1);
  mbedtls_md_hmac_starts(&ctx, (const unsigned char *)key.c_str(), key.length());
  mbedtls_md_hmac_update(&ctx, (const unsigned char *)msg.c_str(), msg.length());
  mbedtls_md_hmac_finish(&ctx, out);
  mbedtls_md_free(&ctx);
  char hex[65];
  for (int i = 0; i < 32; i++) sprintf(hex + i * 2, "%02x", out[i]);
  hex[64] = 0;
  return String(hex);
}

bool httpSigned(
    const char *method,
    const String &path,
    const String &body,
    String &responseOut
) {
  if (deviceSecret.length() < 16) {
    Serial.println("HTTP: no device secret in NVS -- run PROVISION");
    return false;
  }
  if (WiFi.status() != WL_CONNECTED) return false;

  time_t now = time(nullptr);
  if (now < 1700000000) {
    syncNtpClock();
    // Unsigned elapsed: safe across millis() wrap (period << 49 days).
    const uint32_t started = millis();
    do {
      delay(100);
      now = time(nullptr);
    } while (now < 1700000000 && (millis() - started) < 10000UL);
    if (now < 1700000000) {
      Serial.println("HTTP: clock not synchronized; request not signed");
      return false;
    }
    ensureMelbourneTz();
  }
  String ts = String((uint64_t)now * 1000ULL);
  String nonce = randomNonceHex();
  String signPayload =
      String(method) + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + body;
  // Auth v2 uses the provisioned derived hash as the HMAC key; raw secret stays in NVS.
  String authKey = sha256Hex(deviceSecret + ":" + DEVICE_ID);
  String sig = hmacSha256Hex(authKey, signPayload);

  String url = String(API_BASE) + path;
  WiFiClientSecure client;
  client.setCACert(GTS_ROOT_R1);
  HTTPClient http;
  if (!http.begin(client, url)) return false;
  http.setConnectTimeout(5000);
  http.setTimeout(10000);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Id", DEVICE_ID);
  http.addHeader("X-Auth-Version", "2");
  http.addHeader("X-Timestamp", ts);
  http.addHeader("X-Nonce", nonce);
  http.addHeader("X-Secret-Version", String(secretVersion));
  http.addHeader("X-Signature", sig);

  int code = (strcmp(method, "GET") == 0) ? http.GET() : http.POST(body);
  responseOut = http.getString();
  http.end();
  Serial.printf("HTTP %s %s -> %d\n", method, path.c_str(), code);
  if (code < 200 || code >= 300) {
    String diagnostic = responseOut;
    if (diagnostic.length() > 512) diagnostic = diagnostic.substring(0, 512) + "...";
    Serial.print("HTTP response: ");
    Serial.println(diagnostic.length() ? diagnostic : "<empty>");
  }
  return code >= 200 && code < 300;
}

// Prevent overlapping WiFi.begin() ("sta is connecting, cannot set config")
static bool wifiBusy = false;
static uint32_t wifiLastAttemptMs = 0;

static void wifiRadioReset() {
  WiFi.persistent(false);
  WiFi.disconnect(true, true);
  delay(300);
  WiFi.mode(WIFI_OFF);
  delay(400);
  WiFi.mode(WIFI_STA);
  delay(200);
}

void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) {
    return;
  }
  if (wifiSsid.length() < 1) {
    Serial.println("WiFi: not provisioned -- Serial: WIFI \"ssid\" password");
    return;
  }
  // Cooldown: do not start another begin() while one is in flight
  if (wifiBusy) {
    return;
  }
  // Rate-limit retries from loop/SYNC (max once per 20s when offline)
  if (wifiLastAttemptMs != 0 && (millis() - wifiLastAttemptMs) < 20000UL) {
    return;
  }

  wifiBusy = true;
  wifiLastAttemptMs = millis();

  wifiRadioReset();
  Serial.printf("WiFi: connecting to \"%s\" ...\n", wifiSsid.c_str());
  wl_status_t beginSt = WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  if (beginSt == WL_CONNECT_FAILED) {
    Serial.println("WiFi: begin rejected -- will retry later");
    wifiBusy = false;
    return;
  }

  for (int i = 0; i < 80; i++) { // ~20s
    wl_status_t st = WiFi.status();
    if (st == WL_CONNECTED) {
      Serial.printf("WiFi: CONNECTED  ip=%s  rssi=%d dBm\n",
                    WiFi.localIP().toString().c_str(), WiFi.RSSI());
      syncNtpClock();
      wifiBusy = false;
      return;
    }
    if (st == WL_NO_SSID_AVAIL) {
      Serial.println("WiFi: SSID not found (is it 2.4GHz and in range?)");
      wifiRadioReset();
      wifiBusy = false;
      return;
    }
    if (st == WL_CONNECT_FAILED) {
      Serial.println("WiFi: connect failed (wrong password?)");
      wifiRadioReset();
      wifiBusy = false;
      return;
    }
    delay(250);
  }
  Serial.printf("WiFi: timeout status=%d\n", (int)WiFi.status());
  wifiRadioReset();
  wifiBusy = false;
}

/** Force reconnect now (Serial: RECONNECT) -- ignores cooldown. */
void forceWifiReconnect() {
  wifiLastAttemptMs = 0;
  wifiBusy = false;
  if (WiFi.status() == WL_CONNECTED) {
    WiFi.disconnect(true, false);
    delay(200);
  }
  ensureWifi();
}

// -- Whitelist pull: last-known-good until fully validated -----------------
// Do NOT replace working list until: authenticated (HTTP ok) + full download
// + parse success + validated + save success. Failed/empty body keeps LKG.
bool pullWhitelist() {
  ensureWifi();
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Whitelist: offline -- keeping last-known-good");
    return false;
  }

  String path = String("/gsm/device/") + DEVICE_ID + "/whitelist";
  String resp;
  if (!httpSigned("GET", path, "", resp)) {
    Serial.println("Whitelist: download failed -- keeping last-known-good");
    return false;
  }
  if (resp.length() < 8) {
    Serial.println("Whitelist: empty response -- keeping last-known-good");
    return false;
  }

  JsonDocument doc;
  if (deserializeJson(doc, resp)) {
    Serial.println("Whitelist: parse failed -- keeping last-known-good");
    return false;
  }
  if (!doc["version"].is<int64_t>() || !doc["callers"].is<JsonArray>()) {
    Serial.println("Whitelist: invalid shape -- keeping last-known-good");
    return false;
  }
  String canonicalCallers;
  serializeJson(doc["callers"], canonicalCallers);
  const String expectedChecksum = doc["whitelistChecksum"] | "";
  const String actualChecksum = sha256Hex(canonicalCallers);
  if (expectedChecksum.length() != 64 ||
      !actualChecksum.equalsIgnoreCase(expectedChecksum)) {
    Serial.println("Whitelist: checksum mismatch - keeping last-known-good");
    return false;
  }

  // Build TEMP list only; do not touch active callers[] yet
  static CallerEntry temp[MAX_CALLERS];
  size_t n = 0;
  bool anyInvalid = false;
  for (JsonObject c : doc["callers"].as<JsonArray>()) {
    if (n >= MAX_CALLERS) break;
    const char *phone = c["phoneNumberE164"] | "";
    // Production storage is E.164 only
    if (phone[0] != '+' || strlen(phone) < 9 || strlen(phone) > 16) {
      anyInvalid = true;
      continue;
    }
    bool digitsOk = true;
    for (size_t k = 1; phone[k]; k++) {
      if (phone[k] < '0' || phone[k] > '9') {
        digitsOk = false;
        break;
      }
    }
    if (!digitsOk) {
      anyInvalid = true;
      continue;
    }
    strncpy(temp[n].e164, phone, 23);
    temp[n].e164[23] = 0;
    strncpy(temp[n].id, c["id"] | "", 39);
    temp[n].id[39] = 0;
    strncpy(temp[n].name, c["name"] | "", 39);
    temp[n].name[39] = 0;
    // A backend entry is never authorized unless explicitly enabled.
    temp[n].enabled = c["enabled"] | false;
    temp[n].validUntilEpoch = parseIsoUtcEpoch(c["validUntil"] | "");
    if (temp[n].validUntilEpoch < 0) {
      anyInvalid = true;
      continue;
    }
    n++;
  }
  // Empty callers[] is valid (all deleted). Malformed-only payload with no
  // valid numbers when server sent entries is treated as failed validation.
  if (anyInvalid && n == 0 && doc["callers"].as<JsonArray>().size() > 0) {
    Serial.println("Whitelist: validation failed -- keeping last-known-good");
    return false;
  }

  // Persist and verify the inactive NVS slot before changing the active list.
  int64_t newVer = doc["version"] | 0;
  // Skip dual-slot NVS rewrite when version matches and checksum is unknown or equal.
  // (Checksum used to be RAM-only → after reboot length!=64 → rewrote every poll.)
  // Do NOT return early: same poll still services smsJobs + remoteCommands below.
  const bool sameWhitelist =
      newVer == localWhitelistVersion &&
      (localWhitelistChecksum.length() != 64 ||
       localWhitelistChecksum.equalsIgnoreCase(actualChecksum));

  if (sameWhitelist) {
    if (!localWhitelistChecksum.equalsIgnoreCase(actualChecksum)) {
      localWhitelistChecksum = actualChecksum;
      persistWhitelistMeta(newVer, localWhitelistChecksum);
    }
    Serial.printf("Whitelist unchanged v%lld; skip NVS write\n", (long long)newVer);
  } else {
    if (!persistWhitelistAtomic(temp, n, newVer)) return false;
    for (size_t i = 0; i < n; i++) callers[i] = temp[i];
    callerCount = n;
    localWhitelistVersion = newVer;
    localWhitelistChecksum = actualChecksum;
    persistWhitelistMeta(newVer, localWhitelistChecksum);

    // Download is not "synced" until the committed NVS image is acknowledged.
    JsonDocument ackDoc;
    ackDoc["version"] = localWhitelistVersion;
    ackDoc["callerCount"] = callerCount;
    ackDoc["whitelistChecksum"] = localWhitelistChecksum;
    String ackBody;
    serializeJson(ackDoc, ackBody);
    String ackResponse;
    if (!httpSigned(
            "POST",
            String("/gsm/device/") + DEVICE_ID + "/whitelist-ack",
            ackBody,
            ackResponse)) {
      Serial.println("Whitelist: applied locally; acknowledgement pending");
    }
  }

  // Lease at most one job. Modem transmission continues non-blockingly in loop().
  if (smsState == SmsState::idle &&
      millis() - lastSmsFinishedAt >= SMS_COOLDOWN_MS &&
      doc["smsJobs"].is<JsonArray>()) {
    for (JsonObject j : doc["smsJobs"].as<JsonArray>()) {
      const char *jid = j["jobId"] | "";
      const char *phone = j["phoneNumberE164"] | "";
      const char *msg = j["message"] | "";
      if (!jid[0] || !phone[0] || !msg[0]) continue;
      JsonDocument claimDoc;
      claimDoc["jobIds"].to<JsonArray>().add(jid);
      String claimBody;
      serializeJson(claimDoc, claimBody);
      String claimResp;
      JsonDocument claimResult;
      if (httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/sms-claim",
                     claimBody, claimResp) &&
          !deserializeJson(claimResult, claimResp) &&
          claimResult["claimedJobIds"].is<JsonArray>() &&
          claimResult["claimedJobIds"].as<JsonArray>().size() == 1) {
        beginSmsJob(jid, phone, msg);
      }
      break;
    }
  }

  // Remote owner test: claim once in the cloud before touching the relay.
  // This makes the test available from the app without a PC at the controller.
  if (smsState == SmsState::idle && !ringPending && !hangupPending &&
      !relayActive && doc["remoteCommands"].is<JsonArray>()) {
    for (JsonObject command : doc["remoteCommands"].as<JsonArray>()) {
      const char *commandId = command["commandId"] | "";
      const char *type = command["type"] | "";
      if (!commandId[0] || strcmp(type, "remote_gate_test") != 0) continue;

      JsonDocument claimDoc;
      claimDoc["commandId"] = commandId;
      String claimBody;
      serializeJson(claimDoc, claimBody);
      String claimResponse;
      JsonDocument claimResult;
      const bool claimed =
          httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/command-claim",
                     claimBody, claimResponse) &&
          !deserializeJson(claimResult, claimResponse) &&
          (claimResult["claimed"] | false);
      if (!claimed) break;

      startRelayPulse();
      // Day hold: startRelayPulse re-asserts AP and does not set relayActive.
      // That is still a successful "gate open" path — ack success, not relay_busy.
      const bool testOk = relayActive || dayHoldActive;
      JsonDocument ackDoc;
      ackDoc["commandId"] = commandId;
      ackDoc["triggered"] = testOk;
      if (dayHoldActive && !relayActive) {
        ackDoc["mode"] = "day_hold";
      }
      if (!testOk) ackDoc["error"] = "relay_busy";
      String ackBody;
      serializeJson(ackDoc, ackBody);
      String ackResponse;
      httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/command-ack",
                 ackBody, ackResponse);
      Serial.printf("Remote gate test %s: %s%s\n", commandId,
                    testOk ? "triggered" : "failed",
                    (dayHoldActive && !relayActive) ? " (day hold)" : "");
      break;
    }
  }

  Serial.printf("Whitelist active v%lld n=%u\n", (long long)localWhitelistVersion, (unsigned)n);
  return true;
}

void serviceSmsAcknowledgement() {
  if (smsState != SmsState::acknowledge || ringPending || hangupPending ||
      relayActive || WiFi.status() != WL_CONNECTED) return;
  static uint32_t lastAckAttemptAt = 0;
  if (lastAckAttemptAt && millis() - lastAckAttemptAt < 30000UL) return;
  lastAckAttemptAt = millis();
  JsonDocument ackDoc;
  JsonObject result = ackDoc["results"].to<JsonArray>().add<JsonObject>();
  result["jobId"] = activeSms.jobId;
  result["ok"] = activeSms.ok;
  result["phoneNumberE164"] = activeSms.phone;
  if (activeSms.ok) {
    result["cmgsReference"] = activeSms.cmgsReference;
    result["sentAtEpoch"] = activeSms.sentAtEpoch;
  } else {
    result["error"] = activeSms.error;
  }
  String body;
  serializeJson(ackDoc, body);
  String response;
  if (!httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/sms-ack",
                  body, response)) return;
  Serial.printf("SMS: job=%s state=%s ref=%s\n", activeSms.jobId.c_str(),
                activeSms.ok ? "sent" : "failed",
                activeSms.ok ? activeSms.cmgsReference.c_str() : "-");
  activeSms = {};
  smsState = SmsState::idle;
  lastSmsFinishedAt = millis();
  lastWhitelistPull = millis() - WHITELIST_POLL_MS + SMS_COOLDOWN_MS;
  lastAckAttemptAt = 0;
}

void sendHeartbeat() {
  ensureWifi();
  queryLiveModemTelemetry();
  JsonDocument doc;
  doc["firmwareVersion"] = "3.1.0-prod";
  doc["modemModel"] = "SIM7600G-H";
  doc["networkRegistered"] = modemNetworkRegistered;
  if (modemSignalStrength >= 0) doc["signalStrength"] = modemSignalStrength;
  if (modemOperator.length()) doc["operator"] = modemOperator;
  if (modemRadioTechnology.length()) {
    doc["radioTechnology"] = modemRadioTechnology;
  }
  doc["whitelistVersion"] = localWhitelistVersion;
  doc["whitelistChecksum"] = localWhitelistChecksum;
  doc["whitelistCallerCount"] = callerCount;
  doc["deviceName"] = "SG-PRO GSM Gate";
  doc["uptimeSec"] = millis() / 1000;
  if (lastGnssFix.valid) {
    JsonObject location = doc["location"].to<JsonObject>();
    location["latitude"] = lastGnssFix.latitude;
    location["longitude"] = lastGnssFix.longitude;
    location["altitudeMetres"] = lastGnssFix.altitudeMetres;
    location["speedKnots"] = lastGnssFix.speedKnots;
    location["headingDegrees"] = lastGnssFix.headingDegrees;
    location["capturedAtEpoch"] = lastGnssFix.capturedAtEpoch;
    location["source"] = "SIM7600_GNSS";
  }
  String body;
  serializeJson(doc, body);
  String resp;
  if (!httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/heartbeat", body, resp)) return;
  JsonDocument rdoc;
  if (!deserializeJson(rdoc, resp)) {
    int64_t srv = rdoc["accountWhitelistVersion"] | 0;
    if (srv > localWhitelistVersion) lastWhitelistPull = 0;
  }
}

struct PendingCallEvent {
  bool pending = false;
  String callerNumberE164;
  bool authorised = false;
  bool relayTriggered = false;
  String rejectionReason;
  String matchedCallerId;
  String matchedCallerName;
};
PendingCallEvent pendingCallEvent;

void queueCallEvent(const String &e164, bool authorised, bool relay, const char *reason, int matchedIdx) {
  if (pendingCallEvent.pending) {
    Serial.println("EVENT: previous call event still pending; newest event dropped");
    return;
  }
  pendingCallEvent.pending = true;
  pendingCallEvent.callerNumberE164 = e164.length() ? e164 : "WITHHELD";
  pendingCallEvent.authorised = authorised;
  pendingCallEvent.relayTriggered = relay;
  pendingCallEvent.rejectionReason = reason;
  pendingCallEvent.matchedCallerId = matchedIdx >= 0 ? String(callers[matchedIdx].id) : "";
  pendingCallEvent.matchedCallerName = matchedIdx >= 0 ? String(callers[matchedIdx].name) : "";
}

void processPendingCallEvent() {
  if (!pendingCallEvent.pending || relayActive) return;
  ensureWifi();
  if (WiFi.status() != WL_CONNECTED) {
    // Keep pending — do not drop (Mildura-class: lost evidence / silent fail)
    Serial.println("EVENT: offline; will retry call event upload");
    return;
  }
  JsonDocument doc;
  doc["callerNumberE164"] = pendingCallEvent.callerNumberE164;
  doc["authorised"] = pendingCallEvent.authorised;
  doc["relayTriggered"] = pendingCallEvent.relayTriggered;
  doc["rejectionReason"] = pendingCallEvent.rejectionReason;
  if (pendingCallEvent.matchedCallerId.length()) {
    doc["matchedCallerId"] = pendingCallEvent.matchedCallerId;
    doc["matchedCallerName"] = pendingCallEvent.matchedCallerName;
  }
  String body;
  serializeJson(doc, body);
  String response;
  if (!httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/events", body, response)) {
    Serial.println("EVENT: upload failed; will retry");
    return;
  }
  pendingCallEvent.pending = false;
  pendingCallEvent = {};
}

// -- Call path (local only -- never waits for cloud) ----------------------------------------
bool callLockoutActive() {
  return callLockout;
}

void startCallLockout() {
  callLockout = true;
  callLockoutStartedAt = millis();
  Serial.println("CALL: ten-second lockout started");
}

void updateCallLockout() {
  if (callLockout && millis() - callLockoutStartedAt >= CALL_LOCKOUT_MS) {
    callLockout = false;
    resetIncomingCallState();
    Serial.println("CALL: ten-second lockout ended");
  }
}

const char *callerRejectionReason(const String &e164) {
  const time_t now = time(nullptr);
  for (size_t i = 0; i < callerCount; i++) {
    if (!e164.equals(callers[i].e164)) continue;
    if (!callers[i].enabled) return "DISABLED";
    if (callers[i].validUntilEpoch > 0 &&
        (now < 1700000000 || (int64_t)now > callers[i].validUntilEpoch))
      return "EXPIRED";
  }
  return "UNKNOWN";
}

bool isValidCallerNumber(const String &number) {
  return number.length() >= 9 && number.length() <= 16 &&
         number.startsWith("+");
}

bool cacheIncomingCaller(const String &rawNumber, const char *source) {
  const String normalizedNumber = normalizePhoneNumber(rawNumber);
  if (!isValidCallerNumber(normalizedNumber)) return false;
  if (temporaryCallerNumber != normalizedNumber) {
    temporaryCallerNumber = normalizedNumber;
    Serial.printf("CALL: Caller ID cached from %s: %s\n", source,
                  temporaryCallerNumber.c_str());
  }
  return true;
}

void resetIncomingCallState() {
  ringPending = false;
  firstRingObserved = false;
  hasHandledCall = false;
  temporaryCallerNumber = "";
  lastClccQueryAt = 0;
  clccReplyPending = false;
  relayQueuedAfterHangup = false;
}

// After CHUP finishes (or fails): free state machine so the next call works.
// Accepted calls already use callLockout — clear when lockout ends.
// Rejected / day / hidden MUST still clear or ringPending/hasHandledCall stick forever.
void onHangupFinished(const char *how) {
  hangupPending = false;
  hangupAttempts = 0;
  relayQueuedAfterHangup = false;
  Serial.printf("CALL: hang-up finished (%s)\n", how ? how : "?");
  if (callLockoutActive()) {
    // Accepted-call path: keep hasHandledCall until lockout ends (updateCallLockout).
    return;
  }
  // Reject / day / hidden / malformed / failsafe — unlock for next caller
  resetIncomingCallState();
}

void hangUpCall() {
  if (hangupPending) return;
  if (smsCommandMode) {
    // Escape SIM7600 SMS text-entry mode before issuing any AT command.
    writeModemByte(27);
    modem.flush(true);
    smsCommandMode = false;
    delay(20);
  }
  sendAT("AT+CHUP");
  // flush() with no argument clears RX on Arduino-ESP32. txOnly=true waits for
  // the command to leave UART without deleting modem confirmations/URCs.
  modem.flush(true);
  Serial.println("CALL: AT+CHUP sent");
  hangupPending = true;
  hangupAttempts = 1;
  lastHangupAttemptAt = millis();
}

void serviceHangup() {
  if (!hangupPending || millis() - lastHangupAttemptAt < HANGUP_RETRY_MS) return;
  if (hangupAttempts >= HANGUP_MAX_ATTEMPTS) {
    Serial.println("CALL: AT+CHUP result: timeout");
    // Always force PP off; never touch day HOLD incorrectly
    if (!dayHoldActive) {
      relayActive = false;
      setPulseRelay(false);
      setHoldRelay(false);
    } else {
      setPulseRelay(false);
    }
    onHangupFinished("timeout");
    return;
  }
  sendAT("AT+CHUP");
  modem.flush(true);
  ++hangupAttempts;
  lastHangupAttemptAt = millis();
}

void detectIncomingCall() {
  if (hangupPending || callLockoutActive()) return;
  if (!ringPending) {
    ringPending = true;
    ringStartedAt = millis();
    Serial.println("CALL: first ring detected");
  }
}

void handleIncomingCaller(const String &rawNumber, const char *source) {
  if (hasHandledCall || hangupPending || callLockoutActive()) return;
  const String e164 = normalizePhoneNumber(rawNumber);
  if (!isValidCallerNumber(e164)) return;
  hasHandledCall = true;
  lastCallHandledAt = millis();
  temporaryCallerNumber = e164;
  Serial.printf("CALL: Caller recovered from %s: %s\n", source, e164.c_str());

  // Day hold: gate already constant-ON — hang up, do not start a timed pulse
  // (a 3s pulse would schedule an OFF and drop the day hold).
  if (dayHoldActive) {
    const int idxDay = findCallerIndex(e164);
    Serial.println("CALL: day hold — hang-up only (no night pulse)");
    relayQueuedAfterHangup = false;
    queueCallEvent(e164, idxDay >= 0, false,
                   idxDay >= 0 ? "DAY_HOLD" : "DAY_HOLD_UNKNOWN", idxDay);
    // Lockout so ringPending/hasHandledCall clear after CHUP (was stuck forever)
    startCallLockout();
    hangUpCall();
    return;
  }

  const int idx = findCallerIndex(e164);
  if (idx < 0) {
    const char *reason = callerRejectionReason(e164);
    Serial.printf("CALL: whitelist rejected %s reason=%s\n", e164.c_str(), reason);
    relayQueuedAfterHangup = false;
    queueCallEvent(e164, false, false, reason, -1);
    startCallLockout();
    hangUpCall();
    return;
  }
  Serial.printf("CALL: whitelist match %s caller=%s\n", e164.c_str(),
                callers[idx].name);
  startCallLockout();
  queueCallEvent(e164, true, true, "", idx);
  relayQueuedAfterHangup = false;
  hangUpCall();
  startRelayPulse();
}

void processModemLine(const String &rawLine, bool framingCorrupted) {
  bool invalidByte = false;
  for (size_t i = 0; i < rawLine.length(); ++i) {
    const uint8_t value = static_cast<uint8_t>(rawLine.charAt(i));
    if (value < 0x20 || value > 0x7e) invalidByte = true;
  }
  String line(rawLine);
  line.trim();
  const bool corrupted = framingCorrupted || invalidByte;
  if (modemHexDiagnostics || corrupted) printModemHex(rawLine);
  if (corrupted) {
    if (atCommandPending) atCommandCorrupted = true;
    Serial.println("MODEM: invalid raw bytes; line rejected");
    return; // Never turn damaged input into a valid command/phone number.
  }
  if (!line.length()) return;
  Serial.print("MODEM: ");
  Serial.println(line);

  if (smsState != SmsState::idle && smsState != SmsState::acknowledge) {
    if (line == "OK") smsResponseOk = true;
    else if (line == "ERROR" || line.startsWith("+CME ERROR") ||
             line.startsWith("+CMS ERROR")) smsResponseError = true;
    else if (line.startsWith("+CMGS:")) {
      activeSms.cmgsReference = line.substring(6);
      activeSms.cmgsReference.trim();
      smsCmgsSeen = activeSms.cmgsReference.length() > 0;
    }
  }

  if (atCommandPending) {
    atCommandResponse += line;
    atCommandResponse += '\n';
    if (line == "OK") atCommandResult = 1;
    else if (line == "ERROR" || line.startsWith("+CME ERROR") ||
             line.startsWith("+CMS ERROR"))
      atCommandResult = -1;
  }

  if (line.startsWith("+CREG:") || line.startsWith("+CEREG:")) {
    Serial.printf("NETWORK: registration %s\n", line.c_str());
  }
  if (hangupPending && (line == "OK" || line == "ERROR" ||
                        line.startsWith("+CME ERROR"))) {
    Serial.printf("CALL: AT+CHUP result: %s\n", line.c_str());
    onHangupFinished(line.c_str());
  }

  if (callLockoutActive() &&
      (line == "RING" || line.startsWith("+CLIP:") ||
       line.indexOf("+CRING:") >= 0)) {
    return; // Ignore every incoming-call URC throughout the call lockout.
  }

  if (line == "OK" || line.startsWith("ERROR") || line.startsWith("+CME ERROR"))
    clccReplyPending = false;

  const bool cleanClip = line.startsWith("+CLIP:");
  const bool clcc = line.startsWith("+CLCC:");
  bool incomingClcc = false;
  if (clcc) {
    const int firstComma = line.indexOf(',');
    const int secondComma = firstComma >= 0 ? line.indexOf(',', firstComma + 1) : -1;
    const int thirdComma = secondComma >= 0 ? line.indexOf(',', secondComma + 1) : -1;
    if (secondComma > firstComma && thirdComma > secondComma) {
      String direction = line.substring(firstComma + 1, secondComma);
      String callState = line.substring(secondComma + 1, thirdComma);
      direction.trim();
      callState.trim();
      incomingClcc = direction == "1" &&
                     (callState == "4" || callState == "5");
    }
  }

  if (cleanClip) {
    const String clipNumber = extractCallerNumber(line);
    Serial.printf("CALL: caller ID raw=%s\n",
                  clipNumber.length() ? clipNumber.c_str() : "<hidden>");
    if (!clipNumber.length()) {
      if (!callLockoutActive() && !hasHandledCall && !hangupPending) {
        hasHandledCall = true;
        Serial.println("CALL: whitelist rejected reason=HIDDEN_PRIVATE");
        queueCallEvent("", false, false, "HIDDEN_PRIVATE", -1);
        startCallLockout();
        hangUpCall();
      }
    } else if (!cacheIncomingCaller(clipNumber, "CLIP") &&
               !callLockoutActive() && !hasHandledCall && !hangupPending) {
      hasHandledCall = true;
      Serial.println("CALL: whitelist rejected reason=MALFORMED");
      queueCallEvent("", false, false, "MALFORMED", -1);
      startCallLockout();
      hangUpCall();
    }
  } else if (incomingClcc) {
    cacheIncomingCaller(extractPhoneCandidate(line), "CLCC");
  }

  const bool incomingIndication = line == "RING" || line.indexOf("+CRING:") >= 0 ||
      line.indexOf("INCOMING") >= 0 ||
      (line.indexOf("VOICE CALL") >= 0 && line.indexOf("END") < 0);
  if (incomingIndication || incomingClcc) {
    firstRingObserved = true;
    detectIncomingCall();
  }

  if (ringPending && firstRingObserved && !hasHandledCall &&
      isValidCallerNumber(temporaryCallerNumber)) {
    handleIncomingCaller(temporaryCallerNumber,
                         cleanClip ? "CLIP" :
                         (clcc ? "CLCC" : "cached caller ID"));
    return;
  }
  if (line.indexOf("NO CARRIER") >= 0 || line.indexOf("BUSY") >= 0 ||
      line.indexOf("NO ANSWER") >= 0 || line.startsWith("+CEND:") ||
      line.startsWith("MISSED_CALL:") ||
      (line.indexOf("VOICE CALL") >= 0 && line.indexOf("END") >= 0)) {
    if (hangupPending) {
      Serial.println("CALL: hang-up confirmed by modem (end URC)");
      onHangupFinished("end_urc");
    } else if (ringPending && !hasHandledCall) {
      // Still ringing with no ID handled — drop once
      hasHandledCall = true;
      startCallLockout();
      hangUpCall();
    } else if (ringPending && hasHandledCall && !callLockoutActive()) {
      // Call already decided but state not cleared — free machine
      resetIncomingCallState();
    }
  }
}

void readModem() {
  readModemBytes();
}

void recoverIncomingCall() {
  if (!ringPending || hasHandledCall || hangupPending || callLockoutActive()) return;
  if (isValidCallerNumber(temporaryCallerNumber)) {
    handleIncomingCaller(temporaryCallerNumber, "cached caller ID");
    return;
  }
  const uint32_t elapsed = millis() - ringStartedAt;
  if (elapsed >= CALL_FAILSAFE_MS) {
    // Mark handled BEFORE hangup so we never spam CHUP every loop (was critical).
    hasHandledCall = true;
    Serial.println("CALL: whitelist rejected reason=HIDDEN_PRIVATE (failsafe)");
    queueCallEvent("", false, false, "HIDDEN_PRIVATE", -1);
    startCallLockout();
    hangUpCall();
    return;
  }
  if (elapsed < CLCC_RECOVERY_MS) {
    if (lastClccQueryAt == 0 || millis() - lastClccQueryAt >= CLCC_QUERY_INTERVAL_MS) {
      clccReplyPending = true;
      lastClccQueryAt = millis();
      Serial.println("CALL: Querying AT+CLCC");
      sendAT("AT+CLCC");
    }
  }
}

// Parse next token: "quoted value" or unquoted until space. Advances `from`.
static String nextToken(const String &s, int &from) {
  while (from < (int)s.length() && s.charAt(from) == ' ') from++;
  if (from >= (int)s.length()) return "";
  if (s.charAt(from) == '"') {
    from++;
    int end = s.indexOf('"', from);
    if (end < 0) end = s.length();
    String out = s.substring(from, end);
    from = end + 1;
    return out;
  }
  int end = from;
  while (end < (int)s.length() && s.charAt(end) != ' ') end++;
  String out = s.substring(from, end);
  from = end;
  return out;
}

// Serial provisioning (secrets never in source):
//   PROVISION <secret> [version]
//   WIFI "ssid with spaces" password
//   APN "name" [user] [pass]   | APN CLEAR
//   DAY | NIGHT | HOLD OFF | PULSE | AUTO
//   SYNC | STATUS | MODEM_CHECK | HELP
void handleSerialCmd() {
  static String buf;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      buf.trim();
      if (buf.startsWith("PROVISION ")) {
        String rest = buf.substring(10);
        rest.trim();
        int sp = rest.indexOf(' ');
        String sec = sp > 0 ? rest.substring(0, sp) : rest;
        uint32_t ver = 1;
        if (sp > 0) ver = (uint32_t)rest.substring(sp + 1).toInt();
        if (ver < 1) ver = 1;
        sec.trim();
        // Hash algorithm for backend must be: SHA256(secret + ":" + DEVICE_ID)
        if (saveSecretToNvs(sec, ver)) {
          Serial.println("OK secret stored in NVS");
          Serial.printf("secretVersion=%lu deviceId=%s\n", (unsigned long)ver, DEVICE_ID);
          Serial.println("Backend secretHash = SHA256(secret + \":\" + deviceId)");
        } else {
          Serial.println("ERR secret too short (min 16 chars)");
        }
      } else if (buf.startsWith("WIFI ")) {
        // WIFI "Jarrods wifi" password   or   WIFI ssid password
        String rest = buf.substring(5);
        rest.trim();
        int pos = 0;
        String ssid = nextToken(rest, pos);
        while (pos < (int)rest.length() && rest.charAt(pos) == ' ') pos++;
        String pass = rest.substring(pos);
        pass.trim();
        // Allow quoted password too
        if (pass.startsWith("\"") && pass.endsWith("\"") && pass.length() >= 2) {
          pass = pass.substring(1, pass.length() - 1);
        }
        if (ssid.length() < 1 || pass.length() < 1) {
          Serial.println("ERR usage: WIFI \"ssid\" password   (quotes required if SSID has spaces)");
        } else if (saveWifiToNvs(ssid, pass)) {
          Serial.printf("OK Wi-Fi stored SSID=\"%s\" (password not printed)\n", ssid.c_str());
          forceWifiReconnect();
        } else {
          Serial.println("ERR Wi-Fi SSID empty");
        }
      } else if (buf.startsWith("APN ") || buf == "APN CLEAR" || buf == "APN") {
        if (buf == "APN CLEAR" || buf == "APN") {
          if (buf == "APN CLEAR") {
            saveApnToNvs("", "", "");
            Serial.println("OK APN cleared (PDP skipped until re-set; reboot to reconfigure modem)");
          } else {
            Serial.println("usage: APN \"name\" [user] [pass]   or   APN CLEAR");
            Serial.printf("current apn=%s user=%s\n",
                          cellularApn.length() ? cellularApn.c_str() : "(none)",
                          cellularUser.length() ? "set" : "(none)");
          }
        } else {
          String rest = buf.substring(4);
          rest.trim();
          int pos = 0;
          String apn = nextToken(rest, pos);
          while (pos < (int)rest.length() && rest.charAt(pos) == ' ') pos++;
          String user = nextToken(rest, pos);
          while (pos < (int)rest.length() && rest.charAt(pos) == ' ') pos++;
          String pass = nextToken(rest, pos);
          if (apn.length() < 1) {
            Serial.println("ERR usage: APN \"live.vodafone.com\" [user] [pass]");
          } else {
            saveApnToNvs(apn, user, pass);
            Serial.printf("OK APN stored name=\"%s\" user=%s (reboot or MODEM reconfigure to apply)\n",
                          apn.c_str(), user.length() ? "set" : "none");
          }
        }
      } else if (buf == "DAY") {
        scheduleOverride = ScheduleOverride::ForceDay;
        updateDayHoldSchedule();
        Serial.println("OK schedule override=DAY (HOLD AP on). Send AUTO for clock schedule.");
      } else if (buf == "NIGHT" || buf == "HOLD OFF") {
        scheduleOverride = ScheduleOverride::ForceNight;
        updateDayHoldSchedule();
        Serial.println("OK schedule override=NIGHT (HOLD off). Send AUTO for clock schedule.");
      } else if (buf == "AUTO") {
        scheduleOverride = ScheduleOverride::Auto;
        updateDayHoldSchedule();
        Serial.println("OK schedule override=AUTO (06:00-18:00 Melbourne hold)");
      } else if (buf == "PULSE") {
        if (dayHoldActive) {
          Serial.println("WARN: day hold active — PP pulse skipped (use NIGHT first to test pulse)");
        }
        startRelayPulse();
        Serial.printf("OK PULSE requested (active=%d hold=%d)\n", relayActive, dayHoldActive);
      } else if (buf == "RECONNECT") {
        Serial.println("WiFi: forced reconnect...");
        forceWifiReconnect();
      } else if (buf == "MODEM_HEX ON") {
        modemHexDiagnostics = true;
        Serial.println("OK modem raw hexadecimal diagnostics ON");
      } else if (buf == "MODEM_HEX OFF") {
        modemHexDiagnostics = false;
        Serial.println("OK modem raw hexadecimal diagnostics OFF");
      } else if (buf == "MODEM_CHECK") {
        Serial.printf("--- MODEM_CHECK start (ESP TX=IO%u RX=IO%u, common GND) ---\n",
                      (unsigned)modemTxPin, (unsigned)modemRxPin);
        const bool previousHexDiagnostics = modemHexDiagnostics;
        modemHexDiagnostics = true;
        sendATWait("AT");
        sendATWait("ATS0?");
        sendATWait("AT+IPREX?");
        sendATWait("AT+CVHU?");
        sendATWait("AT+CPIN?");
        sendATWait("AT+CSQ");
        sendATWait("AT+CREG?");
        sendATWait("AT+CEREG?");
        sendATWait("AT+COPS?");
        sendATWait("AT+CPSI?", 2500);
        modemHexDiagnostics = previousHexDiagnostics;
        Serial.println("--- MODEM_CHECK done ---");
      } else if (buf == "MODEM_RETRY") {
        Serial.println("MODEM: manual bring-up...");
        if (bringUpModem(true)) {
          Serial.println("OK modem ready");
        } else {
          Serial.println("ERR modem still not answering — check cable/GND/TXD-RXD");
        }
        lastModemRetryAt = millis();
      } else if (buf == "SYNC") {
        pullWhitelist();
      } else if (buf == "LED ON") {
        saveStatusLedEnabled(true);
        Serial.println("OK LED status bar ON (saved) — GREEN=open RED=close");
      } else if (buf == "LED OFF") {
        saveStatusLedEnabled(false);
        statusLedsBlank();
        Serial.println("OK LED status bar OFF (saved) — gate logic unchanged");
      } else if (buf == "LED TEST") {
#if STATUS_LED_ENABLE
        Serial.println("LED TEST: green 1s → red 1s → restore");
        statusLedsUserEnabled = true;
        statusLedsFill(0, 255, 0);
        delay(1000);
        statusLedsFill(255, 0, 0);
        delay(1000);
        statusLedsBlank();
        // restore user preference from NVS
        prefs.begin("sgpro", true);
        statusLedsUserEnabled = prefs.getBool("ledOn", true);
        prefs.end();
        Serial.printf("LED TEST done; user=%s\n", statusLedsUserEnabled ? "ON" : "OFF");
#else
        Serial.println("ERR LED compiled out (STATUS_LED_ENABLE=0)");
#endif
      } else if (buf == "HELP") {
        Serial.println(
            "cmds: PROVISION | WIFI | APN | DAY | NIGHT | HOLD OFF | AUTO | PULSE | "
            "LED ON | LED OFF | LED TEST | "
            "SYNC | STATUS | RECONNECT | MODEM_CHECK | MODEM_RETRY | MODEM_HEX ON/OFF | raw AT…");
      } else if (buf == "STATUS") {
        ensureMelbourneTz();
        time_t now = time(nullptr);
        struct tm t;
        localtime_r(&now, &t);
        const char *ov =
            scheduleOverride == ScheduleOverride::ForceDay ? "DAY" :
            scheduleOverride == ScheduleOverride::ForceNight ? "NIGHT" : "AUTO";
        Serial.printf(
            "unit=%s secret=%s secretVer=%lu wifi=%s apn=%s wl_v=%lld n=%u wifi_up=%d "
            "modem=%d pulse=%d hold_ap=%d hold_cut=%u pulse_cut=%u led=%d sched=%s "
            "local=%02d:%02d clock_ok=%d pins hold=IO%u pulse=IO%u uart RX=IO%u TX=IO%u\n",
            GATE_LABEL,
            deviceSecret.length() ? "yes" : "NO",
            (unsigned long)secretVersion,
            wifiSsid.length() ? "yes" : "NO",
            cellularApn.length() ? cellularApn.c_str() : "NO",
            (long long)localWhitelistVersion,
            (unsigned)callerCount,
            WiFi.status() == WL_CONNECTED,
            modemReady,
            relayActive,
            dayHoldActive,
            (unsigned)postHoldCutStrikesLeft,
            (unsigned)postPulseCutStrikesLeft,
            statusLedsUserEnabled,
            ov,
            t.tm_hour, t.tm_min,
            clockLooksValid(),
            (unsigned)RELAY_HOLD_PIN,
            (unsigned)RELAY_PULSE_PIN,
            (unsigned)modemRxPin,
            (unsigned)modemTxPin);
      } else if (buf.length()) {
        if (buf.startsWith("AT") && buf.indexOf('\n') < 0 &&
            buf.indexOf('\r') < 0) {
          sendATWait(buf.c_str());
        } else {
          Serial.println("ERR unknown command (HELP for list)");
        }
      }
      buf = "";
    } else buf += c;
  }
}

void setup() {
  // Relays OFF before any other work (no false open on boot).
  relaysInit();
  statusLedsInit();
  Serial.begin(USB_BAUD);
  delay(1200);
  modemLine.reserve(1024);
  modem.setRxBufferSize(4096);
  modemRxPin = MODEM_RX_PIN;
  modemTxPin = MODEM_TX_PIN;
  modem.begin(MODEM_BAUD_DEFAULT, SERIAL_8N1, modemRxPin, modemTxPin);
  activeModemBaud = MODEM_BAUD_DEFAULT;

  Serial.println();
  Serial.println("RJL SG-PRO production - LKG whitelist - SMS job queue");
  Serial.printf("UNIT: %s  DEVICE_ID=%s\n", GATE_LABEL, DEVICE_ID);
  Serial.println("SCHED: 06:00-18:00 HOLD(AP) constant | 18:00-06:00 PULSE(PP) 3s on call");
  Serial.printf("RELAY: HOLD=IO%u→Roger AP  PULSE=IO%u→Roger PP  (both OFF at boot)\n",
                (unsigned)RELAY_HOLD_PIN, (unsigned)RELAY_PULSE_PIN);
  Serial.printf("MODEM pins: ESP RX=IO%u TX=IO%u (modem TXD->RX, modem RXD<-TX)\n",
                (unsigned)modemRxPin, (unsigned)modemTxPin);
  Serial.printf("LED: status DIN=IO%u  GREEN=open/opening  RED=close/closing\n",
                (unsigned)STATUS_LED_PIN);
  loadCredentialsFromNvs();
  loadWhitelistFromNvs(); // last-known-good survives power loss

    // SIM7600 DTU often needs several seconds after 12V before UART answers.
  Serial.println("MODEM: waiting 8s for DTU UART ready...");
  delay(8000);
  if (bringUpModem(true)) {
    Serial.printf("MODEM: detected at %lu baud\n",
                  static_cast<unsigned long>(activeModemBaud));
    Serial.println("Run MODEM_CHECK for CPIN/CSQ/CREG/CEREG/COPS/CPSI");
  } else {
    Serial.println("WARN: modem not ready (normal if cable not fitted yet)");
    Serial.println("WARN: will retry every 60s, or type MODEM_RETRY after wiring");
  }
  lastModemRetryAt = millis();

  ensureWifi();
  // NTP then force Melbourne TZ (configTime alone leaves UTC on many ESP32 cores)
  if (WiFi.status() == WL_CONNECTED) {
    syncNtpClock();
    for (int i = 0; i < 30 && !clockLooksValid(); ++i) delay(200);
    ensureMelbourneTz();
  } else {
    ensureMelbourneTz();
  }
  updateDayHoldSchedule();

  if (deviceSecret.length() >= 16 && wifiSsid.length() > 0) {
    pullWhitelist();
  } else {
    if (deviceSecret.length() < 16) {
      Serial.println("WARN: Serial: PROVISION <long-random-device-secret> [version]");
    }
    if (wifiSsid.length() < 1) {
      Serial.println("WARN: Serial: WIFI \"ssid\" password");
    }
  }
  if (cellularApn.length() < 1) {
    Serial.println("INFO: no cellular APN — Wi‑Fi for API is enough; optional: APN \"name\" [user] [pass]");
  }
  lastWhitelistPull = millis();
  lastHeartbeat = millis();
  lastScheduleTick = millis();
  Serial.println("READY — day=HOLD(AP) | night=PULSE(PP) 3s | LKG whitelist | HELP");
}

void loop() {
  readModem();
  serviceSmsModem();
  updateRelay();
  serviceStatusLeds();
  updateCallLockout();
  recoverIncomingCall();
  serviceHangup();
  const bool callCritical = ringPending || hangupPending || relayActive;
  // No network operation may block caller lookup or hang-up retries.
  if (!callCritical) processPendingCallEvent();
  if (!callCritical) serviceSmsAcknowledgement();
  if (!callCritical) handleSerialCmd();

  if (millis() - lastScheduleTick >= SCHEDULE_TICK_MS) {
    lastScheduleTick = millis();
    updateDayHoldSchedule();
  }

  if (!callCritical && millis() - lastWhitelistPull >= WHITELIST_POLL_MS) {
    lastWhitelistPull = millis();
    if (deviceSecret.length() >= 16) pullWhitelist();
  }
  if (!callCritical && millis() - lastHeartbeat >= HEARTBEAT_MS) {
    lastHeartbeat = millis();
    if (deviceSecret.length() >= 16) sendHeartbeat();
  }
  // Cable fitted after boot (site install): re-probe periodically until OK.
  if (!modemReady && !callCritical &&
      millis() - lastModemRetryAt >= MODEM_RETRY_MS) {
    lastModemRetryAt = millis();
    Serial.println("MODEM: periodic retry (plug cable / check GND TXD RXD)...");
    if (bringUpModem(true)) {
      Serial.println("MODEM: now ready after retry");
    }
  }
  delay(2);
}


