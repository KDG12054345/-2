package com.faust.presentation.view

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.data.utils.PreferenceManager
import com.faust.domain.persona.PersonaType

/**
 * 페르소나 선택 다이얼로그
 * 사용자가 페르소나를 선택하거나 등록 해제할 수 있습니다.
 */
class PersonaSelectionDialog(
    private val preferenceManager: PreferenceManager,
    private val onPersonaSelected: (String?) -> Unit
) : DialogFragment() {

    private lateinit var personaListAdapter: PersonaAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(android.R.layout.simple_list_item_1, null)

        // RecyclerView 및 어댑터 초기화
        val recyclerView = RecyclerView(requireContext())
        personaListAdapter = PersonaAdapter(requireContext()) { personaTypeString ->
            onPersonaSelected(personaTypeString)
            dismiss()
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = personaListAdapter

        // 현재 선택된 페르소나 확인
        val currentPersona = preferenceManager.getPersonaTypeString()
        personaListAdapter.setCurrentPersona(currentPersona)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.persona_selection))
            .setView(recyclerView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
    }
}

/**
 * 페르소나 리스트 어댑터
 */
class PersonaAdapter(
    private val context: android.content.Context,
    private val onPersonaClick: (String?) -> Unit
) : RecyclerView.Adapter<PersonaAdapter.ViewHolder>() {

    private val personas = mutableListOf<PersonaItem>()
    private var currentPersona: String = ""

    data class PersonaItem(
        val type: PersonaType?,
        val name: String,
        val description: String
    )

    fun setCurrentPersona(personaTypeString: String) {
        currentPersona = personaTypeString
        notifyDataSetChanged()
    }

    init {
        // 모든 페르소나 타입 추가
        personas.add(PersonaItem(
            PersonaType.STREET,
            context.getString(R.string.persona_street_name),
            context.getString(R.string.persona_street_description)
        ))
        personas.add(PersonaItem(
            PersonaType.CALM,
            context.getString(R.string.persona_calm_name),
            context.getString(R.string.persona_calm_description)
        ))
        personas.add(PersonaItem(
            PersonaType.DIPLOMATIC,
            context.getString(R.string.persona_diplomatic_name),
            context.getString(R.string.persona_diplomatic_description)
        ))
        personas.add(PersonaItem(
            PersonaType.COMFORTABLE,
            context.getString(R.string.persona_comfortable_name),
            context.getString(R.string.persona_comfortable_description)
        ))
        // 등록 해제 옵션 추가
        personas.add(PersonaItem(
            null,
            context.getString(R.string.persona_unregister),
            context.getString(R.string.persona_unregister_description)
        ))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(personas[position])
    }

    override fun getItemCount(): Int = personas.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(item: PersonaItem) {
            text1.text = item.name
            text2.text = item.description

            // 현재 선택된 페르소나 표시
            val isSelected = when {
                item.type == null && currentPersona.isEmpty() -> true
                item.type != null && currentPersona == item.type.name -> true
                else -> false
            }

            if (isSelected) {
                text1.text = "${item.name} ✓"
            }

            itemView.setOnClickListener {
                val selectedType = item.type?.name ?: ""
                onPersonaClick(if (selectedType.isEmpty()) null else selectedType)
            }
        }
    }
}
