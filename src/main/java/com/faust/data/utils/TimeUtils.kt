package com.faust.data.utils

import java.util.Calendar

object TimeUtils {
    /**
     * 다음 월요일 00:00 시간을 반환합니다.
     */
    fun getNextMondayMidnight(): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = System.currentTimeMillis()
        
        // 현재 요일 확인 (Calendar.MONDAY = 2)
        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val daysUntilMonday = if (currentDayOfWeek == Calendar.MONDAY) {
            // 오늘이 월요일이면 다음 주 월요일
            7
        } else {
            // 월요일까지 남은 일수 계산
            (Calendar.MONDAY - currentDayOfWeek + 7) % 7
        }
        
        // 다음 월요일로 설정
        calendar.add(Calendar.DAY_OF_YEAR, daysUntilMonday)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        return calendar.timeInMillis
    }

    /**
     * 현재 시간이 월요일 00:00인지 확인합니다.
     */
    fun isMondayMidnight(): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = System.currentTimeMillis()
        
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY &&
                calendar.get(Calendar.HOUR_OF_DAY) == 0 &&
                calendar.get(Calendar.MINUTE) == 0
    }

    /**
     * 밀리초를 분 단위로 변환합니다.
     */
    fun millisecondsToMinutes(millis: Long): Long {
        return millis / (1000 * 60)
    }

    /**
     * 분을 밀리초로 변환합니다.
     */
    fun minutesToMilliseconds(minutes: Long): Long {
        return minutes * 60 * 1000
    }
}
