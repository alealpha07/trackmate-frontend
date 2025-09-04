package com.example.trackmate

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.services.AuthService
import com.example.trackmate.services.CollectQuestRequest
import com.example.trackmate.services.QuestResponse
import com.example.trackmate.services.QuestService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuestAdapter(
    private var quests: List<QuestResponse>, private val onCollectClick: (QuestResponse) -> Unit
) : RecyclerView.Adapter<QuestAdapter.QuestViewHolder>() {

    inner class QuestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val points: TextView = itemView.findViewById(R.id.points)
        val description: TextView = itemView.findViewById(R.id.description)
        val progressText: TextView = itemView.findViewById(R.id.progressText)
        val btnCollect: Button = itemView.findViewById(R.id.btnCollect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quest, parent, false)
        return QuestViewHolder(view)
    }

    override fun onBindViewHolder(holder: QuestViewHolder, position: Int) {
        val quest = quests[position]
        holder.points.text = quest.experience.toString()
        holder.description.text = quest.description
        holder.progressText.text = "${quest.progress} / ${quest.maxProgress}"
        holder.btnCollect.isEnabled = quest.progress >= quest.maxProgress
        holder.btnCollect.setOnClickListener {
            onCollectClick(quest)
        }
    }

    override fun getItemCount() = quests.size

    fun updateQuests(newQuests: List<QuestResponse>) {
        quests = newQuests
        notifyDataSetChanged()
    }
}


class Quests : Fragment() {
    private lateinit var levelTitle: TextView
    private lateinit var levelNumber1: TextView
    private lateinit var levelNumber2: TextView
    private lateinit var levelNumber3: TextView
    private lateinit var levelBar0: ProgressBar
    private lateinit var levelBar1: ProgressBar
    private lateinit var levelProgressBar: ProgressBar
    private lateinit var questRecyclerView: RecyclerView
    private lateinit var questAdapter: QuestAdapter
    private lateinit var api: QuestService
    private lateinit var authApi: AuthService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private val maxExpPerLevel = 1000

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_quests, container, false)
        levelTitle = view.findViewById(R.id.level_title)
        levelNumber1 = view.findViewById(R.id.level_number_1)
        levelNumber2 = view.findViewById(R.id.level_number_2)
        levelNumber3 = view.findViewById(R.id.level_number_3)
        levelBar0 = view.findViewById(R.id.level_bar_0)
        levelBar1 = view.findViewById(R.id.level_bar_1)
        levelProgressBar = view.findViewById(R.id.level_progress_bar)
        questRecyclerView = view.findViewById(R.id.questRecyclerView)
        questRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        api = (activity as MainActivity).questService
        authApi = (activity as MainActivity).authService
        questAdapter = QuestAdapter(emptyList()) { quest ->
            apiCallCoroutine.launch {
                try {
                    (activity as MainActivity).questService.collectQuest(CollectQuestRequest(quest.id))
                    withContext(Dispatchers.Main) {
                        reloadData()
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }
        questRecyclerView.adapter = questAdapter

        reloadData()
        return view
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    private fun reloadData() {
        apiCallCoroutine.launch {
            try {
                val userResponse = authApi.getUser()
                if (userResponse.isSuccessful && userResponse.body() != null) {
                    withContext(Dispatchers.Main) {
                        val user = userResponse.body()!!
                        levelTitle.text = "Current Level: ${user.level}"
                        levelProgressBar.progress = user.experience
                        levelProgressBar.max = maxExpPerLevel

                        if (user.level == 0) {
                            levelBar0.visibility = View.INVISIBLE
                            levelNumber1.text = "0"
                            levelNumber1.setBackgroundResource(R.drawable.circle_background_colored)
                            levelBar1.progress = 0
                            levelNumber2.text = "1"
                            levelNumber2.setBackgroundResource(R.drawable.circle_background_gray)
                            levelNumber3.text = "2"
                            levelNumber3.setBackgroundResource(R.drawable.circle_background_gray)
                        } else {
                            levelBar0.visibility = if (user.level > 1) View.VISIBLE else View.GONE
                            levelNumber1.text = "${user.level - 1}"
                            levelNumber1.setBackgroundResource(R.drawable.circle_background_colored)
                            levelBar1.progress = 100
                            levelNumber2.text = "${user.level}"
                            levelNumber2.setBackgroundResource(R.drawable.circle_background_colored)
                            levelNumber3.text = "${user.level + 1}"
                            levelNumber3.setBackgroundResource(R.drawable.circle_background_gray)
                        }
                    }

                    val questsResponse = api.getQuests()
                    val questList = questsResponse.body() ?: emptyList()
                    withContext(Dispatchers.Main) {
                        questAdapter.updateQuests(questList)
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }
}

