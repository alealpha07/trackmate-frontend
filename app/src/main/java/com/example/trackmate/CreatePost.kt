package com.example.trackmate

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.trackmate.services.PostItem
import com.example.trackmate.services.PostRequest
import com.example.trackmate.services.PostService
import com.example.trackmate.services.TrackItem
import com.example.trackmate.services.TrackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File

class ImagePagerAdapter(private val images: List<Uri>) :
    RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.imageCarouselItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_image_carousel, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        if (images.isNotEmpty()) {
            holder.image.setImageURI(images[position])
        }
    }

    override fun getItemCount(): Int = if (images.isEmpty()) 1 else images.size
}

class CreatePost : Fragment() {
    private lateinit var viewPager: ViewPager2
    private lateinit var btnPickImages: Button
    private lateinit var editTitle: EditText
    private lateinit var editDescription: EditText
    private lateinit var spinnerTracks: Spinner
    private lateinit var btnSave: Button
    private lateinit var api: PostService
    private lateinit var trackApi: TrackService
    private val selectedImages = mutableListOf<Uri>()
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private var tracks: List<TrackItem> = emptyList()

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris != null) {
                selectedImages.clear()
                selectedImages.addAll(uris)
                viewPager.adapter = ImagePagerAdapter(selectedImages)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_create_post, container, false)

        viewPager = view.findViewById(R.id.viewPagerImages)
        btnPickImages = view.findViewById(R.id.btnPickImages)
        editTitle = view.findViewById(R.id.editTitle)
        editDescription = view.findViewById(R.id.editDescription)
        spinnerTracks = view.findViewById(R.id.spinnerTracks)
        btnSave = view.findViewById(R.id.btnSave)
        api = (requireActivity() as MainActivity).postService
        trackApi = (requireActivity() as MainActivity).trackService

        viewPager.adapter = ImagePagerAdapter(selectedImages)

        btnPickImages.setOnClickListener {
            imagePicker.launch("image/*")
        }

        loadTracks()

        btnSave.setOnClickListener {
            savePost()
        }
        return view
    }

    private fun loadTracks() {
        apiCallCoroutine.launch {
            try {
                val response = trackApi.getTracks()
                if (response.isSuccessful && response.body() != null) {
                    tracks = response.body()!!
                    withContext(Dispatchers.Main) {
                        val names = tracks.map { it.name }
                        val adapter = ArrayAdapter(
                            requireContext(), android.R.layout.simple_spinner_item, names
                        )
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        spinnerTracks.adapter = adapter
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }

    }

    private fun savePost() {
        val title = editTitle.text.toString().trim()
        val description = editDescription.text.toString().trim()
        val selectedTrackIndex = spinnerTracks.selectedItemPosition
        val trackId = tracks[selectedTrackIndex].id

        apiCallCoroutine.launch {
            try {
                val response = api.createPost(PostRequest(trackId, title, description))
                if (response.isSuccessful && response.body() != null) {
                    val postId = response.body()!!.id
                    for (uri in selectedImages) {
                        val inputStream = requireContext().contentResolver.openInputStream(uri)!!
                        val file = File.createTempFile("upload", ".jpg", requireContext().cacheDir)
                        file.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        val requestFile =
                            file.asRequestBody("image/${file.extension}".toMediaTypeOrNull())
                        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

                        val uploadResponse = api.uploadPostImage(body, postId)
                        withContext(Dispatchers.Main) {
                            if (!uploadResponse.isSuccessful) {
                                Toast.makeText(
                                    requireContext(),
                                    "Failed to upload an image",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Post created!", Toast.LENGTH_SHORT).show()
                        requireActivity().supportFragmentManager.popBackStack()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(), response.errorBody()?.string(), Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }
    }
}
