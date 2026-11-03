"""
Unit and integration tests for WinRT / Python Health Telemetry Bridge.
Verifies parsing, JSON schema validation, thread safety, and telemetry delivery across all metrics.
"""

import json
import threading
import time
import unittest

from winrt_health_bridge import (
    HealthMetrics,
    HealthTelemetryParser,
    HealthTelemetryPayload,
    SleepData,
    SleepStages,
    WinRTHealthBridge,
)


class TestHealthTelemetryBridge(unittest.TestCase):

    def test_schema_matches_exact_specification(self):
        """Verify generated JSON adheres to the target schema from requirements."""
        target_example = {
            "timestamp": 1730550000,
            "metrics": {
                "heart_rate": 72,
                "blood_oxygen": 98,
                "stress_level": 25,
                "steps": 4520,
                "calories_burnt": 310,
                "sleep_data": {"status": "light", "duration_minutes": 360},
            },
        }

        # Parse the reference example
        payload = HealthTelemetryPayload.from_json(json.dumps(target_example))

        self.assertEqual(payload.timestamp, 1730550000)
        self.assertEqual(payload.metrics.heart_rate, 72)
        self.assertEqual(payload.metrics.blood_oxygen, 98)
        self.assertEqual(payload.metrics.stress_level, 25)
        self.assertEqual(payload.metrics.steps, 4520)
        self.assertEqual(payload.metrics.calories_burnt, 310)
        self.assertEqual(payload.metrics.sleep_data.status, "light")
        self.assertEqual(payload.metrics.sleep_data.duration_minutes, 360)

        # Re-serialize to JSON and ensure all keys are present
        serialized_json = payload.to_json()
        doc = json.loads(serialized_json)

        self.assertEqual(doc["timestamp"], 1730550000)
        self.assertIn("metrics", doc)
        metrics = doc["metrics"]
        self.assertEqual(metrics["heart_rate"], 72)
        self.assertEqual(metrics["blood_oxygen"], 98)
        self.assertEqual(metrics["stress_level"], 25)
        self.assertEqual(metrics["steps"], 4520)
        self.assertEqual(metrics["calories_burnt"], 310)
        self.assertEqual(metrics["sleep_data"]["status"], "light")
        self.assertEqual(metrics["sleep_data"]["duration_minutes"], 360)

    def test_parse_health_json_prefixed_payload(self):
        """Test parsing of HEALTH_JSON:<json> packets from WearOS GATT characteristic."""
        raw_json = json.dumps({
            "timestamp": 1730551000,
            "metrics": {
                "heart_rate": 80,
                "blood_oxygen": 99,
                "stress_level": 15,
                "steps": 6200,
                "calories_burnt": 420,
                "active_calories": 280,
                "resting_calories": 140,
                "sleep_data": {
                    "status": "deep",
                    "duration_minutes": 480,
                    "stages": {
                        "deep_minutes": 120,
                        "light_minutes": 240,
                        "rem_minutes": 90,
                        "awake_minutes": 30,
                    },
                    "efficiency": 94,
                },
            },
        })

        packet = b"HEALTH_JSON:" + raw_json.encode("utf-8")
        payload = HealthTelemetryParser.parse_payload(packet)

        self.assertIsNotNone(payload)
        self.assertEqual(payload.timestamp, 1730551000)
        self.assertEqual(payload.metrics.heart_rate, 80)
        self.assertEqual(payload.metrics.blood_oxygen, 99)
        self.assertEqual(payload.metrics.stress_level, 15)
        self.assertEqual(payload.metrics.steps, 6200)
        self.assertEqual(payload.metrics.calories_burnt, 420)
        self.assertEqual(payload.metrics.active_calories, 280)
        self.assertEqual(payload.metrics.resting_calories, 140)
        self.assertEqual(payload.metrics.sleep_data.status, "deep")
        self.assertEqual(payload.metrics.sleep_data.duration_minutes, 480)
        self.assertEqual(payload.metrics.sleep_data.efficiency, 94)
        self.assertEqual(payload.metrics.sleep_data.stages.deep_minutes, 120)

    def test_parse_legacy_health_packet(self):
        """Test backward compatibility with legacy HEALTH:steps:bpm:kcal payload."""
        packet = b"HEALTH:3500:75:240"
        payload = HealthTelemetryParser.parse_payload(packet)

        self.assertIsNotNone(payload)
        self.assertEqual(payload.metrics.steps, 3500)
        self.assertEqual(payload.metrics.heart_rate, 75)
        self.assertEqual(payload.metrics.calories_burnt, 240)
        self.assertEqual(payload.metrics.active_calories, 240)

    def test_malformed_and_empty_payloads(self):
        """Verify graceful error handling for empty or malformed packets."""
        self.assertIsNone(HealthTelemetryParser.parse_payload(b""))
        self.assertIsNone(HealthTelemetryParser.parse_payload(b"INVALID_PAYLOAD"))
        self.assertIsNone(HealthTelemetryParser.parse_payload(b"HEALTH_JSON:not-json"))
        self.assertIsNone(HealthTelemetryParser.parse_payload(b"HEALTH:invalid:abc:xyz"))

    def test_thread_safe_listener_notifications(self):
        """Verify thread safety under concurrent updates and listener notifications."""
        bridge = WinRTHealthBridge()
        received_payloads = []
        lock = threading.Lock()

        def listener(payload: HealthTelemetryPayload):
            with lock:
                received_payloads.append(payload)

        bridge.add_listener(listener)

        threads = []
        for i in range(20):
            t = threading.Thread(
                target=bridge.simulate_telemetry_stream,
                kwargs={
                    "steps": 1000 + i * 50,
                    "bpm": 70 + (i % 10),
                    "spo2": 95 + (i % 5),
                    "stress": 20 + i,
                    "calories": 200 + i * 10,
                    "sleep_minutes": 360,
                },
            )
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        self.assertEqual(len(received_payloads), 20)
        self.assertIsNotNone(bridge.latest_payload)


if __name__ == "__main__":
    unittest.main()
