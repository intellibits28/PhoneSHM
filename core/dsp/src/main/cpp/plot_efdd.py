import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("efdd_spectrum.csv")
plt.figure(figsize=(10, 6))
plt.plot(df["FrequencyHz"], df["SV1"], color='b')
plt.title("EFDD Singular Value Spectrum (SV1)")
plt.xlabel("Frequency (Hz)")
plt.ylabel("Singular Value 1 (Magnitude)")
plt.grid(True)
plt.savefig("/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/4dc961e0-0968-4a21-bdc0-9110eb6d7341/efdd_spectrum.jpg")
