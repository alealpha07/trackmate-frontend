package com.example.trackmate

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.trackmate.api.AuthService
import com.example.trackmate.api.FriendService
import com.example.trackmate.api.InstructionsService
import com.example.trackmate.api.PostService
import com.example.trackmate.api.ProfileService
import com.example.trackmate.api.QuestService
import com.example.trackmate.api.RouteService
import com.example.trackmate.api.SearchService
import com.example.trackmate.api.TrackService
import com.example.trackmate.api.buildRetrofit
import com.example.trackmate.databinding.ActivityMainBinding
import com.example.trackmate.map.configureTileCache
import com.example.trackmate.navigate.TrackRecordingService
import com.example.trackmate.track.TrackNavigationService
import com.example.trackmate.util.Vehicle
import com.example.trackmate.util.applySavedTheme
import com.example.trackmate.util.currentLanguage
import com.example.trackmate.posts.parseSharedPostId
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import okhttp3.Cache
import okhttp3.Interceptor
import java.io.File
import java.util.concurrent.TimeUnit

private val LOCAL_GPS_FILES = listOf("track.json", "navigation.json", "navigation_track.json", "travel_points.json")

class SessionCookieJar(context: Context) : CookieJar {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cookie_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            if (cookie.name == "connect.sid") {
                prefs.edit { putString("connect.sid", cookie.toString()) }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieString = prefs.getString("connect.sid", null)
        val cookie = cookieString?.let { Cookie.parse(url, it) }
        return cookie?.let { listOf(it) } ?: emptyList()
    }

    fun clearCookies() {
        prefs.edit { remove("connect.sid") }
    }
}

class MainActivity : AppCompatActivity() {
    lateinit var authService: AuthService
    lateinit var profileService: ProfileService
    lateinit var questService: QuestService
    lateinit var searchService: SearchService
    lateinit var friendService: FriendService
    lateinit var trackService: TrackService
    lateinit var postService: PostService
    lateinit var routeService: RouteService
    lateinit var instructionsService: InstructionsService
    lateinit var cache: Cache
    lateinit var cookieJar: SessionCookieJar

    private lateinit var binding: ActivityMainBinding

    // post id from a shared link, opened by Home
    private var pendingSharedPostId: Int? = null

    fun consumePendingSharedPostId(): Int? = pendingSharedPostId.also { pendingSharedPostId = null }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onlineCacheInterceptor() = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        response.newBuilder()
            .header("Cache-Control", "public, max-age=1")
            .build()
    }

    private fun languageInterceptor() = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("Accept-Language", currentLanguage().tag).build())
    }

    private fun offlineCacheInterceptor() = Interceptor { chain ->
        var request = chain.request()
        if (!isNetworkAvailable()) {
            request = request.newBuilder()
                .header("Cache-Control", "public, only-if-cached, max-stale=604800")
                .build()
        }
        chain.proceed(request)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // before super, so the first frame already has the right theme
        applySavedTheme(this)
        super.onCreate(savedInstanceState)

        configureTileCache(this)

        cookieJar = SessionCookieJar(baseContext)

        val cacheSize = 5L * 1024 * 1024
        cache = Cache(cacheDir, cacheSize)

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .cache(cache)
            .addInterceptor(languageInterceptor())
            .addInterceptor(offlineCacheInterceptor())
            .addNetworkInterceptor(onlineCacheInterceptor())
            .build()

        val retrofit = buildRetrofit(BuildConfig.BASE_URL, client)

        authService = retrofit.create(AuthService::class.java)
        profileService = retrofit.create(ProfileService::class.java)
        questService = retrofit.create(QuestService::class.java)
        searchService = retrofit.create(SearchService::class.java)
        friendService = retrofit.create(FriendService::class.java)
        trackService = retrofit.create(TrackService::class.java)
        postService = retrofit.create(PostService::class.java)
        // no read timeout, a plan can take a while
        routeService = retrofit.newBuilder()
            .client(client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build())
            .build()
            .create(RouteService::class.java)
        // a long track waits on the server's queue, about a second per 100 km
        instructionsService = retrofit.newBuilder()
            .client(client.newBuilder().readTimeout(2, TimeUnit.MINUTES).build())
            .build()
            .create(InstructionsService::class.java)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.home, R.id.search, R.id.navigate, R.id.quests, R.id.profile)
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.loginRegister){
                binding.navView.visibility = View.GONE
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
            // also opened from the register form, before login
            else if (destination.id == R.id.legal){
                binding.navView.visibility = View.GONE
            }
            else{
                binding.navView.visibility = View.VISIBLE
            }
        }

        hideBottomNavWhileTyping()

        // only on a fresh launch
        if (savedInstanceState == null) pendingSharedPostId = parseSharedPostId(intent?.data)
    }

    // hide the bottom nav while the keyboard is open
    private fun hideBottomNavWhileTyping() {
        var keyboardOpen = false
        var navHiddenForKeyboard = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val open = insets.isVisible(WindowInsetsCompat.Type.ime())
            // only when the keyboard toggles
            if (open != keyboardOpen) {
                keyboardOpen = open
                if (open) {
                    navHiddenForKeyboard = binding.navView.isVisible
                    binding.navView.isVisible = false
                } else if (navHiddenForKeyboard) {
                    // restore only what we hid
                    navHiddenForKeyboard = false
                    binding.navView.isVisible = true
                }
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    // singleTask: shared link opened while the app is running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val postId = parseSharedPostId(intent.data) ?: return
        pendingSharedPostId = postId

        // still on the login screen, home opens it after login
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        if (navController.currentDestination?.id == R.id.loginRegister) return
        // recreate Home so it opens the pending post
        navController.navigate(
            R.id.home,
            null,
            NavOptions.Builder().setPopUpTo(R.id.home, true).build()
        )
    }

    // on logout and account deletion, also when the server can't be reached
    fun clearLocalData() {
        TrackRecordingService.discard()
        stopService(Intent(this, TrackRecordingService::class.java))
        stopService(Intent(this, TrackNavigationService::class.java))
        cookieJar.clearCookies()
        runCatching { cache.evictAll() }
        LOCAL_GPS_FILES.forEach { File(filesDir, it).delete() }
        cacheDir.listFiles { file -> file.name.startsWith("upload") && file.name.endsWith(".jpg") }
            ?.forEach { it.delete() }
        Vehicle.clearSaved(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
