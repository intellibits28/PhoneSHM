# Baseline Tracking & Physics-Based Validation

## 1. Physics-Based Domain Classification

Civil structures oscillate according to structural dynamics dictated by their mass, stiffness, geometry, and material damping. Ambient measurements from low-cost smartphones often pick up unrelated environmental vibrations (footsteps, elevator motors, HVAC equipment, and electrical mains hum). 

To ensure measurement integrity, **PhoneSHM** integrates domain physics models from civil engineering building codes (Eurocode 8 / EN 1998-1, ASCE 7-22) into [`core/physics`](file:///data/data/com.termux/files/home/play-ground/ronin_shm/core/physics/src/main/kotlin/com/ronin/phoneshm/core/physics/PhysicsRuleEngine.kt).

---

### A. Empirical Structural Period & Frequency Formulations
The theoretical fundamental period $T_1$ (seconds) and natural frequency $f_0$ (Hz) of a building with height $H$ (meters) or number of storeys $N$ are estimated using standard empirical equations:
$$T_1 = C_t \cdot H^{3/4}, \quad f_{0, \text{theo}} = \frac{1}{T_1}$$
where $H \approx N \cdot h_{\text{storey}}$ (assuming nominal floor height $h_{\text{storey}} \approx 3.0\text{ m}$ to $3.5\text{ m}$).

| Structural Archetype | Material Coefficient ($C_t$) | Typical Fundamental Period $T_1$ | Expected Fundamental Frequency Range |
| :--- | :--- | :--- | :--- |
| **Moment-Resisting Steel Frame** | $C_t \approx 0.085$ | $T \approx 0.085 H^{3/4}$ | $0.2\text{ Hz} - 8.0\text{ Hz}$ |
| **Reinforced Concrete (RC) Frame**| $C_t \approx 0.075$ | $T \approx 0.075 H^{3/4}$ | $0.3\text{ Hz} - 12.0\text{ Hz}$ |
| **Timber / Wood Frame** | $C_t \approx 0.050$ | $T \approx 0.050 H^{3/4}$ | $1.5\text{ Hz} - 25.0\text{ Hz}$ |
| **Masonry / Concrete Shear Wall** | $C_t \approx 0.050$ | $T \approx 0.050 H^{3/4}$ | $1.0\text{ Hz} - 20.0\text{ Hz}$ |

### B. Classification Categories

```mermaid
flowchart TD
    PeakFreq["Identified Peak Frequency f0"] --> CheckArtifact{"f0 near 50Hz/60Hz or < 0.2Hz?"}
    CheckArtifact -- Yes --> SensorArtifact["SENSOR_ARTIFACT\n(Mains hum or DC drift)"]
    CheckArtifact -- No --> CheckBounds{"Within [0.5 * f_theo, 2.0 * f_theo]?"}
    CheckBounds -- Yes --> GlobalMode["GLOBAL_MODE\n(Plausible fundamental building vibration)"]
    CheckBounds -- No --> CheckLocal{"f0 > 2.0 * f_theo?"}
    CheckLocal -- Yes --> LocalMode["LOCAL_MODE\n(Floor slab or localized component vibration)"]
    CheckLocal -- No --> SensorArtifact
```

1. **`GLOBAL_MODE`**:
   - The detected frequency falls squarely within the physical resonance envelope $[0.5 \cdot f_{0, \text{theo}}, 2.0 \cdot f_{0, \text{theo}}]$ for the declared structural material and height.
   - Represents the true global fundamental translation or torsional oscillation mode of the superstructure.
2. **`LOCAL_MODE`**:
   - The detected frequency is higher than the global envelope but corresponds to physical floor diaphragm bending, partition resonance, or machinery excitation.
3. **`SENSOR_ARTIFACT`**:
   - Spurious peaks corresponding to electrical grid noise ($50.0\text{ Hz} \pm 0.5\text{ Hz}$ or $60.0\text{ Hz} \pm 0.5\text{ Hz}$), high-frequency acoustic feedback ($> 45\text{ Hz}$), or DC drifting ($< 0.2\text{ Hz}$).

---

## 2. Longitudinal Baseline Manager Engine

A building's structural integrity is monitored by tracking long-term shifts in its fundamental frequency $f_0$. Structural stiffness degradation (e.g. earthquake micro-cracking, joint fatigue, subsidence) directly causes a downward shift in natural frequency:
$$f_0 = \frac{1}{2\pi}\sqrt{\frac{K}{M}} \implies \Delta K \approx 2 \cdot \frac{\Delta f_0}{f_0}$$

### A. Multi-Mode Welford Online Variance Algorithm
To maintain numerical precision and avoid unbounded memory growth over months of measurements, [`DefaultBaselineManagerEngine.kt`](file:///data/data/com.termux/files/home/play-ground/ronin_shm/core/baseline/src/main/kotlin/com/ronin/phoneshm/core/baseline/DefaultBaselineManagerEngine.kt) computes running means $\mu$ and variances $\sigma^2$ across multiple modal parameters ($f_0$, second mode $f_1$, damping ratio $\zeta_0$, and orthogonal horizontal axes $f_{0,x}, f_{0,y}$ with $M_{2, f_{0,x}}, M_{2, f_{0,y}}$) using Welford's algorithm:
$$\mu_n = \mu_{n-1} + \frac{x_n - \mu_{n-1}}{n}$$
$$M_{2, n} = M_{2, n-1} + (x_n - \mu_{n-1})(x_n - \mu_n)$$
$$\sigma_n = \sqrt{\frac{M_{2, n}}{n - 1}}$$

### B. Two-Phase Baseline Lifecycle & Anomaly-First Verification

```mermaid
stateDiagram-v2
    [*] --> Calibrating: Sessions 1 to 10
    Calibrating --> Calibrating: Accumulate reference measurements (n < 10)
    Calibrating --> Established: Session 10 completed
    Established --> Operational: Evaluate Thermal-Compensated Δf0 vs μ_baseline
    Operational --> AnomalyDetected: |Δf0_comp / μ| > 5% (Skip Welford & Thermal Update)
    Operational --> Operational: |Δf0_comp / μ| <= 5% (Update Welford & Co-Moments)
```

1. **Calibration Phase ($n < 10$)**:
   - During the first 10 sessions, PhoneSHM accumulates baseline data to establish natural environmental variability.
   - The UI indicates `CALIBRATING BASELINE (n/10)` with the current running mean and standard deviation.
2. **Operational Tracking Phase ($n \ge 10$) & Anomaly-First Ordering**:
   - The established baseline $(\mu_{\text{base}}, \sigma_{\text{base}})$ serves as the golden reference.
   - When a new session arrives, [`DefaultBaselineManagerEngine`](file:///data/data/com.termux/files/home/play-ground/ronin_shm/core/baseline/src/main/kotlin/com/ronin/phoneshm/core/baseline/DefaultBaselineManagerEngine.kt) **first** evaluates the thermal-compensated frequency $\hat{f}_0(T_{\text{ref}})$ using the existing thermal sensitivity $k_{T,\text{prev}}$ and checks whether $|\Delta \hat{f}_0\%| > 5.0\%$:
     $$\hat{f}_0(T_{\text{ref}}) = f_{0,\text{meas}} - k_{T,\text{prev}} \cdot (T - \mu_{T,\text{prev}}), \quad \Delta \hat{f}_0\% = \frac{\hat{f}_0(T_{\text{ref}}) - \mu_{\text{base}}}{\mu_{\text{base}}} \times 100\%$$
   - **Safety Threshold ($\pm 5.0\%$)**: If $|\Delta \hat{f}_0\%| > 5.0\%$, an anomaly alert is raised (`isAnomaly = true`).

### C. Safe Baseline & Thermal State Isolation
When an anomaly is detected (`isAnomaly == true`), PhoneSHM **does not** update the healthy Welford baseline statistics ($\mu_{\text{base}}, M_2, f_1, \zeta_0, f_{0,x}, f_{0,y}$) or the thermal co-moment regression states ($\mu_T, M_{2,T}, C_{T,f_0}$). Only verified non-anomalous sessions update the running baseline and thermal drift parameters.

---

## 3. Numerically Stable Welford Thermal Drift Compensation & Environmental Stratification

Temperature fluctuations alter structural stiffness through thermal expansion, joint friction, and changing concrete/timber elastic moduli ($E$). Without environmental correction, temperature cycles cause diurnal frequency fluctuations of $1\% - 3\%$, triggering false positive structural alarms.

PhoneSHM counters this through online thermal linear regression and diurnal stratification:
1. **Welford Co-Moment Thermal Regression (Catastrophic Cancellation Free)**:
   - Rather than storing raw sums ($\sum T, \sum T^2, \sum T f_0$) which suffer from floating-point cancellation when temperature variance is small, [`BaselineProfileEntity`](file:///data/data/com.termux/files/home/play-ground/ronin_shm/core/database/src/main/kotlin/com/ronin/phoneshm/core/database/entity/BaselineProfileEntity.kt) maintains centered Welford co-moment states (`meanTempC`, `m2Temp`, `coMomentTempF0`, `tempSampleCount`):
     $$\delta_T = T_n - \mu_{T, n-1}, \quad \mu_{T, n} = \mu_{T, n-1} + \frac{\delta_T}{n_T}$$
     $$M_{2, T, n} = M_{2, T, n-1} + \delta_T (T_n - \mu_{T, n}), \quad C_{T, f_0, n} = C_{T, f_0, n-1} + \delta_T (f_{0, n} - \mu_{f_0, n})$$
   - **Zero-Variance Guard**: Once $n_T \ge 10$ and $M_{2, T, n} > 10^{-4}\text{ }^\circ\text{C}^2$, the linear thermal sensitivity slope ($\text{Hz}/^\circ\text{C}$) is updated safely:
     $$k_T = \frac{C_{T, f_0, n}}{M_{2, T, n}}$$
     If temperature remains constant ($M_{2, T, n} \le 10^{-4}$), division by zero is prevented and $k_T$ remains `null` (zero compensation).
2. **Diurnal Time-of-Day Stratification**:
   - Categorized into 4 discrete temporal windows:
     - `MORNING` (05:00 - 11:59)
     - `AFTERNOON` (12:00 - 16:59)
     - `EVENING` (17:00 - 20:59)
     - `NIGHT` (21:00 - 04:59)
   - Allows baseline comparisons to be stratified against identical thermal windows, isolating genuine structural damage from natural thermal diurnal cycles.
