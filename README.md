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
- A valid **Google Maps API Key**  

---

## Setup Instructions

1. **Clone the repository:**
   ```bash
   git clone https://github.com/alealpha07/trackmate-frontend.git
   cd trackmate
   ```

2. **Configure API Key:**

   * Copy the file `example.gradle.properties` to a new file named `gradle.properties`
   * Open `gradle.properties` and replace the placeholder value with your actual Google Maps API key:

     ```properties
     MAPS_API_KEY=your_actual_api_key_here
     ```

3. **Build the Project:**
   Open the project in Android Studio and build it as usual.
   For detailed steps on generating an `.apk` file, refer to the [Android Studio official documentation](https://developer.android.com/studio/run).

---

## Authors

* **Alessandro Prati** – *Lead Developer*

