# ဒေတာ သိုလှောင်မှုနှင့် Format သတ်မှတ်ချက်များ (မြန်မာဘာသာ)

## ၁။ သိုလှောင်မှု စနစ် ခြုံငုံသုံးသပ်ချက်

PhoneSHM သည် တုန်ခါမှု တိုင်းတာနေစဉ် ဖုန်း memory မပြည့်စေရန်နှင့် မြန်ဆန်စွာ ဒေတာသိမ်းဆည်းနိုင်ရန်အတွက် Hybrid Storage Architecture ကို အသုံးပြုပါသည်-
- **အတွင်းပိုင်း သိုလှောင်မှု**: မြန်နှုန်းမြင့် တုန်ခါမှုဒေတာများကို compact binary format (`.bin`) ဖြင့် သီးခြားရေးသားသည်။
- **Sidecar JSON Metadata**: တိုင်းတာမှုတိုင်းအတွက် သက်ဆိုင်ရာ အာရုံခံအခြေအနေနှင့် ပတ်ဝန်းကျင်အချက်အလက်များကို `.meta.json` ဖြင့် တွဲဖက်မှတ်တမ်းတင်သည်။
- **Termux နှင့် ပြင်ပမှ ရယူနိုင်မှု**: ပြီးဆုံးသွားသော binary နှင့် metadata ဖိုင်များကို `/sdcard/Download/PhoneSHM/` သို့ အလိုအလျောက် mirror ပြုလုပ်ပေးထားသောကြောင့် Termux, ADB သို့မဟုတ် PC မှ တိုက်ရိုက် ရယူအသုံးပြုနိုင်ပါသည်။

---

## ၂။ Binary တုန်ခါမှု ဖိုင်ဖွဲ့စည်းပုံ (`.bin`)

အချက်ပြလှိုင်း ဒေတာများကို ပိုမိုပေါ့ပါးမြန်ဆန်စေရန်အတွက် အောက်ပါအတိုင်း ဖွဲ့စည်းထားပါသည်-

```text
+---------------------+-----------------------+-----------------------+-----+
| Header: "SHM1"      | Sample 0              | Sample 1              | ... |
| 4 Bytes (0x00-0x03) | 20 Bytes (0x04-0x17)  | 20 Bytes (0x18-0x2B)  | ... |
+---------------------+-----------------------+-----------------------+-----+
```

### က။ Header (၄ Bytes)
| Offset | Field Name | Data Type | တန်ဖိုးနှင့် ဖော်ပြချက် |
| :--- | :--- | :--- | :--- |
| `0x00 - 0x03` | `magicHeader` | `ASCII[4]` | `"SHM1"` (`0x53, 0x48, 0x4D, 0x31`) သည် PhoneSHM binary format ကို သတ်မှတ်သည်။ |

### ခ။ Sample တစ်ခုစီ၏ ဖွဲ့စည်းပုံ (20 Bytes Little-Endian)
Offset `0x04` မှ စတင်၍ sample တစ်ခုစီတွင် ၂၀ bytes စီ ပါဝင်ပါသည်-

| Frame Relative Offset | Field Name | Data Type | Size (Bytes) | ဖော်ပြချက် |
| :--- | :--- | :--- | :--- | :--- |
| `+0x00 - +0x07` | `timestampNs` | `int64` (Long) | 8 | စက်စတင်ဖွင့်ချိန်မှ Nanosecond ဖြင့် ရေတွက်သော Hardware timestamp။ |
| `+0x08 - +0x0B` | `x` | `float32` (Float) | 4 | X-axis အလိုက် တုန်ခါမှု အရှိန် ($m/s^2$)။ |
| `+0x0C - +0x0F` | `y` | `float32` (Float) | 4 | Y-axis အလိုက် တုန်ခါမှု အရှိန် ($m/s^2$)။ |
| `+0x10 - +0x13` | `z` | `float32` (Float) | 4 | Z-axis အလိုက် တုန်ခါမှု အရှိန် ($m/s^2$)။ |

> [!NOTE]
> **Checksum တည်ဆောက်ပုံ အသိပေးချက်:**
> တိုင်းတာနေစဉ် ဖုန်းစက်ပိတ်သွားပါက ဖိုင်ပျက်စီးခြင်း မဖြစ်စေရန်အတွက် Checksum ကို `.bin` ဖိုင်အဆုံးတွင် ကပ်မထားဘဲ ဖိုင်ပြီးဆုံးချိန်တွင် **4-byte CRC-32 Checksum** ကို တွက်ချက်ကာ အတူတွဲဖက် `.meta.json` ဖိုင်ထဲတွင် `binaryChecksumCrc32` အနေဖြင့် ထည့်သွင်းသိမ်းဆည်းထားပါသည်။
> ဖိုင်တစ်ခုလုံး၏ အရွယ်အစားသည် အမြဲတစေ $\text{File Size} = 4 + (20 \times N) \text{ bytes}$ အတိအကျ ဖြစ်ပါသည်။

---

## ၃။ Sidecar Metadata ဖွဲ့စည်းပုံ (`.meta.json`)

တိုင်းတာမှုတစ်ခုစီအတွက် အောက်ပါ JSON schema အတိုင်း `.meta.json` ထွက်ရှိလာမည်ဖြစ်သည်:

```json
{
  "sessionId": "44ad63e8-5813-41bb-b5bf-73c3cb7db684",
  "measurementProfileId": "c92fa321-4f93-4a7b-a249-f53835e58129",
  "buildingHash": "b3e05a8f2f993d...",
  "buildingDisplayName": "City Hall Tower",
  "buildingType": "CONCRETE_FRAME",
  "floors": 12,
  "actualAverageSampleRateHz": 100.02,
  "sampleJitterStdMs": 0.14,
  "clockDriftPpm": 12.5,
  "sessionNoiseFloorMg": 0.42,
  "sessionAccelerometerBias": [0.0031, -0.0018, 0.0024],
  "binaryChecksumCrc32": "D7A19F83",
  "timeOfDay": "MORNING",
  "batteryTemperatureCelsius": 29.4
}
```

---

## ၄။ Python / NumPy ဖြင့် ဖတ်ယူစစ်ဆေးသည့် နမူနာ Code

```python
import numpy as np

# Sample frame data type (20 bytes little-endian)
dt = np.dtype([
    ('timestamp_ns', '<i8'),
    ('x', '<f4'),
    ('y', '<f4'),
    ('z', '<f4')
])

with open("session_xxx.bin", "rb") as f:
    # ၁။ Magic header 4 bytes ကို အတည်ပြုခြင်း
    magic = f.read(4)
    assert magic == b"SHM1", f"Invalid magic header: {magic}"
    
    # ၂။ ကျန် ၂၀ bytes sample များကို NumPy array အဖြစ် တိုက်ရိုက်ဖတ်ယူခြင်း
    data = np.fromfile(f, dtype=dt)

time_sec = (data['timestamp_ns'] - data['timestamp_ns'][0]) / 1e9
acc_x, acc_y, acc_z = data['x'], data['y'], data['z']
print(f"Total {len(data)} samples loaded across {time_sec[-1]:.2f} seconds.")
```
