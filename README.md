# Trackmate

Trackmate is an Android app to record, plan and repeat routes by car, motorcycle, bicycle or on foot, with stats
for every vehicle and a social side to share them.

The backend (API and web route planner) is in [trackmate-backend](https://github.com/alealpha07/trackmate-backend).

## What it does

- Record routes with GPS, with distance, time and speed on a card over the map.
- Navigate saved tracks: see how far along you are, get a warning when you leave the track, and keep the stats
  card in view.
- Plan bike routes, safest or shortest, with stops and filters (cycleways only, avoid unpaved, avoid high-stress
  roads). The same planner runs on the web.
- Save each track for a vehicle: car, motorcycle, bicycle or on foot. Stats and quests are kept per vehicle.
- Compare travels with graphs, a speed map and a leaderboard for each track.
- Share tracks as posts, add friends, like and save posts, and send links that open in the app.
- Complete daily quests to earn XP and level up.
- Light and dark theme, in English, Italian or German, following the phone or chosen in Settings.
- No email needed: an account is a username and a password. Download your data or delete your account in Settings.

## Tech stack

- Kotlin, Android SDK 35
- osmdroid, with OpenStreetMap data and Stadia Maps tiles
- Google Play services location
- Retrofit, OkHttp and Moshi
- MPAndroidChart

## Development

You need Android Studio, an emulator with Android 15 (API 35) or newer, and a Stadia Maps API key (free account at
https://client.stadiamaps.com/signup/).

1. Clone the repository, copy `example.gradle.properties` to `gradle.properties` and set your Stadia key in it.
2. Start the backend locally, see its [README](https://github.com/alealpha07/trackmate-backend#development).
3. Open the project in Android Studio and run the `app` configuration on the emulator.

Debug builds connect to the backend at `http://10.0.2.2:3000`, which is your computer's localhost as seen from the
emulator.

## Tests

```bash
./gradlew testDebugUnitTest
```

The unit tests run on the JVM, without an emulator or a backend. They cover the travel statistics, the planner's
coordinates, limits and route stream, the shared post links, the map follow logic and turn-by-turn guidance. They also
run the app's real Retrofit and Moshi setup against a MockWebServer that answers with JSON captured from the backend,
in `app/src/test/resources/api/`.

## Credits and data licences

- Map tiles © [Stadia Maps](https://stadiamaps.com/), map style © [OpenMapTiles](https://openmaptiles.org/), map data
  © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the Open Database License.
- Routes are planned on OpenStreetMap data read through the [Overpass API](https://overpass-api.de/), on the instances
  run by NextGIS OÜ and FOSSGIS e.V.
- Turn-by-turn directions come from [Valhalla](https://github.com/valhalla/valhalla) on the FOSSGIS e.V. server.
- Place search uses [Stadia Maps](https://stadiamaps.com/) and [Photon](https://photon.komoot.io/) by komoot.
- Elevation data produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH
  2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. The organisations in charge
  of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus
  WorldDEM-30.

The app shows them in Settings, with the open-source licences of its libraries.

## License

MIT, see [LICENSE](LICENSE).

## Author

Alessandro Prati
