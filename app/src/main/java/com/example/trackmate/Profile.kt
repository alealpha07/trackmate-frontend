package com.example.trackmate

import android.app.AlertDialog
import android.graphics.Bitmap
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.trackmate.services.AuthService
import com.example.trackmate.services.PostItem
import com.example.trackmate.services.PostLikeSaveRequest
import com.example.trackmate.services.PostRequest
import com.example.trackmate.services.PostService
import com.example.trackmate.services.ProfileResponse
import com.example.trackmate.services.ProfileService
import com.example.trackmate.services.UserResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

class PostsAdapter(
    private val onClick: (PostItem) -> Unit
) : ListAdapter<PostItem, PostsAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<PostItem>() {
        override fun areItemsTheSame(old: PostItem, new: PostItem) = old.id == new.id
        override fun areContentsTheSame(old: PostItem, new: PostItem) = old == new
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val postImage: ImageView = view.findViewById(R.id.postImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_post_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val post = getItem(position)
        (holder.postImage.context as? MainActivity)?.let { activity ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response: Response<ResponseBody> =
                        activity.postService.getPostImage(post.id, 0)
                    if (response.isSuccessful && response.body() != null) {
                        val bitmap = BitmapFactory.decodeStream(response.body()!!.byteStream())
                        withContext(Dispatchers.Main) {
                            holder.postImage.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(post) }
    }
}

class PostImagesAdapter(private val images: List<Bitmap>) :
    RecyclerView.Adapter<PostImagesAdapter.ImageViewHolder>() {

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.imageCarouselItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_carousel, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        if (images.isNotEmpty()) {
            holder.image.setImageBitmap(images[position])
        }
    }

    override fun getItemCount(): Int = if (images.isEmpty()) 1 else images.size
}

fun configurePostView(
    view: View,
    post: PostItem,
    activity: MainActivity,
    navigateCallback: () -> Unit
) {
    view.apply {
        findViewById<TextView>(R.id.username).text = post.username
        findViewById<TextView>(R.id.description).text = post.description
        findViewById<TextView>(R.id.post_title).text = post.title
        findViewById<ImageButton>(R.id.navigate_button).setOnClickListener { navigateCallback() }
    }

    var postLiked = post.liked
    var postSaved = post.saved
    var postLikeCount = post.likeCount

    fun updateLikeState() {
        val likeResource =
            if (postLiked) R.drawable.baseline_thumb_up_alt_24 else R.drawable.baseline_thumb_up_off_alt_24
        view.findViewById<ImageButton>(R.id.like_button).setBackgroundResource(likeResource)
        view.findViewById<TextView>(R.id.like_count).text = "$postLikeCount Likes"
    }

    fun updateSaveState() {
        val saveResource =
            if (postSaved) R.drawable.baseline_bookmark_24 else R.drawable.baseline_bookmark_border_24
        view.findViewById<ImageButton>(R.id.save_button).setBackgroundResource(saveResource)
    }

    updateLikeState()
    updateSaveState()

    val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    apiCallCoroutine.launch {
        try {
            val userImageResponse = activity.profileService.getProfileImage(post.userId)
            if (userImageResponse.isSuccessful) {
                userImageResponse.body()?.byteStream()?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    withContext(Dispatchers.Main) {
                        view.findViewById<ImageView>(R.id.user_icon).setImageBitmap(bitmap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("API-ERROR", e.stackTraceToString())
        }
    }

    apiCallCoroutine.launch {
        try {
            val postImages = mutableListOf<Bitmap>()
            for (i in 0 until post.imageCount) {
                val imageResponse = activity.postService.getPostImage(post.id, i)
                if (imageResponse.isSuccessful) {
                    imageResponse.body()?.byteStream()?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        postImages.add(bitmap)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val carouselAdapter = PostImagesAdapter(postImages)
                view.findViewById<ViewPager2>(R.id.image_carousel).adapter = carouselAdapter
            }
        } catch (e: Exception) {
            Log.d("API-ERROR", e.stackTraceToString())
        }
    }

    view.findViewById<ImageButton>(R.id.save_button).setOnClickListener {
        apiCallCoroutine.launch {
            try {
                val response = if (!postSaved) {
                    activity.postService.savePost(PostLikeSaveRequest(post.id))
                } else {
                    activity.postService.unSavePost(post.id)
                }

                if (response.isSuccessful) {
                    postSaved = !postSaved
                    withContext(Dispatchers.Main) {
                        updateSaveState()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    view.findViewById<ImageButton>(R.id.like_button).setOnClickListener {
        apiCallCoroutine.launch {
            try {
                val response = if (!postLiked) {
                    activity.postService.likePost(PostLikeSaveRequest(post.id))
                } else {
                    activity.postService.unLikePost(post.id)
                }

                if (response.isSuccessful) {
                    postLiked = !postLiked
                    postLikeCount += if (postLiked) 1 else -1
                    withContext(Dispatchers.Main) {
                        updateLikeState()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }
}

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
                    .setPositiveButton("Edit", null)
                    .setNegativeButton("Delete", null)
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
                            .setTitle("Edit Post")
                            .setMessage("Editing post ${post.title}")
                            .setPositiveButton("Confirm") { _, _ ->
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
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    negativeButton.setOnClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Delete Post")
                            .setMessage("Do you really want to delete the post ${post.title}?")
                            .setPositiveButton("Yes") { _, _ ->
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
                            level.text = "Level " + profile.level.toString()
                            friendsCount.text = profile.friendsNumber.toString() + " Friends"
                            kmTravelled.text =
                                "${String.format("%.0f", profile.totalTravelledLength)} Km travelled"
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
