# PowerMate

A free, independent Android app for **Powerpal** Bluetooth energy monitors. The official Powerpal
app is no longer maintained; PowerMate keeps the hardware useful by talking to the Powerpal
directly over Bluetooth and keeping your readings on your phone. There's no account to create
and no PowerMate server.

> PowerMate is not made by, endorsed by or affiliated with Powerpal. "Powerpal" is used only to
> say which device the app works with.

## Screenshots

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/live.png" width="240" alt="Live power and the last 15 minutes"><br><sub>Live power and the last 15 minutes</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/energy-clock-kwh.png" width="240" alt="Energy clock: energy per quarter hour, coloured by rate"><br><sub>Energy clock: energy per quarter hour, coloured by rate</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/energy-clock-kw.png" width="240" alt="Energy clock: five-minute power, with the day's peak"><br><sub>Energy clock: five-minute power, with the day's peak</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/energy-clock-cost.png" width="240" alt="Energy clock: what each quarter hour cost"><br><sub>Energy clock: what each quarter hour cost</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/month-chart.png" width="240" alt="A month of daily use, split by rate"><br><sub>A month of daily use, split by rate</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/week-power.png" width="240" alt="A week's power as a line graph"><br><sub>A week's power as a line graph</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/prices.png" width="240" alt="Wholesale prices, renewables share and forecast"><br><sub>Wholesale prices, renewables share and forecast</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/electricity-plan.png" width="240" alt="Time-of-use plan editor"><br><sub>Time-of-use plan editor</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/compare-plans.png" width="240" alt="Every plan at a postcode, priced against your usage"><br><sub>Every plan at a postcode, priced against your usage</sub></td>
  </tr>
</table>

## Features

**Live and history**

- Live power from the Powerpal's meter pulses, with what it's costing per hour at your current
  rate and a 15-minute trend.
- Catches up on new readings whenever it connects, and in the background every couple of hours.
  Older history still in the Powerpal's memory (about 60 days) can be downloaded on request.
- Optional import of your history from Powerpal's cloud, minute by minute, for as long as
  Powerpal's servers stay online.
- CSV export of every reading.

**Usage**

- Day, week, month and year views as a bar chart, line graph or table, in kWh or dollars.
- An energy clock for each day: a 24-hour dial of quarter-hour energy (kWh), five-minute power
  (kW) or cost, coloured by the rate in force, with a strip of the month's days for quick
  navigation.
- Double-tap a month or day in any chart to open it.
- Always-on (baseline) load estimate.

**Costs and plans**

- Flat-rate or time-of-use tariffs (peak, shoulder, off-peak, free periods; weekday and weekend;
  seasonal) and a daily connection fee, plus a projection for the current week, month or year.
- Plan comparison (Victoria): prices your own readings against every residential electricity
  plan offered at your postcode, using the plan data retailers publish under the Consumer Data
  Right. Choose how much recent usage counts as typical, from one week to twelve months, and
  adopt any plan as your PowerMate tariff in one tap.

**Prices (Victoria)**

- The wholesale spot price for Victoria from AEMO, or your own all-in price if you're an Amber
  Electric customer with an API token.
- The grid's renewables share, a 24-hour forecast that picks out cheap and expensive stretches,
  and a chart of today's half-hourly prices.

**Notifications**

- Reminders before cheap and expensive rate periods start, high usage, heavy use at your dearest
  rate, a rising always-on load, daily and weekly summaries, a low Powerpal battery, and
  wholesale price spikes.
- An optional "stay connected" mode keeps a live connection in the background so usage alerts
  work with the app closed.

**Device**

- Battery level, firmware, how much history the Powerpal holds, and its pairing code, so it
  isn't lost.
- A Bluetooth diagnostics log you can share when reporting a problem.

Price and plan features currently cover Victoria only. Everything else works anywhere.

## Requirements

- An Android phone running Android 8.0 or newer, with Bluetooth LE.
- A Powerpal monitor and its 6-digit pairing code (on the card that came with it, or in the
  original app).
- Your meter's rate in impulses per kWh, printed on the meter (usually 1000).

## Getting started

1. Close or uninstall the original Powerpal app. A Powerpal talks to only one app at a time.
2. Install PowerMate (see [Building](#building); there are no published builds yet).
3. Open PowerMate, tap **Find my Powerpal**, pick your device and enter its pairing code.
4. Enter your electricity plan under **Settings → Electricity plan → Edit plan**, or find it
   with **Compare plans for my usage** and tap **Use as my plan in PowerMate**.
5. Optionally, import your older history under **Settings → Import history from Powerpal's
   cloud**. PowerMate fills in the device ID and key from your Powerpal when it can.

## Privacy

Your readings are stored only on your phone, and nothing is sent to the developer. PowerMate
connects to these services, only for the features that need them:

| Service | Used for | What's sent |
|---|---|---|
| AEMO (`visualisations.aemo.com.au`) | Wholesale prices, price alerts | Nothing personal |
| Amber Electric (`api.amber.com.au`) | Renewables share; your prices if you add a token | Your token, only if you add one |
| AER Energy Made Easy (`cdr.energymadeeasy.gov.au`) | Plan comparison | Nothing personal: plans are filtered by postcode on the phone |
| Powerpal (`readings.powerpal.net`) | History import, only when you run it | Your Powerpal's device ID and key |

Permissions: Bluetooth (to find and connect to the Powerpal; on Android 11 and older, Android
also requires location access to scan), notifications, and a connected-device foreground
service for the optional background connection.

## Building

You'll need the Android SDK (platform 36) and JDK 17–21. Android Studio's bundled JDK works.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:installDebug   # with a phone connected over adb
```

Or open the project in Android Studio and press Run. If Gradle can't find a JDK, point
`JAVA_HOME` at Android Studio's (for example `"/c/Program Files/Android/Android Studio/jbr"`
on Windows).

Library versions are pinned (AGP 8.13, Kotlin 2.2, Compose BOM 2025.10) to a set that builds
against compileSdk 36; newer AndroidX releases need compileSdk 37 and AGP 9, so lint's "newer
version available" warnings are expected. Release builds are minified; sign them with your own
key.

## Project layout

Source lives under `app/src/main/java/app/powermate/`.

| Path | What |
|---|---|
| `ble/` | Bluetooth: UUIDs, payload encoding (`PowerpalProtocol`, unit tested), a coroutine GATT wrapper, and the session logic in `PowerpalManager` |
| `data/` | Room database of readings, DataStore settings, the tariff model, usage aggregation, CSV export and cloud import |
| `prices/` | Wholesale and Amber prices, and the cheap/expensive window analysis |
| `plans/` | Consumer Data Right plan download, parsing and pricing |
| `notify/` | Notifications, reminders, background workers and the live-connection service |
| `sync/` | Periodic background sync |
| `ui/` | Jetpack Compose screens and components |

## Bluetooth protocol

PowerMate builds on the protocol work in
[pento/powerpal-esphome](https://github.com/pento/powerpal-esphome) (MIT), itself based on
WeekendWarrior1's research. The Powerpal exposes service `59DAABCD-12F4-25A6-7D4F-55961DCE4205`;
its characteristics are `59DAxxxx-12F4-25A6-7D4F-55961DCE4205`. All integers are little-endian.

| Char | Op | Payload |
|---|---|---|
| `0011` pairing code | write | 6-digit code as u32. Required before anything else works |
| `0004` time | write | Unix seconds, u32. Lost on battery change; set every session |
| `0013` reading batch size | read/write | Minutes per record, u32 |
| `0005` first_rec | read | `[first_ts, last_ts]`, 2×u32: the range of stored records |
| `0002` measurement access | write | `[start_ts, end_ts]`, 2×u32: stream that range over `0001` |
| `0001` measurement | notify | `ts u32` + `pulses u16` per record |
| `0003` pulse | notify | u32 ms between pulses; W = 3.6e9 / (imp/kWh × ms) |
| `0008` LED sensitivity | read | Format unknown (shown raw) |
| `0009` API key | read | 16 bytes, formatted as a UUID, for Powerpal's cloud API |
| `180F/2A19` | read | Battery % |
| `180A/*` | read | Device information strings |

A session: connect → discover services → write the pairing code → write the time → set the
batch size to 1 minute → read device information → subscribe to `0001` → read `0005` → request
missing records through `0002` → subscribe to `0003` for live power.

## Contributing

Bug reports and pull requests are welcome. For Bluetooth problems, include the log from
**Device → Bluetooth diagnostics**, along with your phone model, Android version and the
Powerpal's firmware version (shown on the Device tab).

## Disclaimer

PowerMate is provided as-is, without warranty. Costs, projections and plan comparisons are
estimates from your readings and the tariff details you enter or download; check with your
retailer before making decisions based on them.

## License

PowerMate is released under the [MIT License](LICENSE). The Bluetooth protocol details come
from [pento/powerpal-esphome](https://github.com/pento/powerpal-esphome), also MIT licensed.
