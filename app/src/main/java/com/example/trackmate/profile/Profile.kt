package com.example.trackmate.profile

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.AuthService
import com.example.trackmate.api.PostItem
import com.example.trackmate.api.PostRequest
import com.example.trackmate.api.PostService
import com.example.trackmate.api.ProfileResponse
import com.example.trackmate.api.ProfileService
import com.example.trackmate.api.UserResponse
import com.example.trackmate.posts.PostsAdapter
import com.example.trackmate.posts.configurePostView
import com.example.trackmate.util.showAboveKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

class Profile : Fragment() {
    private lateinit var username: TextView
    private lateinit var savedButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var travelsButton:ImageButton
    private lateinit var imgProfile: ImageView
    private lateinit var level: TextView
    private lateinit var friendsCount: TextView
    private lateinit var kmTravelled: TextView
    private lateinit var bio: TextView
    private lateinit var editProfileButton: Button
    private lateinit var newPostButton: Button
    private lateinit var postsRecycler: RecyclerView
    private lateinit var postsAdapter: PostsAdapter
    private lateinit var api: ProfileService
    private lateinit var postApi: PostService
    private lateinit var authApi: AuthService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        username = view.findViewById(R.id.username)
        savedButton = view.findViewById(R.id.savedButton)
        settingsButton = view.findViewById(R.id.settingsButton)
        travelsButton = view.findViewById(R.id.travelsButton)
        imgProfile = view.findViewById(R.id.imgProfile)
        level = view.findViewById(R.id.level)
        friendsCount = view.findViewById(R.id.friendsCount)
        kmTravelled = view.findViewById(R.id.kmTravelled)
        bio = view.findViewById(R.id.bio)
        newPostButton = view.findViewById(R.id.newPostButton)
        editProfileButton = view.findViewById(R.id.editProfileButton)
        api = (activity as MainActivity).profileService
        postApi = (activity as MainActivity).postService
        authApi = (activity as MainActivity).authService
        postsAdapter = PostsAdapter(
            onClick = { post ->
                val dialogView = layoutInflater.inflate(R.layout.item_post, null)
                val action = ProfileDirections.actionProfileToTrackNavigation(post.trackId)
                val dialog = AlertDialog.Builder(requireContext())
                    .setView(dialogView)
                    .setPositiveButton(R.string.dialog_edit, null)
                    .setNegativeButton(R.string.dialog_delete, null)
                    .create()

                dialog.setOnShowListener {
                    configurePostView(dialogView, post, activity as MainActivity) {
                        findNavController().navigate(action)
                        dialog.dismiss()
                    }

                    val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    positiveButton.setOnClickListener {
                        val editDialogView = layoutInflater.inflate(R.layout.dialog_edit_post, null)
                        val titleText = editDialogView.findViewById<EditText>(R.id.editPostTitle)
                        titleText.setText(post.title)
                        val descText =
                            editDialogView.findViewById<EditText>(R.id.editPostDescription)
                        descText.setText(post.description)
                        AlertDialog.Builder(requireContext())
                            .setView(editDialogView)
                            .setTitle(R.string.post_edit_title)
                            .setMessage(getString(R.string.post_edit_message, post.title))
                            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                                apiCallCoroutine.launch {
                                    try {
                                        val postRequest = PostRequest(
                                            post.id,
                                            titleText.text.toString(),
                                            descText.text.toString()
                                        )
                                        postApi.editPost(postRequest)
                                        withContext(Dispatchers.Main) {
                                            dialog.dismiss()
                                            reloadData()
                                        }
                                    } catch (e: Exception) {
                                        Log.d("API-ERROR", e.stackTraceToString())
                                    }
                                }
                            }
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .showAboveKeyboard()
                    }
                    negativeButton.setOnClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.post_delete_title)
                            .setMessage(getString(R.string.post_delete_message, post.title))
                            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                                apiCallCoroutine.launch {
                                    try {
                                        postApi.deletePost(post.id)
                                        withContext(Dispatchers.Main) {
                                            dialog.dismiss()
                                            reloadData()
                                        }
                                    } catch (e: Exception) {
                                        Log.d("API-ERROR", e.stackTraceToString())
                                    }
                                }
                            }
                            .setNegativeButton("No", null)
                            .show()
                    }
                }
                dialog.show()
            }
        )
        postsRecycler = view.findViewById(R.id.postsRecycler)
        postsRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        postsRecycler.adapter = postsAdapter

        reloadData()

        settingsButton.setOnClickListener {
            findNavController().navigate(R.id.settings)
        }

        savedButton.setOnClickListener {
            findNavController().navigate(R.id.bookmarks)
        }

        travelsButton.setOnClickListener {
            findNavController().navigate(R.id.travels)
        }

        friendsCount.setOnClickListener {
            findNavController().navigate(R.id.friends)
        }

        editProfileButton.setOnClickListener {
            findNavController().navigate(R.id.profileEdit)
        }

        newPostButton.setOnClickListener {
            findNavController().navigate(R.id.createPost)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    private fun reloadData() {
        apiCallCoroutine.launch {
            try {
                val userResponse: Response<UserResponse> = authApi.getUser()
                if (userResponse.isSuccessful && userResponse.body() != null) {
                    val user = userResponse.body()!!
                    val imageResponse = api.getProfileImage(user.id)
                    if (imageResponse.isSuccessful && imageResponse.body() != null) {
                        val bitmap = withContext(Dispatchers.IO) {
                            val inputStream = imageResponse.body()!!.byteStream()
                            BitmapFactory.decodeStream(inputStream)
                        }
                        withContext(Dispatchers.Main) {
                            imgProfile.setImageBitmap(bitmap)
                        }
                    }
                    val postsResponse: Response<List<PostItem>> = postApi.getUserPosts(user.id)
                    if (postsResponse.isSuccessful && postsResponse.body() != null) {
                        withContext(Dispatchers.Main) {
                            postsAdapter.submitList(postsResponse.body()!!)
                        }
                    }
                    val profileResponse: Response<ProfileResponse> = api.getProfile(user.id)
                    if (profileResponse.isSuccessful && profileResponse.body() != null) {
                        val profile = profileResponse.body()!!
                        withContext(Dispatchers.Main) {
                            username.text = profile.username
                            level.text = getString(R.string.profile_level, profile.level)
                            friendsCount.text = resources.getQuantityString(
                                R.plurals.profile_friends, profile.friendsNumber, profile.friendsNumber
                            )
                            kmTravelled.text =
                                getString(R.string.profile_km_travelled, "%.0f".format(profile.totalTravelledLength))
                            bio.text = profile.bio
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }
}
