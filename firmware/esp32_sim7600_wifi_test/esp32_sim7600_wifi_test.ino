#include <Arduino.h>
#include <WiFi.h>

#include "wifi_secrets.h"

namespace config {

constexpr char WIFI_SSID[] = "sim7600";
constexpr uint32_t CONNECTION_TIMEOUT_MS = 30000;
constexpr uint32_t RECONNECT_INTERVAL_MS = 10000;

}  // namespace config

uint32_t lastReconnectAttemptMs = 0;

const char* wifiStatusText(wl_status_t status)
{
    switch (status) {
        case WL_IDLE_STATUS:
            return "idle";
        case WL_NO_SSID_AVAIL:
            return "network not found";
        case WL_SCAN_COMPLETED:
            return "scan completed";
        case WL_CONNECTED:
            return "connected";
        case WL_CONNECT_FAILED:
            return "connection failed";
        case WL_CONNECTION_LOST:
            return "connection lost";
        case WL_DISCONNECTED:
            return "disconnected";
        default:
            return "unknown";
    }
}

void printNetworkDetails()
{
    Serial.println();
    Serial.println("WiFi connected through SIM7600 Windows hotspot");
    Serial.printf("SSID: %s\n", WiFi.SSID().c_str());
    Serial.printf("IP address: %s\n", WiFi.localIP().toString().c_str());
    Serial.printf("Gateway: %s\n", WiFi.gatewayIP().toString().c_str());
    Serial.printf("DNS: %s\n", WiFi.dnsIP().toString().c_str());
    Serial.printf("Signal: %d dBm\n", WiFi.RSSI());
    Serial.println();
}

bool connectToSim7600Hotspot()
{
    Serial.printf("Connecting to WiFi \"%s\"", config::WIFI_SSID);

    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(config::WIFI_SSID, secrets::WIFI_PASSWORD);

    const uint32_t startedMs = millis();

    while (WiFi.status() != WL_CONNECTED) {
        if (millis() - startedMs >= config::CONNECTION_TIMEOUT_MS) {
            Serial.println();
            Serial.printf(
                "WiFi connection timed out: %s\n",
                wifiStatusText(WiFi.status())
            );
            return false;
        }

        Serial.print('.');
        delay(500);
    }

    Serial.println();
    printNetworkDetails();
    return true;
}

void maintainWiFiConnection()
{
    if (WiFi.status() == WL_CONNECTED) {
        return;
    }

    const uint32_t nowMs = millis();

    if (nowMs - lastReconnectAttemptMs < config::RECONNECT_INTERVAL_MS) {
        return;
    }

    lastReconnectAttemptMs = nowMs;

    Serial.printf(
        "WiFi disconnected: %s. Reconnecting...\n",
        wifiStatusText(WiFi.status())
    );

    if (!WiFi.reconnect()) {
        WiFi.begin(config::WIFI_SSID, secrets::WIFI_PASSWORD);
    }
}

void setup()
{
    Serial.begin(115200);
    delay(1000);

    connectToSim7600Hotspot();
}

void loop()
{
    maintainWiFiConnection();
    delay(100);
}
