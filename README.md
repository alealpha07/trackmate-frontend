# Trackmate - Frontend

A mobile application built with **Kotlin** dedicated to travel enthusiasts.  
It combines **GPS tracking, data visualization, and social features** in a single integrated experience.

---

## Features

- **Record Routes** – Track your routes in real time via GPS  
- **Share Itineraries** – Share trips and itineraries with friends and other users  
- **Explore & Navigate** – Review, explore, and navigate saved tracks  
- **Monitor Performance** – Keep track of speed, distance, and route progress  
- **Daily Challenges** – Take part in daily challenges to make the experience more engaging  

---

## Prerequisites

Before building the project, make sure you have the following installed:

- **Android Studio** (latest version recommended)  
- **Android SDK >= 35**  
- **Gradle** (bundled with Android Studio is fine)  
- A valid **Stadia Maps API Key**  

Maps are powered by [OpenStreetMap](https://www.openstreetmap.org/) data, rendered via [Stadia Maps](https://stadiamaps.com/) and displayed with [osmdroid](https://github.com/osmdroid/osmdroid). Stadia Maps' free tier covers 200,000 tile loads/month and is free forever for non-commercial use.

---

## Setup Instructions

1. **Clone the repository:**
   ```bash
   git clone https://github.com/alealpha07/trackmate-frontend.git
   cd trackmate
   ```

2. **Configure API Key:**

   * Create a free account and API key at [client.stadiamaps.com](https://client.stadiamaps.com/signup/)
   * Copy the file `example.gradle.properties` to a new file named `gradle.properties`
   * Open `gradle.properties` and replace the placeholder value with your actual Stadia Maps API key:

     ```properties
     STADIA_MAPS_API_KEY=your_actual_api_key_here
     ```

3. **Build the Project:**
   Open the project in Android Studio and build it as usual.
   For detailed steps on generating an `.apk` file, refer to the [Android Studio official documentation](https://developer.android.com/studio/run).

---

## Authors

* **Alessandro Prati** – *Lead Developer*

