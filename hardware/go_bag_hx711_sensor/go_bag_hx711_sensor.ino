#include "HX711.h"
#include <Preferences.h>

const uint8_t NUM_SENSORS = 5;
const uint8_t SCK_PIN = 33;
const uint8_t DOUT_PINS[NUM_SENSORS] = {32, 25, 26, 27, 14};

HX711 scales[NUM_SENSORS];
Preferences tareStore;

long tareRaw[NUM_SENSORS] = {0, 0, 0, 0, 0};
bool hasTare[NUM_SENSORS] = {false, false, false, false, false};

// Signed counts per gram.
// If a sensor raw value goes DOWN when loaded, keep this value negative.
float COUNTS_PER_GRAM[NUM_SENSORS] = {
  161.5, 161.5, 161.5, 161.5, 161.5
};

float DETECT_THRESHOLD_G[NUM_SENSORS] = {
  100.0, 100.0, 100.0, 100.0, 100.0
};

const int TARE_SAMPLES = 20;
const int READ_SAMPLES = 10;
const unsigned long SAMPLE_READY_TIMEOUT_MS = 250;
const unsigned long PRINT_INTERVAL_MS = 1000;

unsigned long lastPrint = 0;

bool sensorAvailable(uint8_t i) {
  return scales[i].wait_ready_timeout(500);
}

bool readAverageWithTimeout(uint8_t i, int samples, long &averageRaw) {
  int64_t totalRaw = 0;

  for (int sample = 0; sample < samples; sample++) {
    if (!scales[i].wait_ready_timeout(SAMPLE_READY_TIMEOUT_MS)) {
      return false;
    }
    totalRaw += scales[i].read();
  }

  averageRaw = (long)(totalRaw / samples);
  return true;
}

String tareKey(uint8_t i) {
  return "tare" + String(i);
}

String hasTareKey(uint8_t i) {
  return "has" + String(i);
}

void saveTare(uint8_t i) {
  tareStore.putLong(tareKey(i).c_str(), tareRaw[i]);
  tareStore.putBool(hasTareKey(i).c_str(), hasTare[i]);
}

void loadTares() {
  for (uint8_t i = 0; i < NUM_SENSORS; i++) {
    hasTare[i] = tareStore.getBool(hasTareKey(i).c_str(), false);
    tareRaw[i] = tareStore.getLong(tareKey(i).c_str(), 0);
    if (hasTare[i]) {
      Serial.print("Loaded stored tare for sensor ");
      Serial.print(i + 1);
      Serial.print(": ");
      Serial.println(tareRaw[i]);
    } else {
      Serial.print("No saved tare for sensor ");
      Serial.print(i + 1);
      Serial.println(". Empty that section and tare before use.");
    }
  }
}

void clearStoredTares() {
  tareStore.clear();
  for (uint8_t i = 0; i < NUM_SENSORS; i++) {
    tareRaw[i] = 0;
    hasTare[i] = false;
  }
  Serial.println("Stored tares cleared. Empty each section and send 't' to tare again.");
}

void printHelp() {
  Serial.println("Commands:");
  Serial.println("t = tare all connected sensors and save baselines");
  Serial.println("1 = tare sensor 1 and save baseline");
  Serial.println("2 = tare sensor 2 and save baseline");
  Serial.println("3 = tare sensor 3 and save baseline");
  Serial.println("4 = tare sensor 4 and save baseline");
  Serial.println("5 = tare sensor 5 and save baseline");
  Serial.println("x = clear saved tare baselines");
  Serial.println("h = show help");
  Serial.println();
}

void tareSensor(uint8_t i) {
  if (!sensorAvailable(i)) {
    Serial.print("Sensor ");
    Serial.print(i + 1);
    Serial.println(" not ready for tare.");
    return;
  }

  long averageRaw = 0;
  if (!readAverageWithTimeout(i, TARE_SAMPLES, averageRaw)) {
    Serial.print("Sensor ");
    Serial.print(i + 1);
    Serial.println(" not ready for tare.");
    return;
  }

  tareRaw[i] = averageRaw;
  hasTare[i] = true;
  saveTare(i);

  Serial.print("Sensor ");
  Serial.print(i + 1);
  Serial.print(" tared and saved. Baseline raw = ");
  Serial.println(tareRaw[i]);
}

void tareAllSensors() {
  Serial.println();
  Serial.println("Taring all connected sensors...");
  Serial.println("Make sure only the empty platform is on each section.");
  for (uint8_t i = 0; i < NUM_SENSORS; i++) {
    if (sensorAvailable(i)) {
      tareSensor(i);
    } else {
      Serial.print("Sensor ");
      Serial.print(i + 1);
      Serial.println(" not connected.");
    }
  }
  Serial.println("Tare complete.");
  Serial.println();
}

void handleSerialCommands() {
  while (Serial.available()) {
    char c = Serial.read();

    if (c == 't' || c == 'T') {
      tareAllSensors();
    } else if (c >= '1' && c <= '5') {
      uint8_t idx = c - '1';
      Serial.println();
      tareSensor(idx);
      Serial.println();
    } else if (c == 'x' || c == 'X') {
      clearStoredTares();
    } else if (c == 'h' || c == 'H') {
      printHelp();
    }
  }
}

float computeWeightG(uint8_t i, long raw) {
  if (!hasTare[i]) return 0.0f;

  long netRaw = raw - tareRaw[i];
  float countsPerGram = COUNTS_PER_GRAM[i];

  if (countsPerGram == 0.0f) return 0.0f;

  float weightG = ((float) netRaw) / countsPerGram;

  if (weightG < DETECT_THRESHOLD_G[i]) weightG = 0.0f;
  if (weightG < 0.0f) weightG = 0.0f;

  return weightG;
}

void publishJsonFrame() {
  float weightsG[NUM_SENSORS] = {0, 0, 0, 0, 0};
  uint8_t connectedSections = 0;
  uint8_t occupiedSections = 0;
  bool ready = true;

  for (uint8_t i = 0; i < NUM_SENSORS; i++) {
    if (!sensorAvailable(i)) {
      Serial.print("Sensor ");
      Serial.print(i + 1);
      Serial.println(" not connected.");
      ready = false;
      continue;
    }

    connectedSections++;

    if (!hasTare[i]) {
      Serial.print("Sensor ");
      Serial.print(i + 1);
      Serial.println(" needs tare. Empty that section and send its number or 't'.");
      ready = false;
      continue;
    }

    long raw = 0;
    if (!readAverageWithTimeout(i, READ_SAMPLES, raw)) {
      Serial.print("Sensor ");
      Serial.print(i + 1);
      Serial.println(" not ready for reading.");
      ready = false;
      continue;
    }

    float weightG = computeWeightG(i, raw);

    weightsG[i] = weightG;
    if (weightG > 0.0f) occupiedSections++;
  }

  if (!ready || connectedSections != NUM_SENSORS) {
    return;
  }

  Serial.print("{\"source\":\"esp32_hx711\",\"unit\":\"g\",\"weights\":[");
  for (uint8_t i = 0; i < NUM_SENSORS; i++) {
    if (i > 0) Serial.print(",");
    Serial.print(weightsG[i], 1);
  }
  Serial.print("],\"occupied_sections\":");
  Serial.print(occupiedSections);
  Serial.print(",\"connected_sections\":");
  Serial.print(connectedSections);
  Serial.println("}");
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("=== GO BAG SENSOR JSON MODE ===");
  Serial.println("Stored tare baselines are reused after restart.");
  Serial.println("Only send 't' when each section is empty except for its platform.");
  Serial.println();

  tareStore.begin("gobag-tare", false);

  for (uint8_t i = 0; i < NUM_SENSORS; i++) {
    scales[i].begin(DOUT_PINS[i], SCK_PIN);
  }

  loadTares();
  printHelp();
}

void loop() {
  handleSerialCommands();

  if (millis() - lastPrint < PRINT_INTERVAL_MS) return;
  lastPrint = millis();

  publishJsonFrame();
}
