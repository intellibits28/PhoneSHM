# Data Formats & Storage Specification

## 1. Storage Architecture Overview

PhoneSHM uses a hybrid storage model designed for high throughput, zero garbage collection pauses during measurement, and seamless external research interoperability:
- **Internal Storage**: Raw vibration data is streamed to application-private directory files (`context.filesDir/sessions/`) to avoid I/O bottlenecks.
- **Sidecar JSON Metadata**: Each binary session is accompanied by an atomic `.meta.json` file containing full provenance, sensor telemetry, and environmental context.
- **Termux & External Access**: Each completed `.bin` and `.meta.json` file is automatically mirrored to the public downloads directory:
  `/sdcard/Download/PhoneSHM/`
  allowing root-free access via Termux, ADB, USB transfer, or file managers.

---

## 2. Binary Sensor Sample Format (`.bin`)

Raw high-frequency vibration samples ($x, y, z$ acceleration @ $100\text{ Hz}$) are streamed to a custom compact binary file without intermediate string serialization or JSON parsing overhead.

### Frame Layout (20 Bytes per Sample)
Each sample is encoded in **Little-Endian (x86_64 / ARM64 standard)** format:

| Offset (Bytes) | Field Name | Data Type | Size (Bytes) | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0x00 - 0x07` | `timestampNs` | `int64` (Long) | 8 | Hardware sensor timestamp in nanoseconds since device boot (`SensorEvent.timestamp`). |
| `0x08 - 0x0B` | `x` | `float32` (Float) | 4 | Calibrated acceleration along the smartphone X-axis ($m/s^2$). |
| `0x0C - 0x0F` | `y` | `float32` (Float) | 4 | Calibrated acceleration along the smartphone Y-axis ($m/s^2$). |
| `0x10 - 0x13` | `z` | `float32` (Float) | 4 | Calibrated acceleration along the smartphone Z-axis ($m/s^2$). |

### Throughput & File Sizing
- **Sampling Rate**: $100\text{ Hz}$ ($100\text{ samples/sec}$)
- **Data Rate**: $100 \times 20\text{ bytes} = 2,000\text{ bytes/sec} \approx 2\text{ KB/sec}$
- **30-Second Session**: $\approx 60\text{ KB}$
- **10-Minute Continuous Session**: $\approx 1.2\text{ MB}$

### Python / NumPy Parsing Example
```python
import numpy as np

dt = np.dtype([
    ('timestamp_ns', '<i8'),
    ('x', '<f4'),
    ('y', '<f4'),
    ('z', '<f4')
])

data = np.fromfile("session_xxx.bin", dtype=dt)
time_sec = (data['timestamp_ns'] - data['timestamp_ns'][0]) / 1e9
acc_x, acc_y, acc_z = data['x'], data['y'], data['z']
```

---

## 3. Sidecar Metadata JSON Schema (`.meta.json`)

Accompanies every binary `.bin` file with the identical base name (e.g. `<sessionId>.meta.json`):

```json
{
  "sessionId": "44ad63e8-5813-41bb-b5bf-73c3cb7db684",
  "measurementProfileId": "c92fa321-4f93-4a7b-a249-f53835e58129",
  "buildingHash": "b3e05a8f2f993d...",
  "buildingDisplayName": "City Hall Tower",
  "buildingType": "CONCRETE_FRAME",
  "floors": 12,
  "constructionYear": 2008,
  "primaryMaterial": "Reinforced Concrete",
  "latitude": 16.8661,
  "longitude": 96.1951,
  "measurementFloorLevel": 12,
  "surfaceType": "CONCRETE_SLAB",
  "locationType": "CENTER_SPAN",
  "phonePlacement": "FLAT_ON_FLOOR",
  "deviceCapabilityReportId": "RESEARCH_GRADE",
  "targetDurationSeconds": 60,
  "targetSampleRateHz": 100,
  "actualAverageSampleRateHz": 100.02,
  "sampleJitterStdMs": 0.14,
  "clockDriftPpm": 12.5,
  "rawStorageFileUri": "/data/data/com.ronin.phoneshm/files/sessions/44ad63e8....bin",
  "appVersionName": "1.0.0",
  "appVersionCode": 1,
  "gitCommitHash": "1e663c7",
  "sessionNoiseFloorMg": 0.42,
  "sessionAccelerometerBias": [0.0031, -0.0018, 0.0024],
  "recordedAtEpochMs": 1757829482000,
  "binaryChecksumCrc32": "D7A19F83",
  "timeOfDay": "MORNING",
  "batteryTemperatureCelsius": 29.4
}
```

### Key Field Descriptions
- **`binaryChecksumCrc32`**: 8-character hexadecimal CRC-32 checksum computed over the completed binary file to guarantee data integrity across uploads and storage transfers.
- **`sampleJitterStdMs`**: Standard deviation of the inter-sample arrival delta ($\sigma_{\Delta t}$ in milliseconds). Values $< 1.0\text{ ms}$ indicate excellent hardware clock consistency.
- **`clockDriftPpm`**: Relative deviation between the hardware oscillator clock and the system Linux monotonic clock, expressed in parts-per-million (PPM).
- **`sessionNoiseFloorMg`**: Estimated sensor noise floor in milli-g ($1\text{ mg} = 0.001g$) obtained during the pre-session 3-second zero-velocity calibration.
- **`sessionAccelerometerBias`**: 3-element array $[b_x, b_y, b_z]$ representing the static DC bias measured prior to acquisition.
- **`batteryTemperatureCelsius`**: Environmental proxy for ambient temperature at time of measurement.
