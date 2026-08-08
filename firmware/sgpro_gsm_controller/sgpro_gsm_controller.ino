/**
 * RJL Multicom SG-PRO Ã¢â‚¬â€ Production controller
 * ESP32-S3 + Waveshare SIM7600G-H 4G DTU
 *
 * FINAL BEHAVIOUR
 * Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
 * Portal add/delete Ã¢â€ â€™ Firebase whitelistVersion++
 * ESP32 polls ~60s Ã¢â€ â€™ validate temp Ã¢â€ â€™ atomic activate local list
 * Incoming call Ã¢â€ â€™ local list only Ã¢â€ â€™ AT+CHUP Ã¢â€ â€™ 3s relay if authorised
 * Welcome SMS Ã¢â€ â€™ gsmSmsQueue jobs PENDINGÃ¢â€ â€™SENDINGÃ¢â€ â€™SENT|FAILED, ack by jobId
 *
 * Secrets: provision once via Serial "PROVISION <secret>" Ã¢â€ â€™ Preferences NVS
 * Never commit live DEVICE_SECRET to git.
 *
 * Libraries: ArduinoJson, Preferences, WiFi, HTTPClient, mbedTLS (built-in ESP32)
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

// Ã¢â€â‚¬Ã¢â€â‚¬ Pins (ESP32-S3 Screw Terminal Board) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
// Silkscreen usually says IO17 / IO18 / IO5 (or G17 / G18 / G5) Ã¢â‚¬â€ not "GPIO18".
//
//   SIM7600G-H DTU TXD  Ã¢â€ â€™  board IO18  (ESP32 RX  = MODEM_RX_PIN)
//   SIM7600G-H DTU RXD  Ã¢â€ â€™  board IO17  (ESP32 TX  = MODEM_TX_PIN)
//   SIM7600G-H DTU GND  Ã¢â€ â€™  board GND
//   Relay IN            Ã¢â€ â€™  board IO5
//   Relay GND           Ã¢â€ â€™  board GND
//
// Power DTU from 12V (7Ã¢â‚¬â€œ36V), NOT from the ESP32 3V3/5V.
// If your terminal strip has no IO17/IO18, pick two free UARTable IOs
// (e.g. IO15/IO16) and change the three numbers below to match the labels.
constexpr uint8_t MODEM_RX_PIN = 18;  // ESP32 receives from modem TXD
constexpr uint8_t MODEM_TX_PIN = 17;  // ESP32 sends to modem RXD
constexpr uint8_t RELAY_PIN = 5;
constexpr bool RELAY_ACTIVE_LOW = false;

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

// Ã¢â€â‚¬Ã¢â€â‚¬ Non-secret firmware config only (safe in source control) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
// Deployed on project iiii-7b9e8 (Blaze) Ã¢â‚¬â€ Cloud Run URL for gen2 function.
// Classic: https://australia-southeast1-iiii-7b9e8.cloudfunctions.net/gsmDeviceApi
// See docs/ALTERNATE_PROJECT_DEPLOY.md
const char *API_BASE =
    "https://gsmdeviceapi-2vin7lgmjq-ts.a.run.app";
const char *DEVICE_ID = "device_commercial_bc_01";

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
// Wi-Fi SSID/password and DEVICE_SECRET are NEVER committed Ã¢â‚¬â€ provision via Serial.

// Ã¢â€â‚¬Ã¢â€â‚¬ Runtime Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
HardwareSerial modem(1);
Preferences prefs;
String modemLine;

bool relayActive = false;
uint32_t relayOffAt = 0;
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

// Ã¢â€â‚¬Ã¢â€â‚¬ Relay Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
// GPIO 5 is authoritative ACTIVE HIGH: HIGH energises, LOW is always safe/off.
// Keep all physical relay writes here (except setup's mandatory LOW writes).
void setRelay(bool on) {
  digitalWrite(RELAY_PIN, on ? HIGH : LOW);
}
void startRelayPulse() {
  if (relayActive) return; // one pulse per call window
  relayActive = true;
  relayOffAt = millis() + RELAY_PULSE_MS;
  setRelay(true);
  Serial.println("RELAY: ON GPIO5=HIGH pulse=3000ms");
}
void updateRelay() {
  if (relayActive && (int32_t)(millis() - relayOffAt) >= 0) {
    relayActive = false;
    setRelay(false);
    Serial.println("RELAY: OFF GPIO5=LOW");
  }
}

// Ã¢â€â‚¬Ã¢â€â‚¬ NVS secrets + whitelist Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
void loadCredentialsFromNvs() {
  prefs.begin("sgpro", true);
  deviceSecret = prefs.getString("secret", "");
  secretVersion = prefs.getUInt("secretVer", 1);
  wifiSsid = prefs.getString("wifiSsid", "");
  wifiPass = prefs.getString("wifiPass", "");
  localWhitelistVersion = prefs.getLong64("wl_ver", -1);
  prefs.end();
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
    ok = prefs.putString((k + "p").c_str(), entries[i].e164) > 0;
    prefs.putString((k + "i").c_str(), entries[i].id);
    prefs.putString((k + "n").c_str(), entries[i].name);
    prefs.putBool((k + "e").c_str(), entries[i].enabled);
    prefs.putLong64((k + "u").c_str(), entries[i].validUntilEpoch);
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

// Ã¢â€â‚¬Ã¢â€â‚¬ Modem Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
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

bool sendATWait(const char *cmd, uint32_t waitMs = 3000) {
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
  setRelay(false);
  modem.end();
  delay(20);
  modemLine = "";
  modemLineCorrupted = false;
  modem.begin(baud, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
  activeModemBaud = baud;
  Serial.printf("MODEM: probing %lu baud\n", static_cast<unsigned long>(baud));
}

bool detectModemBaud() {
  static const uint32_t baudRates[] = {115200, 9600, 57600, 38400, 19200};
  for (uint8_t round = 0; round < 3; ++round) {
    for (uint32_t baud : baudRates) {
      beginModemUart(baud);
      if (sendATWait("AT", 1200)) {
        Serial.printf("MODEM: clean OK detected at %lu baud\n",
                      static_cast<unsigned long>(baud));
        return true;
      }
    }
  }
  Serial.println("MODEM: baud detection failed; no clean ASCII OK");
  return false;
}

bool configureModem() {
  static const char *const commands[] = {
      "ATE0",
      "AT+CMEE=2",
      "AT+CPIN?",
      "AT+CFUN=1",
      "AT+CLIP=1",
      "AT+CSQ",
      "AT+CREG?",
      "AT+CEREG?",
      "AT+CGDCONT=1,\"IP\",\"live.vodafone.com\"",
      "AT+CGAUTH=1,1,\"wa9acpnyjrbg\",\"wa9acpnyjrbg\"",
      "AT+CGATT=1",
      "AT+CGACT=1,1",
      "AT+CGPADDR=1",
      "AT+NETOPEN",
  };
  bool allOk = true;
  for (const char *command : commands) {
    const uint32_t timeoutMs =
        (!strcmp(command, "AT+CGATT=1") || !strcmp(command, "AT+CGACT=1,1") ||
         !strcmp(command, "AT+NETOPEN"))
            ? 30000
            : 5000;
    if (!sendATWait(command, timeoutMs)) allOk = false;
  }
  // Preserve the production SMS feature after the required data sequence.
  if (!sendATWait("AT+CMGF=1", 5000)) allOk = false;
  if (!sendATWait("AT+CSCS=\"GSM\"", 5000)) allOk = false;
  // GNSS may already be enabled; failure here must not disable gate/call use.
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

// Ã¢â€â‚¬Ã¢â€â‚¬ Phone Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
String normalizePhoneNumber(const String &raw) {
  String cleaned;
  for (size_t i = 0; i < raw.length(); i++) {
    char c = raw.charAt(i);
    if (isDigit((unsigned char)c)) cleaned += c;
    else if (c == '+' && cleaned.length() == 0) cleaned += c;
  }
  if (cleaned.isEmpty()) return "";
  if (cleaned.startsWith("+")) return cleaned;
  if (cleaned.startsWith("00")) return "+" + cleaned.substring(2);
  if (cleaned.startsWith("0")) return "+61" + cleaned.substring(1);
  if (cleaned.startsWith("61")) return "+" + cleaned;
  if (cleaned.startsWith("4") && cleaned.length() == 9) return "+61" + cleaned;
  return cleaned;
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
      hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 60)
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

// Ã¢â€â‚¬Ã¢â€â‚¬ SMS Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
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

// Ã¢â€â‚¬Ã¢â€â‚¬ Auth + HTTP Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
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
    Serial.println("HTTP: no device secret in NVS Ã¢â‚¬â€ run PROVISION");
    return false;
  }
  if (WiFi.status() != WL_CONNECTED) return false;

  time_t now = time(nullptr);
  if (now < 1700000000) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    const uint32_t timeDeadline = millis() + 10000UL;
    do {
      delay(100);
      now = time(nullptr);
    } while (now < 1700000000 && (int32_t)(millis() - timeDeadline) < 0);
    if (now < 1700000000) {
      Serial.println("HTTP: clock not synchronized; request not signed");
      return false;
    }
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
  Serial.printf("HTTP %s %s Ã¢â€ â€™ %d\n", method, path.c_str(), code);
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
    Serial.println("WiFi: not provisioned Ã¢â‚¬â€ Serial: WIFI \"ssid\" password");
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
    Serial.println("WiFi: begin rejected Ã¢â‚¬â€ will retry later");
    wifiBusy = false;
    return;
  }

  for (int i = 0; i < 80; i++) { // ~20s
    wl_status_t st = WiFi.status();
    if (st == WL_CONNECTED) {
      Serial.printf("WiFi: CONNECTED  ip=%s  rssi=%d dBm\n",
                    WiFi.localIP().toString().c_str(), WiFi.RSSI());
      configTime(0, 0, "pool.ntp.org", "time.google.com");
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

/** Force reconnect now (Serial: RECONNECT) Ã¢â‚¬â€ ignores cooldown. */
void forceWifiReconnect() {
  wifiLastAttemptMs = 0;
  wifiBusy = false;
  if (WiFi.status() == WL_CONNECTED) {
    WiFi.disconnect(true, false);
    delay(200);
  }
  ensureWifi();
}

// Ã¢â€â‚¬Ã¢â€â‚¬ Whitelist pull: last-known-good until fully validated Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
// Do NOT replace working list until: authenticated (HTTP ok) + full download
// + parse success + validated + save success. Failed/empty body keeps LKG.
bool pullWhitelist() {
  ensureWifi();
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Whitelist: offline Ã¢â‚¬â€ keeping last-known-good");
    return false;
  }

  String path = String("/gsm/device/") + DEVICE_ID + "/whitelist";
  String resp;
  if (!httpSigned("GET", path, "", resp)) {
    Serial.println("Whitelist: download failed Ã¢â‚¬â€ keeping last-known-good");
    return false;
  }
  if (resp.length() < 8) {
    Serial.println("Whitelist: empty response Ã¢â‚¬â€ keeping last-known-good");
    return false;
  }

  JsonDocument doc;
  if (deserializeJson(doc, resp)) {
    Serial.println("Whitelist: parse failed Ã¢â‚¬â€ keeping last-known-good");
    return false;
  }
  if (!doc["version"].is<int64_t>() || !doc["callers"].is<JsonArray>()) {
    Serial.println("Whitelist: invalid shape Ã¢â‚¬â€ keeping last-known-good");
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
    Serial.println("Whitelist: validation failed Ã¢â‚¬â€ keeping last-known-good");
    return false;
  }

  // Persist and verify the inactive NVS slot before changing the active list.
  int64_t newVer = doc["version"] | 0;
  if (!persistWhitelistAtomic(temp, n, newVer)) return false;
  for (size_t i = 0; i < n; i++) callers[i] = temp[i];
  callerCount = n;
  localWhitelistVersion = newVer;
  localWhitelistChecksum = actualChecksum;

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
      JsonDocument ackDoc;
      ackDoc["commandId"] = commandId;
      ackDoc["triggered"] = relayActive;
      if (!relayActive) ackDoc["error"] = "relay_busy";
      String ackBody;
      serializeJson(ackDoc, ackBody);
      String ackResponse;
      httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/command-ack",
                 ackBody, ackResponse);
      Serial.printf("Remote gate test %s: %s\n", commandId,
                    relayActive ? "triggered" : "failed");
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
  PendingCallEvent event = pendingCallEvent;
  pendingCallEvent.pending = false;
  ensureWifi();
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("EVENT: offline; call event not uploaded");
    return;
  }
  JsonDocument doc;
  doc["callerNumberE164"] = event.callerNumberE164;
  doc["authorised"] = event.authorised;
  doc["relayTriggered"] = event.relayTriggered;
  doc["rejectionReason"] = event.rejectionReason;
  if (event.matchedCallerId.length()) {
    doc["matchedCallerId"] = event.matchedCallerId;
    doc["matchedCallerName"] = event.matchedCallerName;
  }
  String body;
  serializeJson(doc, body);
  String response;
  if (!httpSigned("POST", String("/gsm/device/") + DEVICE_ID + "/events", body, response)) {
    Serial.println("EVENT: upload failed");
  }
}

// Ã¢â€â‚¬Ã¢â€â‚¬ Call path (local only Ã¢â‚¬â€ never waits for cloud) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
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
    hangupPending = false;
    setRelay(false);
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

  const int idx = findCallerIndex(e164);
  if (idx < 0) {
    const char *reason = callerRejectionReason(e164);
    Serial.printf("CALL: whitelist rejected %s reason=%s\n", e164.c_str(), reason);
    relayQueuedAfterHangup = false;
    queueCallEvent(e164, false, false, reason, -1);
    // Lockout clears ringPending/hasHandledCall when it ends. Without this,
    // rejected calls leave hasHandledCall stuck true and block all later opens.
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
    hangupPending = false;
  }

  if (callLockoutActive() &&
      (line == "RING" || line.startsWith("+CLIP:") ||
       line.indexOf("+CRING:") >= 0)) {
    return; // Ignore every incoming-call URC throughout the accepted-call lockout.
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
      if (!callLockoutActive() && !hasHandledCall) {
        hasHandledCall = true;
        Serial.println("CALL: whitelist rejected reason=HIDDEN_PRIVATE");
        queueCallEvent("", false, false, "HIDDEN_PRIVATE", -1);
        startCallLockout();
        hangUpCall();
      }
    } else if (!cacheIncomingCaller(clipNumber, "CLIP") &&
               !callLockoutActive() && !hasHandledCall) {
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
      hangupPending = false;
      Serial.println("CALL: hang-up confirmed by modem");
      relayQueuedAfterHangup = false;
    } else if (ringPending) {
      hangUpCall();
    }
  }
}

void readModem() {
  readModemBytes();
}

void recoverIncomingCall() {
  if (!ringPending || hasHandledCall || hangupPending) return;
  if (isValidCallerNumber(temporaryCallerNumber)) {
    handleIncomingCaller(temporaryCallerNumber, "cached caller ID");
    return;
  }
  const uint32_t elapsed = millis() - ringStartedAt;
  if (elapsed >= CALL_FAILSAFE_MS) {
    Serial.println("CALL: whitelist rejected reason=HIDDEN_PRIVATE");
    queueCallEvent("", false, false, "HIDDEN_PRIVATE", -1);
    // Mark handled + lockout so failsafe does not re-CHUP forever and so the
    // next real caller is accepted after CALL_LOCKOUT_MS.
    hasHandledCall = true;
    startCallLockout();
    hangUpCall();
    return;
  }
  if (elapsed >= CLCC_RECOVERY_MS) return;
  if (lastClccQueryAt == 0 || millis() - lastClccQueryAt >= CLCC_QUERY_INTERVAL_MS) {
    clccReplyPending = true;
    lastClccQueryAt = millis();
    Serial.println("CALL: Querying AT+CLCC");
    sendAT("AT+CLCC");
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
//   WIFI ssid password
//   SYNC | STATUS | MODEM_CHECK
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
        Serial.println("--- MODEM_CHECK start (ESP32 GPIO17=TXÃ¢â€ â€™modem RX, GPIO18=RXÃ¢â€ Âmodem TX, common GND) ---");
        const bool previousHexDiagnostics = modemHexDiagnostics;
        modemHexDiagnostics = true;
        sendATWait("AT");
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
      } else if (buf == "SYNC") {
        pullWhitelist();
      } else if (buf == "STATUS") {
        Serial.printf(
            "secret=%s secretVer=%lu wifi=%s wl_v=%lld n=%u wifi_up=%d relay=%d\n",
            deviceSecret.length() ? "yes" : "NO",
            (unsigned long)secretVersion,
            wifiSsid.length() ? "yes" : "NO",
            (long long)localWhitelistVersion,
            (unsigned)callerCount,
            WiFi.status() == WL_CONNECTED,
            relayActive);
      } else if (buf.length()) {
        if (buf.startsWith("AT") && buf.indexOf('\n') < 0 &&
            buf.indexOf('\r') < 0) {
          sendATWait(buf.c_str());
        } else {
          Serial.println("ERR unknown command");
        }
      }
      buf = "";
    } else buf += c;
  }
}

void setup() {
  // This must remain the first executable setup code. Pre-load the output latch
  // LOW before changing direction to prevent even a transient active-high pulse.
  digitalWrite(RELAY_PIN, LOW);
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, LOW);
  Serial.begin(USB_BAUD);
  delay(1200);
  modemLine.reserve(1024);
  modem.setRxBufferSize(4096);
  modem.begin(MODEM_BAUD_DEFAULT, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
  activeModemBaud = MODEM_BAUD_DEFAULT;

  // Relay must stay OFF through boot / reset
  setRelay(false);

  Serial.println();
  Serial.println("RJL SG-PRO production - LKG whitelist - SMS job queue");
  Serial.println("RELAY: OFF GPIO5=LOW (ACTIVE HIGH)");
  loadCredentialsFromNvs();
  loadWhitelistFromNvs(); // last-known-good survives power loss

  delay(2000);
  if (detectModemBaud()) {
    Serial.printf("MODEM: detected at %lu baud\n",
                  static_cast<unsigned long>(activeModemBaud));
    const bool configured = configureModem();
    Serial.printf("MODEM: initialization %s\n",
                  configured ? "complete" : "completed with failures");
    Serial.println("Run MODEM_CHECK for CPIN/CSQ/CREG/CEREG/COPS/CPSI");
  } else {
    Serial.println("WARN: modem not ready");
  }

  ensureWifi();
  if (deviceSecret.length() >= 16 && wifiSsid.length() > 0) {
    pullWhitelist();
  } else {
    if (deviceSecret.length() < 16) {
      Serial.println("WARN: Serial: PROVISION <long-random-device-secret> [version]");
    }
    if (wifiSsid.length() < 1) {
      Serial.println("WARN: Serial: WIFI <ssid> <password>");
    }
  }
  lastWhitelistPull = millis();
  lastHeartbeat = millis();
  Serial.println("READY Ã¢â‚¬â€ calls use local LKG cache only (no cloud wait)");
}

void loop() {
  readModem();
  serviceSmsModem();
  updateRelay();
  updateCallLockout();
  recoverIncomingCall();
  serviceHangup();
  const bool callCritical = ringPending || hangupPending || relayActive;
  // No network operation may block caller lookup or hang-up retries.
  if (!callCritical) processPendingCallEvent();
  if (!callCritical) serviceSmsAcknowledgement();
  if (!callCritical) handleSerialCmd();

  if (!callCritical && millis() - lastWhitelistPull >= WHITELIST_POLL_MS) {
    lastWhitelistPull = millis();
    if (deviceSecret.length() >= 16) pullWhitelist();
  }
  if (!callCritical && millis() - lastHeartbeat >= HEARTBEAT_MS) {
    lastHeartbeat = millis();
    if (deviceSecret.length() >= 16) sendHeartbeat();
  }
  delay(2);
}


