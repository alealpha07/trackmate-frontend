package com.example.trackmate.api

import com.example.trackmate.planner.PlanError
import com.example.trackmate.planner.readPlanMessages
import com.squareup.moshi.Types
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// the app's real Retrofit and Moshi setup against answers captured from the backend
class ApiClientTest {
    private val server = MockWebServer()
    private val moshi = buildMoshi()
    private lateinit var retrofit: retrofit2.Retrofit

    @Before
    fun start() {
        server.start()
        retrofit = buildRetrofit(server.url("/").toString(), OkHttpClient())
    }

    @After
    fun stop() = server.shutdown()

    private inline fun <reified T> service(): T = retrofit.create(T::class.java)

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/api/$name")!!.bufferedReader().use { it.readText() }

    private fun answer(name: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(fixture(name)))

    // captured as the status on the first line, then the body
    private fun answerError(name: String): String {
        val (status, body) = fixture(name).trimEnd().split("\n", limit = 2)
        server.enqueue(MockResponse().setResponseCode(status.toInt()).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body))
        return body
    }

    private fun answerText(text: String = "OK") = server.enqueue(MockResponse().setBody(text))

    private fun RecordedRequest.json(): Map<String, Any?> {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type).fromJson(body.readUtf8())!!
    }

    @Test
    fun loginAndRegisterSendTheExpectedBodies() = runTest {
        val auth = service<AuthService>()
        answerText()
        auth.register(RegisterRequest("giulia", "correct-horse-battery", "correct-horse-battery", acceptTerms = true))
        val register = server.takeRequest()
        assertEquals("POST", register.method)
        assertEquals("/auth/register", register.path)
        assertEquals(
            mapOf("username" to "giulia", "password" to "correct-horse-battery", "confirmPassword" to "correct-horse-battery", "acceptTerms" to true),
            register.json()
        )

        answerText()
        auth.login(LoginRequest("giulia", "correct-horse-battery"))
        assertEquals(mapOf("username" to "giulia", "password" to "correct-horse-battery"), server.takeRequest().json())

        answer("auth_user.json")
        assertEquals(UserResponse(1, "giulia", "Gravel and hills around Parma", 0, 0), auth.getUser().body())
    }

    @Test
    fun errorsReachTheCallerWithStatusAndText() = runTest {
        val auth = service<AuthService>()
        val wrong = answerError("error_login_401.txt")
        val login = auth.login(LoginRequest("giulia", "wrong-password"))
        assertFalse(login.isSuccessful)
        assertEquals(401, login.code())
        assertEquals(wrong, login.errorBody()!!.string())

        val terms = answerError("error_register_422_it.txt")
        val register = auth.register(RegisterRequest("nuovo", "a-long-password", "a-long-password", acceptTerms = false))
        assertEquals(422, register.code())
        assertEquals(terms, register.errorBody()!!.string())
        assertTrue(terms.startsWith("Per registrarti"))

        server.enqueue(MockResponse().setResponseCode(429).setBody("Too many requests, wait a bit and try again!"))
        assertEquals(429, service<QuestService>().getQuests().code())
    }

    @Test
    fun searchResultsAreUsersOrPosts() = runTest {
        answer("search.json")
        val results = service<SearchService>().search("a").body()!!
        assertEquals("/search?text=a&sortBy=score", server.takeRequest().path)
        val post = results.filterIsInstance<PostSearchResponse>().single()
        assertEquals("Val Taro loop", post.title)
        assertEquals("giulia", post.username)
        assertEquals(2.0, post.score, 0.0)
        val users = results.filterIsInstance<UserSearchResponse>()
        assertEquals(listOf("giulia", "marco"), users.map { it.username })
        assertEquals(3.63, users.first().totalTravelled, 1e-9)
    }

    @Test
    fun sortedByDistanceTheScoresAreKilometers() = runTest {
        answer("search_distance.json")
        val results = service<SearchService>().search("a", sortBy = "distance").body()!!
        assertEquals("/search?text=a&sortBy=distance", server.takeRequest().path)
        val giulia = results.filterIsInstance<UserSearchResponse>().first { it.username == "giulia" }
        assertEquals(3.63, giulia.score, 1e-9)
    }

    @Test
    fun posts() = runTest {
        val posts = service<PostService>()
        answer("post.json")
        val post = posts.getPost(1).body()!!
        assertEquals(PostItem(1, 1, 0, "Val Taro loop", "Quiet roads, one steep climb", true, true, 1, "giulia", 1), post)

        answer("posts_friends.json")
        assertEquals(listOf(1), posts.getFriendsPosts(0).body()!!.map { it.id })
        answer("posts_saved.json")
        assertTrue(posts.getSavedPosts().body()!!.single().saved)

        answer("post_created.json")
        assertEquals(PostId(1), posts.createPost(PostRequest(1, "Val Taro loop", "Quiet roads")).body())
        repeat(3) { server.takeRequest() }
        assertEquals(mapOf("id" to 1.0, "title" to "Val Taro loop", "description" to "Quiet roads"), server.takeRequest().json())
    }

    @Test
    fun tracksAndTravels() = runTest {
        val tracks = service<TrackService>()
        answer("track_created.json")
        assertEquals(TrackId(1), tracks.createTrack(NewTrackRequest("Val Taro loop", "bicycle")).body())

        answer("tracks.json")
        val list = tracks.getTracks().body()!!
        assertEquals(listOf("Val Taro loop"), list.map { it.name })
        // the best of all users on the track
        assertEquals(698.5f, list.single().bestTime!!, 0.001f)

        answer("track_details.json")
        val details = tracks.getTrack(1).body()!!
        assertEquals(1, details.ownerId)
        assertEquals("giulia", details.userBest!!.username)
        assertEquals("marco", details.overallBest!!.username)
        assertEquals(698.5f, details.overallBest!!.time, 0.001f)

        answer("travels.json")
        val travel = tracks.getTravels().body()!!.single()
        assertEquals("15/10/2026", travel.dateTimeString)
        assertNull(travel.username)

        answer("travel_details.json")
        assertEquals(listOf("giulia", "marco"), tracks.getTravelsByTrack(1).body()!!.mapNotNull { it.username }.sorted())

        answer("leaderboard.json")
        assertEquals(listOf("marco", "giulia"), tracks.getLeaderboard(1).body()!!.map { it.name })

        answer("travel_created.json")
        assertEquals(TravelId(1), tracks.createTravel(NewTravelRequest(1, 754.2f, 17.31f, 32.8f, 3.63f)).body())

        val paths = (1..6).map { server.takeRequest().path }
        assertEquals("/track/details?id=1", paths[2])
        // null filters aren't sent
        assertEquals("/track", paths[1])
        val travelBody = server.takeRequest().json()
        assertFalse(travelBody.containsKey("vehicle"))
        assertEquals(754.2, travelBody["time"] as Double, 0.001)
    }

    @Test
    fun questsComeTranslated() = runTest {
        answer("quests_it.json")
        val quests = service<QuestService>().getQuests().body()!!
        assertTrue(quests.isNotEmpty())
        assertTrue(quests.all { it.type in QuestType.entries.map(QuestType::name) })
        // daily quests are random, but always in the request language
        val italian = Regex("^(Guida|Percorri|Pedala|Cammina|Naviga|Registra) ")
        assertTrue(quests.all { italian.containsMatchIn(it.description) })
        assertTrue(quests.all { it.vehicle in listOf("car", "motorcycle", "bicycle", "foot") })
    }

    @Test
    fun profileFriendsAndVehicles() = runTest {
        answer("profile.json")
        val profile = service<ProfileService>().getProfile(1).body()!!
        assertEquals(ProfileResponse("giulia", "Gravel and hills around Parma", 0, 0, 1, 3.63f), profile)

        val friends = service<FriendService>()
        answer("friends.json")
        assertEquals(listOf(FriendResponse(2, "marco", level = 0)), friends.getFriends(1).body())
        answer("friend_requests.json")
        val received = friends.getFriendRequests().body()!!.single()
        assertEquals(FriendResponse(3, "lena", level = 0), received.sender)
        assertNull(received.receiver)
        answer("friend_requests_sent.json")
        val sent = friends.getSentFriendRequests().body()!!.single()
        assertEquals(1, sent.receiverId)
        assertEquals("giulia", sent.receiver!!.username)

        answer("vehicles.json")
        val vehicles = service<RouteService>().vehicles().body()!!
        assertEquals(listOf("bicycle"), vehicles.filter { it.plannable }.map { it.id })
        answer("user_vehicles.json")
        assertEquals(listOf("car", "motorcycle", "bicycle", "foot"), service<ProfileService>().getVehicles().body())
    }

    @Test
    fun deletingTheAccountSendsThePasswordInADeleteBody() = runTest {
        answerText()
        service<ProfileService>().deleteAccount(DeleteAccountRequest("correct-horse-battery"))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/user", request.path)
        assertEquals(mapOf("password" to "correct-horse-battery"), request.json())
    }

    @Test
    fun instructionsSkipTheCache() = runTest {
        answer("instructions.json")
        val instructions = service<InstructionsService>().getInstructions(1).body()!!
        assertEquals("no-store", server.takeRequest().getHeader("Cache-Control"))
        assertEquals("de-DE", instructions.language)
        assertEquals(listOf(1, 4), instructions.maneuvers.map { it.type })
        assertTrue(instructions.maneuvers.first().isDeparture)
        assertEquals("de-DE: Head east.", instructions.maneuvers.first().spokenText)
        assertTrue(instructions.speedLimits.isEmpty())
    }

    @Test
    fun aPlanIsStreamedThenRead() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(fixture("plan.ndjson")))
        val request = PlanRequest(
            start = LatLng(44.7815, 9.846), via = listOf(LatLng(44.776, 9.856)), end = LatLng(44.7815, 9.856),
            vehicle = "bicycle", policy = "safest", filters = mapOf("cyclewaysOnly" to true), maxDetour = 25
        )
        val response = service<RouteService>().plan(request).execute()
        val route = response.body()!!.use { readPlanMessages(it.source(), moshi.adapter(PlanMessage::class.java)) {} }
        assertEquals(2, route.legs.size)
        assertTrue(route.track.size > 10)
        assertEquals(route.distance, route.legs.sumOf { it.distance }, 0.01)
        assertTrue(route.offBikeways!! <= route.distance)

        val sent = server.takeRequest()
        assertEquals("/route/plan?stream=1", sent.path)
        assertEquals(25.0, sent.json()["maxDetour"])
    }

    @Test
    fun aPlanWithoutDetourLimitLeavesItOut() = runTest {
        answerText()
        val request = PlanRequest(LatLng(1.0, 2.0), emptyList(), LatLng(1.1, 2.0), "bicycle", "shortest", emptyMap())
        service<RouteService>().plan(request).execute()
        assertFalse(server.takeRequest().json().containsKey("maxDetour"))
    }

    @Test
    fun aMapDataErrorEndsThePlan() {
        server.enqueue(MockResponse().setBody(fixture("plan_map_error.ndjson")))
        val progress = mutableListOf<PlanProgress>()
        val body = service<RouteService>().plan(PlanRequest(LatLng(1.0, 2.0), emptyList(), LatLng(1.1, 2.0), "bicycle", "safest", emptyMap())).execute().body()!!
        val error = assertThrows(PlanError::class.java) {
            runBlocking { body.use { readPlanMessages(it.source(), moshi.adapter(PlanMessage::class.java)) { p -> progress += p } } }
        }
        assertEquals("Map data is temporarily unavailable, try again later!", error.message)
        assertEquals(listOf(PlanProgress(0, 2)), progress)
    }
}
