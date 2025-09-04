package com.example.trackmate

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.trackmate.databinding.FragmentSearchBinding
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.databinding.ItemPostSearchResultBinding
import com.example.trackmate.databinding.ItemUserSearchResultBinding
import com.example.trackmate.services.CollectQuestRequest
import com.example.trackmate.services.PostItem
import com.example.trackmate.services.SearchItem
import com.example.trackmate.services.UserSearchResponse
import com.example.trackmate.services.PostSearchResponse
import com.example.trackmate.services.PostService
import com.example.trackmate.services.SearchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

class SearchAdapter(
    private val onUserClick: (Int) -> Unit,
    private val onPostClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchItem>()

    fun submitList(newItems: List<SearchItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is UserSearchResponse -> VIEW_TYPE_USER
            else -> VIEW_TYPE_POST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemUserSearchResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            UserViewHolder(binding)
        } else {
            val binding = ItemPostSearchResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            PostViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is UserSearchResponse -> (holder as UserViewHolder).bind(item)
            is PostSearchResponse -> (holder as PostViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class UserViewHolder(private val binding: ItemUserSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(user: UserSearchResponse) {
            binding.usernameText.text = user.username
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imageResponse = (binding.root.context as MainActivity)
                        .profileService.getProfileImage(user.id)

                    if (imageResponse.isSuccessful && imageResponse.body() != null) {
                        val inputStream = imageResponse.body()!!.byteStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)

                        withContext(Dispatchers.Main) {
                            binding.userImage.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
            binding.openProfileButton.setOnClickListener { onUserClick(user.id) }
        }
    }

    inner class PostViewHolder(private val binding: ItemPostSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(post: PostSearchResponse) {
            binding.postTitleText.text = post.title
            binding.description.text = post.description
            binding.likeCount.text = "${post.likesCount} Likes"
            binding.username.text = post.username
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imageResponse = (binding.root.context as MainActivity)
                        .profileService.getProfileImage(post.userId)

                    if (imageResponse.isSuccessful && imageResponse.body() != null) {
                        val inputStream = imageResponse.body()!!.byteStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)

                        withContext(Dispatchers.Main) {
                            binding.userImage.setImageBitmap(bitmap)
                        }
                    }

                    val postImageResponse = (binding.root.context as MainActivity)
                        .postService.getPostImage(post.id, 0)

                    if (postImageResponse.isSuccessful && postImageResponse.body() != null) {
                        val inputStream = postImageResponse.body()!!.byteStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)

                        withContext(Dispatchers.Main) {
                            binding.postImage.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
            binding.openPostButton.setOnClickListener { onPostClick(post.id) }
        }
    }

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_POST = 1
    }
}


class Search : Fragment() {
    private lateinit var adapter: SearchAdapter
    private lateinit var api: SearchService
    private lateinit var postApi: PostService
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        api = (activity as MainActivity).searchService
        postApi = (activity as MainActivity).postService
        setupRecyclerView()
        setupSearchListener()

        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = SearchAdapter(
            onUserClick = { userId -> onUserClicked(userId) },
            onPostClick = { postId -> onPostClicked(postId) }
        )
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecyclerView.adapter = adapter
    }

    private fun setupSearchListener() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.length >= 2) {
                    fetchSearchResults(s.toString())
                } else {
                    adapter.submitList(emptyList())
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun fetchSearchResults(query: String) {
        val sanitizedQuery = query.replace("[^a-zA-Z0-9]".toRegex(), "")

        apiCallCoroutine.launch {
            try {
                val results: Response<List<SearchItem>> = api.search(sanitizedQuery)
                if (results.isSuccessful && results.body() != null) {
                    withContext(Dispatchers.Main) {
                        adapter.submitList(results.body()!!)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        adapter.submitList(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun onUserClicked(userId: Int) {
        findNavController().navigate(SearchDirections.actionSearchToUserProfile2(userId))
    }

    private fun onPostClicked(postId: Int) {
        val dialogView = layoutInflater.inflate(R.layout.item_post, null)
        apiCallCoroutine.launch {
            try {
                val response: Response<PostItem> = postApi.getPost(postId)
                if (response.isSuccessful && response.body() != null) {
                    withContext(Dispatchers.Main) {
                        val action =
                            SearchDirections.actionSearchToUserProfile2(response.body()!!.userId)
                        val navAction =
                            BookmarksDirections.actionBookmarksToTrackNavigation(response.body()!!.trackId)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(dialogView).create()
                        dialogView.findViewById<TextView>(R.id.username).setOnClickListener {
                            dialog.dismiss()
                            findNavController().navigate(action)
                        }
                        dialogView.findViewById<ImageView>(R.id.user_icon).setOnClickListener {
                            dialog.dismiss()
                            findNavController().navigate(action)
                        }
                        configurePostView(dialogView, response.body()!!, activity as MainActivity) {
                            dialog.dismiss()
                            findNavController().navigate(navAction)
                        }
                        dialog.show()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}