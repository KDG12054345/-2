package com.faust.presentation.view

import android.app.Dialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.models.BlockedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionDialog(
    private val onAppSelected: (BlockedApp) -> Unit
) : DialogFragment() {

    private lateinit var appListAdapter: InstalledAppAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // 어댑터를 먼저 초기화
        appListAdapter = InstalledAppAdapter { app ->
            onAppSelected(app)
            dismiss()
        }

        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = appListAdapter
        }

        // 어댑터 초기화 후 앱 목록 로드
        loadInstalledApps()

        return AlertDialog.Builder(requireContext())
            .setTitle("앱 선택")
            .setView(recyclerView)
            .setNegativeButton("취소", null)
            .create()
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { 
                        // 런처 아이콘이 있는 앱만 표시 (실제 사용 가능한 앱)
                        try {
                            pm.getLaunchIntentForPackage(it.packageName) != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                    .map {
                        BlockedApp(
                            packageName = it.packageName,
                            appName = pm.getApplicationLabel(it).toString()
                        )
                    }
                    .sortedBy { it.appName } // 앱 이름순 정렬
            }
            appListAdapter.submitList(apps)
        }
    }
}

class InstalledAppAdapter(
    private val onAppClick: (BlockedApp) -> Unit
) : RecyclerView.Adapter<InstalledAppAdapter.ViewHolder>() {

    private val apps = mutableListOf<BlockedApp>()

    fun submitList(newApps: List<BlockedApp>) {
        apps.clear()
        apps.addAll(newApps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(app: BlockedApp) {
            text1.text = app.appName
            text2.text = app.packageName

            itemView.setOnClickListener {
                onAppClick(app)
            }
        }
    }
}
