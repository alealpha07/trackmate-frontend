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

## License

MIT, see [LICENSE](LICENSE).

## Author

Alessandro Prati
