package com.example.trackmate.posts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.trackmate.BuildConfig
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.PostItem
import com.example.trackmate.api.PostLikeSaveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.net.URI
import java.net.URISyntaxException

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
        findViewById<ImageButton>(R.id.share_button).setOnClickListener { sharePost(context, post) }
    }

    var postLiked = post.liked
    var postSaved = post.saved
    var postLikeCount = post.likeCount

    fun updateLikeState() {
        val likeResource =
            if (postLiked) R.drawable.baseline_thumb_up_alt_24 else R.drawable.baseline_thumb_up_off_alt_24
        view.findViewById<ImageButton>(R.id.like_button).setBackgroundResource(likeResource)
        view.findViewById<TextView>(R.id.like_count).text =
            view.resources.getQuantityString(R.plurals.post_likes, postLikeCount, postLikeCount)
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

// shared links are /p/<postId> on the backend
private const val SHARED_POST_PATH = "p"

fun sharedPostUrl(postId: Int) = "${BuildConfig.BASE_URL}/$SHARED_POST_PATH/$postId"

fun sharePost(context: Context, post: PostItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, post.title)
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.post_share_text, post.title, sharedPostUrl(post.id)))
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.post_share)))
}

fun parseSharedPostId(uri: Uri?): Int? = uri?.let { sharedPostId(it.toString(), BuildConfig.BASE_URL) }

// takes a string, so it runs in unit tests without android.net.Uri
fun sharedPostId(link: String, baseUrl: String): Int? {
    val uri = try {
        URI(link)
    } catch (e: URISyntaxException) {
        return null
    }
    if (uri.host == null || uri.host != URI(baseUrl).host) return null
    val segments = uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
    if (segments.size != 2 || segments[0] != SHARED_POST_PATH) return null
    return segments[1].toIntOrNull()
}
