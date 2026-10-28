# Play Console: policy and app content answers

Draft answers for the "App content" and release sections, with the reasoning behind each. The
developer account holder is responsible for what's submitted; check each answer against the
form's current wording.

## Privacy policy

Publish `PRIVACY.md` at a public URL (for example on GitHub) and enter that URL. Fill in its
contact address first.

## App access

**All or some functionality is restricted.** Everything past the welcome screen needs a Powerpal
Bluetooth energy monitor, a physical device. Suggested reviewer note:

> PowerMate is a companion app for Powerpal Bluetooth energy monitors (hardware). There is no
> login. Without a Powerpal nearby, the welcome screen and device search are all that can be
> used. The app pairs over Bluetooth Low Energy using the 6-digit code supplied with the device.

Reviewers without the hardware may reject the app as not working. A built-in demo mode with
sample data would let them see the whole app, and would also give the listing screenshots that
don't come from a real household.

## Ads

No, the app doesn't contain ads.

## Content rating

Category: utility, productivity, communication or other. Answer no to every content question
(violence, sexual content, profanity, drugs, gambling). PowerMate doesn't share the user's
location, and users can't interact with each other. Expect a rating of Everyone / PEGI 3.

## Target audience

18 and over. The app is for householders managing their electricity; choosing only adult ages
keeps it out of the Families policy.

## Data safety

All transmitted data is encrypted in transit (HTTPS).

**Does your app collect or share any of the required user data types?** The suggested answer is
**No**, because:

- The developer receives nothing: no analytics, crash reporting, ads or accounts.
- Readings, settings and device details stay on the phone.
- The only things sent anywhere are credentials or identifiers the user chooses to use with
  their own accounts: their Powerpal's device ID and key, sent to Powerpal to fetch their
  history, and their Amber token, sent to Amber for their prices. Both are sent only when the
  user starts the action.

If you'd rather declare conservatively, the closest categories are **Device or other IDs** (the
Powerpal device ID) and **App info and performance → Other**, marked as user-initiated, not
shared and not stored by the developer.

**Can users ask for their data to be deleted?** Data is never held off the device. In-app
deletion: Settings → Delete all readings; uninstalling removes everything.

## Permissions declarations

- **Foreground service type `connectedDevice`.** Play asks what it's used for and may ask for a
  short video. Suggested description:

  > Optional "Stay connected in the background" setting (Settings → Notifications). When the user turns it on,
  > PowerMate keeps a Bluetooth Low Energy connection to the user's Powerpal energy monitor so
  > that usage alerts (for example, high power use) can be raised in real time while the app is
  > closed. An ongoing notification is shown while it runs, and the user can turn it off at any
  > time.

  The video should show turning the setting on, the ongoing notification, and an alert arriving.
- **Bluetooth:** `BLUETOOTH_SCAN` is declared `neverForLocation`. `ACCESS_FINE_LOCATION` is
  requested only on Android 11 and older (`maxSdkVersion 30`), where Android requires it for
  Bluetooth scans.
- **Exact alarms:** not used; reminders use inexact alarms.
- **Advertising ID:** not used. Answer no; the app declares no `AD_ID` permission.

## Other declarations

News app: no. Government app: no. Financial features: none. Health: none.

## Release

1. **Upload key.** Create one in Android Studio (Build → Generate Signed App Bundle → Create
   new) and keep it outside the project, backed up. Copy `keystore.properties.example` to
   `keystore.properties` and fill it in. Then `./gradlew :app:bundleRelease` writes a signed
   `app/build/outputs/bundle/release/app-release.aab`.
2. **Play App Signing.** Accept it when creating the app. Google holds the app signing key, and
   a lost upload key can be reset through Play support.
3. **Package name** `com.aksevenfour.powermate` is permanent once uploaded.
4. **Testing before production.** Personal developer accounts created after 13 November 2023
   must run a closed test with at least 12 testers opted in for 14 days in a row before applying
   for production access. Organisation accounts are exempt.
5. **Version.** Give the first public release a clear version name (for example 1.0.0) and keep
   increasing `versionCode` for every upload.
