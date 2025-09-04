package com.example.trackmate

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.services.PostItem
import com.example.trackmate.services.PostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

class Bookmarks : Fragment() {

    private lateinit var postsRecycler: RecyclerView
    private lateinit var postsAdapter: PostsAdapter
    private lateinit var api: PostService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_bookmarks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postsAdapter = PostsAdapter (
            onClick = { post ->
                val dialogView = layoutInflater.inflate(R.layout.item_post, null)
                val action = BookmarksDirections.actionBookmarksToUserProfile(post.userId)
                val navAction = BookmarksDirections.actionBookmarksToTrackNavigation(post.trackId)
                val dialog = AlertDialog.Builder(requireContext())
                    .setView(dialogView).create()
                dialogView.findViewById<TextView>(R.id.username).setOnClickListener{
                    dialog.dismiss()
                    findNavController().navigate(action)
                }
                dialogView.findViewById<ImageView>(R.id.user_icon).setOnClickListener{
                    dialog.dismiss()
                    findNavController().navigate(action)
                }
                configurePostView(dialogView, post, activity as MainActivity){
                    dialog.dismiss()
                    findNavController().navigate(navAction)
                }
                dialog.show()

            }
        )
        postsRecycler = view.findViewById(R.id.postsRecycler)
        postsRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        postsRecycler.adapter = postsAdapter
        api = (activity as MainActivity).postService
        reloadData()
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    private fun reloadData() {
        apiCallCoroutine.launch {
            try {
                val postsResponse: Response<List<PostItem>> = api.getSavedPosts()
                if (postsResponse.isSuccessful && postsResponse.body() != null) {
                    withContext(Dispatchers.Main) {
                        postsAdapter.submitList(postsResponse.body()!!)
                    }
                }
            }catch (e: Exception){
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }
}
