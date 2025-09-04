package com.example.trackmate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.trackmate.databinding.ActivityMainBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import androidx.core.content.edit
import com.example.trackmate.services.*
import okhttp3.Cache
import okhttp3.Interceptor
import androidx.appcompat.app.AppCompatDelegate

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
    lateinit var cache: Cache
    lateinit var cookieJar: SessionCookieJar

    private lateinit var binding: ActivityMainBinding

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
        super.onCreate(savedInstanceState)

        cookieJar = SessionCookieJar(baseContext)

        val moshi = Moshi.Builder()
            .add(
                PolymorphicJsonAdapterFactory.of(SearchItem::class.java, "type")
                    .withSubtype(UserSearchResponse::class.java, "user")
                    .withSubtype(PostSearchResponse::class.java, "post")
            )
            .add(KotlinJsonAdapterFactory())
            .build()


        val cacheSize = 5L * 1024 * 1024
        cache = Cache(cacheDir, cacheSize)

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .cache(cache)
            .addInterceptor(offlineCacheInterceptor())
            .addNetworkInterceptor(onlineCacheInterceptor())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        authService = retrofit.create(AuthService::class.java)
        profileService = retrofit.create(ProfileService::class.java)
        questService = retrofit.create(QuestService::class.java)
        searchService = retrofit.create(SearchService::class.java)
        friendService = retrofit.create(FriendService::class.java)
        trackService = retrofit.create(TrackService::class.java)
        postService = retrofit.create(PostService::class.java)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

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
            else{
                binding.navView.visibility = View.VISIBLE
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
