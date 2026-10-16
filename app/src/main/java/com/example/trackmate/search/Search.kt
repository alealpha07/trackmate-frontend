package com.example.trackmate.search

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.PostItem
import com.example.trackmate.api.PostSearchResponse
import com.example.trackmate.api.PostService
import com.example.trackmate.api.SearchItem
import com.example.trackmate.api.SearchService
import com.example.trackmate.api.UserSearchResponse
import com.example.trackmate.databinding.FragmentSearchBinding
import com.example.trackmate.databinding.ItemPostSearchResultBinding
import com.example.trackmate.databinding.ItemUserSearchResultBinding
import com.example.trackmate.posts.configurePostView
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

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
            binding.likeCount.text =
                binding.root.resources.getQuantityString(R.plurals.post_likes, post.likesCount, post.likesCount)
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


private const val SEARCH_DEBOUNCE_MS = 300L
private const val SEARCH_MIN_CHARS = 2

class Search : Fragment() {
    private lateinit var adapter: SearchAdapter
    private lateinit var api: SearchService
    private lateinit var postApi: PostService
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private var searchJob: Job? = null

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.searchEditText.requestFocus()
        binding.searchEditText.post {
            val imm = requireContext().getSystemService<InputMethodManager>()
            imm?.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
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
                searchJob?.cancel()
                val text = s?.toString()?.trim().orEmpty()
                if (text.length < SEARCH_MIN_CHARS) {
                    adapter.submitList(emptyList())
                    showMessage(null)
                    return
                }
                // one request when typing pauses, not one per key
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    fetchSearchResults(text)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private suspend fun fetchSearchResults(query: String) {
        try {
            val results: Response<List<SearchItem>> = api.search(query)
            val items = results.body()
            if (results.isSuccessful && items != null) {
                adapter.submitList(items)
                showMessage(if (items.isEmpty()) getString(R.string.search_no_results) else null)
            } else {
                adapter.submitList(emptyList())
                showMessage(results.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: getString(R.string.error_generic))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("API-ERROR", e.stackTraceToString())
            adapter.submitList(emptyList())
            showMessage(getString(if (e is IOException) R.string.error_offline else R.string.error_generic))
        }
    }

    private fun showMessage(text: String?) {
        val message = _binding?.searchMessage ?: return
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
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
                            SearchDirections.actionSearchToTrackNavigation(response.body()!!.trackId)
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