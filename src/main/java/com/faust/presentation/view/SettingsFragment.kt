package com.faust.presentation.view

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.faust.R
import com.faust.data.utils.PreferenceManager
import com.faust.data.utils.TimeUtils
import com.faust.domain.DailyResetService
import kotlinx.coroutines.launch

/**
 * 설정 Fragment
 * 사용자 지정 일일 리셋 시간을 설정할 수 있는 UI를 제공합니다.
 */
class SettingsFragment : Fragment() {
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var textCurrentResetTime: TextView
    private lateinit var buttonSelectTime: com.google.android.material.button.MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        preferenceManager = PreferenceManager(requireContext())

        textCurrentResetTime = view.findViewById(R.id.textCurrentResetTime)
        buttonSelectTime = view.findViewById(R.id.buttonSelectTime)

        updateCurrentTimeDisplay()

        buttonSelectTime.setOnClickListener {
            showTimePicker()
        }
    }

    private fun updateCurrentTimeDisplay() {
        val currentTime = preferenceManager.getCustomDailyResetTime()
        textCurrentResetTime.text = getString(R.string.current_reset_time, currentTime)
    }

    private fun showTimePicker() {
        val currentTime = preferenceManager.getCustomDailyResetTime()
        val (hour, minute) = try {
            TimeUtils.parseTimeString(currentTime)
        } catch (e: Exception) {
            // 기본값 사용
            Pair(0, 0)
        }

        TimePickerDialog(
            requireContext(),
            { _, selectedHour, selectedMinute ->
                val timeString = String.format("%02d:%02d", selectedHour, selectedMinute)
                preferenceManager.setCustomDailyResetTime(timeString)
                updateCurrentTimeDisplay()

                // 알람 재스케줄링
                try {
                    DailyResetService.scheduleDailyReset(requireContext())
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.reset_time_updated, timeString),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "Failed to reschedule daily reset", e)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.reset_time_update_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            hour,
            minute,
            true // 24시간 형식
        ).show()
    }
}
