package com.example.trackmate

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.services.AuthService
import com.example.trackmate.services.FriendRequestAction
import com.example.trackmate.services.FriendService
import com.example.trackmate.services.PostItem
import com.example.trackmate.services.PostService
import com.example.trackmate.services.ProfileResponse
import com.example.trackmate.services.ProfileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

class UserProfile : Fragment() {

    private lateinit var username: TextView
    private lateinit var imgProfile: ImageView
    private lateinit var level: TextView
    private lateinit var friendsCount: TextView
    private lateinit var kmTravelled: TextView
    private lateinit var bio: TextView
    private lateinit var friendRequestButton: Button
    private lateinit var postsRecycler: RecyclerView
    private lateinit var postsAdapter: PostsAdapter
    private lateinit var profileApi: ProfileService
    private lateinit var authApi: AuthService
    private lateinit var friendApi: FriendService
    private lateinit var postApi: PostService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private val args: UserProfileArgs by navArgs()
    private var userId: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_user_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        username = view.findViewById(R.id.username)
        imgProfile = view.findViewById(R.id.imgProfile)
        level = view.findViewById(R.id.level)
        friendsCount = view.findViewById(R.id.friendsCount)
        kmTravelled = view.findViewById(R.id.kmTravelled)
        bio = view.findViewById(R.id.bio)
        friendRequestButton = view.findViewById(R.id.friendRequestButton)
        profileApi = (activity as MainActivity).profileService
        friendApi = (activity as MainActivity).friendService
        authApi = (activity as MainActivity).authService
        postApi = (activity as MainActivity).postService
        postsAdapter = PostsAdapter(
            onClick = { post ->
                val dialogView = layoutInflater.inflate(R.layout.item_post, null)
                val action = UserProfileDirections.actionUserProfileToTrackNavigation(post.trackId)
                val dialog = AlertDialog.Builder(requireContext())
                    .setView(dialogView)
                    .create()
                configurePostView(dialogView, post, activity as MainActivity) {
                    dialog.dismiss()
                    findNavController().navigate(action)
                }
                dialog.show()
            }
        )
        postsRecycler = view.findViewById(R.id.userPostsRecycler)
        postsRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        postsRecycler.adapter = postsAdapter

        userId = args.id
        if (userId != -1) {
            reloadData(userId)
            updateFriendButton(userId)
        }
    }

    override fun onResume() {
        super.onResume()
        userId = args.id
        if (userId != -1) {
            reloadData(userId)
            updateFriendButton(userId)
        }
    }

    private fun reloadData(userId: Int) {
        apiCallCoroutine.launch {
            try {
                val profileResponse: Response<ProfileResponse> =
                    profileApi.getProfile(userId)
                if (profileResponse.isSuccessful && profileResponse.body() != null) {
                    val profile = profileResponse.body()!!
                    val imageResponse = profileApi.getProfileImage(userId)
                    if (imageResponse.isSuccessful && imageResponse.body() != null) {
                        val bitmap = withContext(Dispatchers.IO) {
                            val inputStream = imageResponse.body()!!.byteStream()
                            BitmapFactory.decodeStream(inputStream)
                        }
                        withContext(Dispatchers.Main) {
                            imgProfile.setImageBitmap(bitmap)
                        }
                    }
                    val postsResponse: Response<List<PostItem>> = postApi.getUserPosts(userId)
                    if (postsResponse.isSuccessful && postsResponse.body() != null) {
                        withContext(Dispatchers.Main) {
                            postsAdapter.submitList(postsResponse.body()!!)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        username.text = profile.username
                        level.text = "Level ${profile.level}"
                        friendsCount.text = "${profile.friendsNumber} Friends"
                        kmTravelled.text =
                            "${String.format("%.0f", profile.totalTravelledLength)} Km travelled"
                        bio.text = profile.bio
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun updateFriendButton(userId: Int) {
        apiCallCoroutine.launch {
            try {
                val currentUser = authApi.getUser().body() ?: return@launch
                val currentId = currentUser.id

                val sentIds =
                    friendApi.getSentFriendRequests().body()?.map { it.receiverId } ?: emptyList()
                val receivedIds =
                    friendApi.getFriendRequests().body()?.map { it.senderId } ?: emptyList()
                val friendsIds =
                    friendApi.getFriends(currentId).body()?.map { it.id } ?: emptyList()
                val (text, action) = when {
                    friendsIds.contains(userId) -> "Remove Friend" to suspend {
                        friendApi.removeFriend(
                            userId
                        )
                    }

                    receivedIds.contains(userId) -> "Accept Friend Request" to suspend {
                        friendApi.acceptRequest(
                            FriendRequestAction(userId)
                        )
                    }

                    sentIds.contains(userId) -> "Cancel Friend Request" to suspend {
                        friendApi.removeFriend(
                            userId
                        )
                    }

                    else -> "Send Friend Request" to suspend {
                        friendApi.sendRequest(
                            FriendRequestAction(userId)
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    friendRequestButton.text = text
                    friendRequestButton.setOnClickListener {
                        apiCallCoroutine.launch {
                            action()
                            updateFriendButton(userId)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }
}
