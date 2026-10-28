# Google Play store listing

Draft text and assets for the PowerMate listing. Character limits are Play's.

## App details

**App name** (30 max): `PowerMate: Energy Monitor` (25)

Keep "Powerpal" out of the name. Play treats another company's brand in an app's title as
implying a connection, while saying what a device works with in the description is fine.

**Short description** (80 max):
`Free, private app for Powerpal Bluetooth energy monitors. No account needed.` (76)

**Full description** (4,000 max):

```
PowerMate keeps your Powerpal energy monitor useful. It talks to the Powerpal directly over Bluetooth and keeps your readings on your phone: no account, no subscription, no ads and no tracking.

LIVE AND HISTORY
• Live power from your meter, with what it's costing per hour at your current rate
• Catches up on readings whenever it connects, and in the background
• Download the history still in your Powerpal's memory (about 60 days)
• Optionally import your older history from Powerpal's cloud while it's still online
• Export every reading to CSV

SEE WHERE YOUR ENERGY GOES
• An energy clock for each day: a 24-hour dial of energy, power or cost, coloured by your tariff
• Day, week, month and year charts, line graphs and tables, in kWh or dollars
• Double-tap any month or day to drill into it
• Your always-on (baseline) load

KNOW WHAT IT COSTS
• Flat-rate or time-of-use tariffs with peak, shoulder, off-peak and free periods, weekday and weekend, and seasonal rates
• Daily connection fee and a projection for the week, month or year
• Compare every residential electricity plan at your postcode against your own usage (Victoria), and switch PowerMate to any of them in one tap

PRICES (VICTORIA)
• Wholesale spot prices from AEMO, or your own price if you're with Amber Electric
• The grid's renewables share and a forecast of cheap and expensive times

ALERTS
• Reminders before cheap and expensive periods start
• High usage, heavy use at your dearest rate, a rising always-on load
• Daily and weekly summaries, low Powerpal battery and price spikes

PRIVATE BY DESIGN
Your readings never leave your phone. PowerMate only goes online for prices, plan data and, if you ask, importing your history from Powerpal's cloud.

You'll need a Powerpal Bluetooth energy monitor and its 6-digit pairing code.

PowerMate is independent and isn't made by, endorsed by or affiliated with Powerpal.
```

## Categorisation and contact

- **App category:** House & Home
- **Tags:** pick the closest to energy monitoring and utilities that Play offers.
- **Contact email (shown publicly):** [CONTACT EMAIL]
- **Website (optional):** the GitHub repository, once public
- **Privacy policy URL:** where `PRIVACY.md` is published (for example its GitHub page)

## Graphics

| Asset | File | Play requirement |
|---|---|---|
| App icon | `icon-512.png` | 512 × 512 PNG |
| Feature graphic | `feature-graphic.png` | 1024 × 500 |
| Phone screenshots | `screenshots/01…08-*.png` | 2–8 images; these are 1080 × 1920 (9:16) |

The icon and feature graphic come from `tools/make_logo.py`. The screenshots are framed with the
logo and a caption by `tools/frame_screenshots.py`, from full-resolution emulator captures. They
show a real household's history. The Live screen's power reading is simulated with the debug
build's mock connection (`adb shell am start -n app.powermate/.ui.MainActivity --ez mock_live true`).
