# PowerMate privacy policy

_Last updated: 28 October 2026_

PowerMate is a free app for Powerpal Bluetooth energy monitors. It's built so your data stays
with you: there's no account, no PowerMate server, no advertising, no analytics and no tracking.
The developer receives none of your data.

## What PowerMate keeps on your phone

- Your electricity readings, from your Powerpal over Bluetooth or imported from Powerpal's cloud.
- Your settings: your electricity plan and rates, meter rate, notification choices and, if you
  enter them, your postcode and an Amber Electric API token.
- Details of your Powerpal: its Bluetooth address, name, pairing code, serial number, firmware
  version and the cloud API key it reports.

This stays in the app's private storage on your phone. If you've turned on Android's backup to
your Google account, Android may include it in that backup, under Google's control and
encryption. PowerMate itself never uploads it anywhere.

## When PowerMate goes online

PowerMate only connects to these services, only for the features listed, and always over
encrypted connections (HTTPS):

| Service | What for | What PowerMate sends |
|---|---|---|
| Australian Energy Market Operator (AEMO) | Wholesale prices on the Prices tab and price alerts | Nothing about you |
| Amber Electric | The grid's renewables share; your own prices if you add an Amber API token | Your Amber token, only if you add one |
| Australian Energy Regulator's Energy Made Easy (Consumer Data Right) | Plan comparison | Nothing about you. Plans are downloaded and filtered by your postcode on the phone |
| Powerpal (readings.powerpal.net) | Importing your history, only when you choose to | Your Powerpal's device ID and API key, which the service uses to return your readings |

These services see your phone's IP address, as any website does, and their own privacy policies
apply to what they receive. PowerMate doesn't send your readings, location, contacts or any
other personal information to them.

## Permissions

- **Bluetooth** (nearby devices): to find and connect to your Powerpal. PowerMate doesn't use
  Bluetooth to work out where you are. On Android 11 and older, Android requires location
  permission for any Bluetooth scan; PowerMate uses it only for that scan.
- **Notifications**: for the alerts and reminders you turn on.
- **Foreground service (connected device)**: only if you turn on "stay connected", to keep the
  connection to your Powerpal open so usage alerts work while the app is closed. A notification
  shows whenever it's running.
- **Internet and network state**: for the services above.
- **Run at startup**: to reschedule your reminders and background sync after the phone restarts.

## Deleting your data

Delete your readings at any time in **Settings → Delete all readings**. Uninstalling PowerMate,
or clearing its storage in Android's settings, removes everything it has stored. You can also
export your readings to a CSV file first, under **Settings → Export readings (CSV)**.

## Children

PowerMate isn't directed at children and doesn't knowingly collect information from anyone.

## Changes

If this policy changes, the new version will be published here with a new date. PowerMate will
never start collecting your data without saying so here first.

## Contact

Questions about this policy: [adamkeher@gmail.com](mailto:adamkeher@gmail.com)
