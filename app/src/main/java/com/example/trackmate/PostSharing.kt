package com.example.trackmate

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.trackmate.services.PostItem

// Shared links are served by the backend at /p/<postId>; the manifest's App Link filter
// routes them back into the app when it's installed.
private const val SHARED_POST_PATH = "p"

fun sharedPostUrl(postId: Int) = "${BuildConfig.BASE_URL}/$SHARED_POST_PATH/$postId"

fun sharePost(context: Context, post: PostItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, post.title)
        putExtra(Intent.EXTRA_TEXT, "${post.title} on TrackMate\n${sharedPostUrl(post.id)}")
    }
    context.startActivity(Intent.createChooser(intent, "Share post"))
}

/** The post id from a shared link like https://<backend>/p/42, or null for anything else. */
fun parseSharedPostId(uri: Uri?): Int? {
    if (uri == null || uri.host != Uri.parse(BuildConfig.BASE_URL).host) return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != SHARED_POST_PATH) return null
    return segments[1].toIntOrNull()
}
