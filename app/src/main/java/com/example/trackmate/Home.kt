package com.example.trackmate

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import com.example.trackmate.services.PostItem
import com.example.trackmate.services.PostLikeSaveRequest
import com.example.trackmate.services.PostService
import okhttp3.ResponseBody

class FeedPostsAdapter(
    private val activity: MainActivity
) : ListAdapter<PostItem, FeedPostsAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<PostItem>() {
        override fun areItemsTheSame(old: PostItem, new: PostItem) = old.id == new.id
        override fun areContentsTheSame(old: PostItem, new: PostItem) = old == new
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val username: TextView = view.findViewById(R.id.username)
        val description: TextView = view.findViewById(R.id.description)
        val title: TextView = view.findViewById(R.id.post_title)
        val userIcon: ImageView = view.findViewById(R.id.user_icon)
        val likeButton: ImageButton = view.findViewById(R.id.like_button)
        val saveButton: ImageButton = view.findViewById(R.id.save_button)
        val travelButton: ImageButton = view.findViewById(R.id.navigate_button)
        val likeCount: TextView = view.findViewById(R.id.like_count)
        val imageCarousel: ViewPager2 = view.findViewById(R.id.image_carousel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_post, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val post = getItem(position)

        holder.username.text = post.username
        holder.description.text = post.description
        holder.title.text = post.title

        var postLiked = post.liked
        var postSaved = post.saved
        var postLikeCount = post.likeCount

        fun updateLikeState() {
            val likeResource =
                if (postLiked) R.drawable.baseline_thumb_up_alt_24 else R.drawable.baseline_thumb_up_off_alt_24
            holder.likeButton.setBackgroundResource(likeResource)
            holder.likeCount.text = "$postLikeCount Likes"
        }

        fun updateSaveState() {
            val saveResource =
                if (postSaved) R.drawable.baseline_bookmark_24 else R.drawable.baseline_bookmark_border_24
            holder.saveButton.setBackgroundResource(saveResource)
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
                            holder.userIcon.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }

        apiCallCoroutine.launch {
            try {
                val postImages = mutableListOf<android.graphics.Bitmap>()
                for (i in 0 until post.imageCount) {
                    val imageResponse: Response<ResponseBody> =
                        activity.postService.getPostImage(post.id, i)
                    if (imageResponse.isSuccessful) {
                        imageResponse.body()?.byteStream()?.use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            postImages.add(bitmap)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val carouselAdapter = PostImagesAdapter(postImages)
                    holder.imageCarousel.adapter = carouselAdapter
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }

        holder.travelButton.setOnClickListener {
            val action = HomeDirections.actionHomeToTrackNavigation(post.trackId)
            findNavController(holder.itemView).navigate(action)
        }

        holder.likeButton.setOnClickListener {
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
                        withContext(Dispatchers.Main) { updateLikeState() }
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }

        holder.saveButton.setOnClickListener {
            apiCallCoroutine.launch {
                try {
                    val response = if (!postSaved) {
                        activity.postService.savePost(PostLikeSaveRequest(post.id))
                    } else {
                        activity.postService.unSavePost(post.id)
                    }
                    if (response.isSuccessful) {
                        postSaved = !postSaved
                        withContext(Dispatchers.Main) { updateSaveState() }
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }

        holder.userIcon.setOnClickListener {
            findNavController(
                activity,
                R.id.homeRecyclerView
            ).navigate(HomeDirections.actionHomeToUserProfile(post.userId))
        }
        holder.username.setOnClickListener {
            findNavController(
                activity,
                R.id.homeRecyclerView
            ).navigate(HomeDirections.actionHomeToUserProfile(post.userId))
        }
    }
}

class Home : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var postsAdapter: FeedPostsAdapter
    private lateinit var api: PostService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    private var currentTab = 0
    private var trendingOffset = 0
    private var friendsOffset = 0
    private var isLoading = false
    private val pageSize = 10

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        tabLayout = view.findViewById(R.id.homeTabLayout)
        recyclerView = view.findViewById(R.id.homeRecyclerView)
        postsAdapter = FeedPostsAdapter(activity as MainActivity)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = postsAdapter
        tabLayout.addTab(tabLayout.newTab().setText("Trending"))
        tabLayout.addTab(tabLayout.newTab().setText("Friends"))
        api = (activity as MainActivity).postService

        loadTrendingPosts(reset = true)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                if (currentTab == 0) loadTrendingPosts(reset = true)
                else loadFriendsPosts(reset = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val layoutManager = rv.layoutManager as LinearLayoutManager
                val totalItemCount = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()

                if (!isLoading && lastVisible >= totalItemCount - 2) {
                    if (currentTab == 0) loadTrendingPosts(reset = false)
                    else loadFriendsPosts(reset = false)
                }
            }
        })

        return view
    }

    private fun loadTrendingPosts(reset: Boolean) {
        if (reset) trendingOffset = 0
        isLoading = true
        apiCallCoroutine.launch {
            try {
                val response: Response<List<PostItem>> =
                    api.getTrendingPosts(trendingOffset)
                if (response.isSuccessful && response.body() != null) {
                    val posts = response.body()!!
                    withContext(Dispatchers.Main) {
                        if (reset) {
                            postsAdapter.submitList(posts.toMutableList())
                        } else {
                            val current = postsAdapter.currentList.toMutableList()
                            current.addAll(posts)
                            postsAdapter.submitList(current)
                        }
                    }
                    trendingOffset += pageSize
                }
                isLoading = false
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }

    }

    private fun loadFriendsPosts(reset: Boolean) {
        if (reset) friendsOffset = 0
        isLoading = true
        apiCallCoroutine.launch {
            try {
                val response: Response<List<PostItem>> =
                    api.getFriendsPosts(friendsOffset)
                if (response.isSuccessful && response.body() != null) {
                    val posts = response.body()!!
                    withContext(Dispatchers.Main) {
                        if (reset) {
                            postsAdapter.submitList(posts.toMutableList())
                        } else {
                            val current = postsAdapter.currentList.toMutableList()
                            current.addAll(posts)
                            postsAdapter.submitList(current)
                        }
                    }
                    friendsOffset += pageSize
                }
                isLoading = false
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }
}
