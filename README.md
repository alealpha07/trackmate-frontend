# Trackmate Frontend

Android app for Trackmate, written in Kotlin. It records routes with GPS, lets you navigate saved
tracks, shows speed and distance stats, and has posts, friends and daily challenges.

Maps use OpenStreetMap data with Stadia Maps tiles, shown with osmdroid. Stadia's free plan is enough
for non-commercial use.

## Requirements

- Android Studio with Android SDK 35 or newer
- A Stadia Maps API key (free account at https://client.stadiamaps.com/signup/)

## Setup

```bash
git clone https://github.com/alealpha07/trackmate-frontend.git
```

Copy `example.gradle.properties` to `gradle.properties` and set your key:

```properties
STADIA_MAPS_API_KEY=your_api_key
```

Then open the project in Android Studio and build it.

## Author

Alessandro Prati
