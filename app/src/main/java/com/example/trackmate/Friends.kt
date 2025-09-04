package com.example.trackmate

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.services.AuthService
import com.example.trackmate.services.FriendRequestAction
import com.example.trackmate.services.FriendResponse
import com.example.trackmate.services.FriendService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

fun ImageView.loadProfileImage(userId: Int, activity: MainActivity) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response: Response<ResponseBody> = activity.profileService.getProfileImage(userId)
            if (response.isSuccessful && response.body() != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeStream(response.body()!!.byteStream())
                }
                withContext(Dispatchers.Main) {
                    this@loadProfileImage.setImageBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            Log.d("API-ERROR", e.stackTraceToString())
        }
    }
}

class FriendsAdapter(
    private val onRemove: (FriendResponse) -> Unit,
    private val onViewProfile: (FriendResponse) -> Unit
) : ListAdapter<FriendResponse, FriendsAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<FriendResponse>() {
        override fun areItemsTheSame(old: FriendResponse, new: FriendResponse) = old.id == new.id
        override fun areContentsTheSame(old: FriendResponse, new: FriendResponse) = old == new
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val username: TextView = view.findViewById(R.id.usernameText)
        val userImage: ImageView = view.findViewById(R.id.userImage)
        val viewProfile: Button = view.findViewById(R.id.viewProfileButton)
        val remove: Button = view.findViewById(R.id.removeButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        holder.username.text = user.username
        (holder.userImage.context as? MainActivity)?.let { activity ->
            holder.userImage.loadProfileImage(user.id, activity)
        }
        holder.viewProfile.setOnClickListener { onViewProfile(user) }
        holder.remove.setOnClickListener { onRemove(user) }
    }
}

class FriendRequestsAdapter(
    private val onAccept: (FriendResponse) -> Unit,
    private val onRefuse: (FriendResponse) -> Unit,
    private val onViewProfile: (FriendResponse) -> Unit
) : ListAdapter<FriendResponse, FriendRequestsAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<FriendResponse>() {
        override fun areItemsTheSame(old: FriendResponse, new: FriendResponse) = old.id == new.id
        override fun areContentsTheSame(old: FriendResponse, new: FriendResponse) = old == new
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val username: TextView = view.findViewById(R.id.usernameText)
        val userImage: ImageView = view.findViewById(R.id.userImage)
        val accept: Button = view.findViewById(R.id.acceptButton)
        val refuse: Button = view.findViewById(R.id.refuseButton)
        val profile: Button = view.findViewById(R.id.openProfileButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        holder.username.text = user.username
        (holder.userImage.context as? MainActivity)?.let { activity ->
            holder.userImage.loadProfileImage(user.id, activity)
        }
        holder.accept.setOnClickListener { onAccept(user) }
        holder.refuse.setOnClickListener { onRefuse(user) }
        holder.profile.setOnClickListener { onViewProfile(user) }
    }
}

class Friends : Fragment() {
    private lateinit var friendRequestsTitle: TextView
    private lateinit var friendRequestsRecycler: RecyclerView
    private lateinit var friendsRecycler: RecyclerView
    private lateinit var requestsAdapter: FriendRequestsAdapter
    private lateinit var friendsAdapter: FriendsAdapter
    private lateinit var api: FriendService
    private lateinit var authApi: AuthService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_friends, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        friendRequestsRecycler = view.findViewById(R.id.friendRequestsRecycler)
        friendsRecycler = view.findViewById(R.id.friendsRecycler)
        friendRequestsTitle = view.findViewById(R.id.friendRequestsTitle)

        requestsAdapter = FriendRequestsAdapter(
            onAccept = { user -> acceptRequest(user) },
            onRefuse = { user -> refuseRequest(user) },
            onViewProfile = { user -> openProfile(user) }
        )
        friendsAdapter = FriendsAdapter(
            onRemove = { user -> removeFriend(user) },
            onViewProfile = { user -> openProfile(user) }
        )

        friendRequestsRecycler.layoutManager = LinearLayoutManager(requireContext())
        friendsRecycler.layoutManager = LinearLayoutManager(requireContext())
        friendRequestsRecycler.adapter = requestsAdapter
        friendsRecycler.adapter = friendsAdapter
        api = (activity as MainActivity).friendService
        authApi = (activity as MainActivity).authService
        reloadData()
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    private fun reloadData() {
        apiCallCoroutine.launch {
            try {
                val currentUser = authApi.getUser().body() ?: return@launch
                val currentId = currentUser.id

                val requests = api.getFriendRequests().body() ?: emptyList()
                val friends = api.getFriends(currentId).body() ?: emptyList()

                withContext(Dispatchers.Main) {
                    if (requests.isNotEmpty()) {
                        friendRequestsTitle.visibility = View.VISIBLE
                        friendRequestsRecycler.visibility = View.VISIBLE
                    } else {
                        friendRequestsTitle.visibility = View.GONE
                        friendRequestsRecycler.visibility = View.GONE
                    }
                    requestsAdapter.submitList(requests.map { it.sender })
                    friendsAdapter.submitList(friends)
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun acceptRequest(user: FriendResponse) {
        apiCallCoroutine.launch {
            try {
                api.acceptRequest(FriendRequestAction(user.id))
                reloadData()
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun refuseRequest(user: FriendResponse) {
        apiCallCoroutine.launch {
            try {
                api.removeFriend(user.id)
                reloadData()
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun removeFriend(user: FriendResponse) {
        apiCallCoroutine.launch {
            try {
                api.removeFriend(user.id)
                reloadData()
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun openProfile(user: FriendResponse) {
        val action = FriendsDirections.actionFriendsToUserProfile(user.id)
        findNavController().navigate(action)
    }
}
