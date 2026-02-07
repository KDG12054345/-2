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

    /**
     * "HH:mm" 형식의 시간 문자열을 (시간, 분) 쌍으로 파싱합니다.
     * @param time "HH:mm" 형식의 시간 문자열 (예: "02:00", "14:30")
     * @return (시간, 분) 쌍
     * @throws IllegalArgumentException 시간 형식이 잘못된 경우
     */
    fun parseTimeString(time: String): Pair<Int, Int> {
        val parts = time.split(":")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid time format: $time. Expected format: HH:mm")
        }
        val hour = parts[0].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid hour: ${parts[0]}")
        val minute = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid minute: ${parts[1]}")
        
        if (hour !in 0..23) {
            throw IllegalArgumentException("Hour must be between 0 and 23: $hour")
        }
        if (minute !in 0..59) {
            throw IllegalArgumentException("Minute must be between 0 and 59: $minute")
        }
        
        return Pair(hour, minute)
    }

    /**
     * 사용자 지정 시간 기준으로 오늘 시작 시간(timestamp)을 반환합니다.
     * 예: 사용자가 02:00을 설정한 경우, 오늘 02:00:00의 timestamp를 반환합니다.
     * 
     * @param customTime "HH:mm" 형식의 사용자 지정 시간 (예: "02:00")
     * @return 오늘 시작 시간의 timestamp (밀리초)
     */
    fun getCurrentDayStart(customTime: String): Long {
        val (hour, minute) = parseTimeString(customTime)
        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()
        calendar.timeInMillis = now
        
        // 오늘의 사용자 지정 시간으로 설정
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        val todayStart = calendar.timeInMillis
        
        // 현재 시간이 오늘의 사용자 지정 시간 이전이면, 어제의 사용자 지정 시간이 오늘 시작 시간
        if (now < todayStart) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            return calendar.timeInMillis
        }
        
        return todayStart
    }

    /**
     * 사용자 지정 시간 기준으로 다음 리셋 시간(timestamp)을 반환합니다.
     * 예: 사용자가 02:00을 설정한 경우, 다음 02:00:00의 timestamp를 반환합니다.
     * 
     * @param customTime "HH:mm" 형식의 사용자 지정 시간 (예: "02:00")
     * @return 다음 리셋 시간의 timestamp (밀리초)
     */
    fun getNextResetTime(customTime: String): Long {
        val (hour, minute) = parseTimeString(customTime)
        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()
        calendar.timeInMillis = now
        
        // 오늘의 사용자 지정 시간으로 설정
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        val todayReset = calendar.timeInMillis
        
        // 현재 시간이 오늘의 사용자 지정 시간 이전이면 오늘이 다음 리셋 시간
        if (now < todayReset) {
            return todayReset
        }
        
        // 현재 시간이 오늘의 사용자 지정 시간 이후면 내일이 다음 리셋 시간
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    /**
     * 사용자 지정 시간 기준으로 새 날인지 확인합니다.
     * 예: 사용자가 02:00을 설정한 경우, 현재 시간이 02:00:00을 넘었는지 확인합니다.
     * 
     * @param customTime "HH:mm" 형식의 사용자 지정 시간 (예: "02:00")
     * @param lastResetTime 마지막 리셋 시간 (timestamp, 밀리초)
     * @return 새 날이면 true, 아니면 false
     */
    fun isNewDay(customTime: String, lastResetTime: Long): Boolean {
        if (lastResetTime <= 0) {
            return true // 리셋 기록이 없으면 새 날로 간주
        }
        
        val nextResetTime = getNextResetTime(customTime)
        val now = System.currentTimeMillis()
        
        // 마지막 리셋 시간 이후에 다음 리셋 시간이 지나갔는지 확인
        // 예: 마지막 리셋이 어제 02:00이고, 다음 리셋이 오늘 02:00인데 현재가 오늘 02:00 이후면 새 날
        if (lastResetTime < nextResetTime && now >= nextResetTime) {
            return true
        }
        
        // 마지막 리셋 시간이 다음 리셋 시간 이후인 경우 (이상한 경우지만 방어 코드)
        // 예: 마지막 리셋이 내일 02:00인데 현재가 오늘 02:00 이후면 새 날
        if (lastResetTime >= nextResetTime) {
            val currentDayStart = getCurrentDayStart(customTime)
            return now >= currentDayStart && lastResetTime < currentDayStart
        }
        
        return false
    }

    /**
     * 사용자 지정 시간 기준으로 오늘 날짜 문자열을 반환합니다.
     * 예: 사용자가 02:00을 설정한 경우, 현재가 오늘 02:00 이후면 오늘 날짜, 이전이면 어제 날짜를 반환합니다.
     * 
     * @param customTime "HH:mm" 형식의 사용자 지정 시간 (예: "02:00")
     * @return "YYYY-MM-DD" 형식의 날짜 문자열
     */
    fun getDayString(customTime: String): String {
        val dayStart = getCurrentDayStart(customTime)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = dayStart
        
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH는 0부터 시작
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        
        return String.format("%04d-%02d-%02d", year, month, day)
    }
}
