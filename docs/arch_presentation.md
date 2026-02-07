# Presentation Layer 아키텍처

## 책임 (Responsibilities)

Presentation Layer는 사용자 인터페이스와 상호작용을 담당합니다. MVVM 패턴을 따르며, ViewModel을 통해 데이터를 관찰하고 UI를 반응형으로 업데이트합니다.

---

## 컴포넌트 상세

### 1. MainActivity

**파일**: [`app/src/main/java/com/faust/presentation/view/MainActivity.kt`](app/src/main/java/com/faust/presentation/view/MainActivity.kt)

- **책임**: ViewPager2로 Fragment 통합, 권한 요청, 서비스 제어
- **의존성**: 
  - `MainViewModel`, `CreditViewModel` (데이터 관찰 및 비즈니스 로직)
  - `AppBlockingService`, `TimeCreditBackgroundService` (서비스 제어)
  - `PreferenceManager` (페르소나 설정 관리)
  - `DailyResetService`, `WeeklyResetService` (일일/주간 초기화 스케줄링)
  - `AppGroupService` (앱 그룹 초기화)
- **UI 구조**:
  - ViewPager2를 사용하여 3개의 Fragment 통합 (MainFragment, CreditFragment, SettingsFragment)
  - TabLayout으로 탭 네비게이션 제공
- **경량화**: 데이터베이스 직접 접근 제거, ViewModel을 통한 간접 접근

### 1.1 MainFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/MainFragment.kt`](app/src/main/java/com/faust/presentation/view/MainFragment.kt)

- **책임**: 차단 앱 목록 및 TimeCredit 타이머(HH:MM:SS) 표시 (기존 MainActivity의 내용)
- **의존성**: 
  - `MainViewModel` (데이터 관찰 및 비즈니스 로직)
- **UI 업데이트**: 
  - ViewModel의 StateFlow를 관찰하여 UI 자동 업데이트
  - **타이머**: `viewModel.timerUiState` StateFlow 구독 (PreferenceManager TimeCredit 잔액 기반, Idle/Running/Syncing). 1초 틱은 `viewLifecycleOwner.lifecycleScope` + `repeatOnLifecycle(STARTED)` 내 while 루프로 화면 표시 중에만 갱신
  - 차단 앱 목록: `viewModel.blockedApps` StateFlow 구독
- **페르소나 선택 기능**:
  - `showPersonaDialog()`: PersonaSelectionDialog를 표시하여 페르소나 선택 또는 등록 해제
  - 선택된 페르소나는 PreferenceManager에 저장

### 2. MainViewModel

**파일**: [`app/src/main/java/com/faust/presentation/viewmodel/MainViewModel.kt`](app/src/main/java/com/faust/presentation/viewmodel/MainViewModel.kt)

- **책임**: 데이터 관찰 및 비즈니스 로직 처리
- **의존성**:
  - `FaustDatabase` (데이터 소스)
  - `PreferenceManager` (설정 데이터)
- **StateFlow 관리**:
  - `timerUiState: StateFlow<TimerUiState>` - TimeCredit 타이머 UI 상태 (Idle/Running/Syncing). 메인 카드 표시용. PreferenceManager.getTimeCreditBalanceFlow() 구독
  - `currentPoints: StateFlow<Int>` - 포인트 합계 (Zero-Deletion: 유지, DB Flow 구독)
  - `blockedApps: StateFlow<List<BlockedApp>>` - 차단 앱 목록
  - `transactions: StateFlow<List<PointTransaction>>` - 거래 내역 (포인트 정산 로그 포함)
- **주요 메서드**:
  - `addBlockedApp()`: 차단 앱 추가
  - `removeBlockedApp()`: 차단 앱 제거
  - `getMaxBlockedApps()`: 티어별 최대 앱 개수 반환
- **티어별 최대 차단 앱 개수**:
  - `FREE`: 1개
  - `STANDARD`: 3개
  - `FAUST_PRO`: 무제한 (Int.MAX_VALUE)
- **테스트 모드**: `PreferenceManager.setTestModeMaxApps(10)`으로 설정 시 모든 티어에서 최대 10개까지 차단 가능 (실제 휴대폰 테스트용)
  - 기본값: 테스트 모드 활성화 (최대 10개)
  - 비활성화: `setTestModeMaxApps(null)` 호출

### 3. GuiltyNegotiationOverlay

**파일**: [`app/src/main/java/com/faust/presentation/view/GuiltyNegotiationOverlay.kt`](app/src/main/java/com/faust/presentation/view/GuiltyNegotiationOverlay.kt)

- **책임**: 시스템 오버레이로 유죄 협상 화면 표시
- **특징**:
  - `WindowManager`를 사용한 시스템 레벨 오버레이
  - 30초 카운트다운 타이머
  - 강행/철회 버튼 제공
  - Persona Module 통합: 능동적 계약 방식 (사용자 입력 검증)
  - 페르소나별 피드백 (시각, 촉각, 청각)
  - Safety Net: 기기 상태에 따른 피드백 모드 자동 조정
- **성능 최적화**:
  - 하드웨어 가속 활성화: `WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED` 플래그 사용
  - `PixelFormat.TRANSLUCENT`로 알파 채널 렌더링 시 가속 지원
  - `dimAmount = 0.5f`로 배경 어둡게 처리 (하드웨어 가속 시 부드러운 렌더링)
  - 앱 전체 하드웨어 가속: `AndroidManifest.xml`의 `<application>` 태그에 `android:hardwareAccelerated="true"` 설정

### 4. CreditFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/CreditFragment.kt`](app/src/main/java/com/faust/presentation/view/CreditFragment.kt)

- **책임**: 시간 크레딧 잔액 및 사용자 타입 관리 UI 제공
- **의존성**:
  - `CreditViewModel` (데이터 관찰 및 크레딧 로직)
- **UI 구성**:
  - 크레딧 잔액 표시: 현재 보유한 시간 크레딧 수량
  - 사용자 타입 선택기: Light/Pro/Detox 선택
  - 쿨다운 상태 표시: 크레딧 사용 후 쿨다운 남은 시간
  - 크레딧 사용 안내: 크레딧 사용 방법 및 정산 정보
- **주요 기능**:
  - 크레딧 잔액 관찰: `viewModel.creditBalance` StateFlow 구독
  - 사용자 타입 변경: `viewModel.setUserType(userType)`
  - 쿨다운 상태 관찰: `viewModel.isInCooldown` StateFlow 구독

### 5. SettingsFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/SettingsFragment.kt`](app/src/main/java/com/faust/presentation/view/SettingsFragment.kt)

- **책임**: 사용자 지정 일일 리셋 시간 설정
- **의존성**:
  - `PreferenceManager` (사용자 지정 리셋 시간 저장/조회)
  - `DailyResetService` (알람 재스케줄링)
- **주요 기능**:
  - "나의 하루가 끝나는 시간" 설정
  - TimePickerDialog로 시간 선택 (HH:mm 형식)
  - 현재 설정된 시간 표시
  - 설정 변경 시 DailyResetService 알람 재스케줄링

### 6. CreditViewModel

**파일**: [`app/src/main/java/com/faust/presentation/viewmodel/CreditViewModel.kt`](app/src/main/java/com/faust/presentation/viewmodel/CreditViewModel.kt)

- **책임**: 크레딧 데이터 관찰 및 사용자 타입 관리
- **의존성**:
  - `PreferenceManager` (크레딧 잔액, 사용자 타입)
- **StateFlow 관리**:
  - `creditBalance: StateFlow<Int>` - 현재 크레딧 잔액
  - `userType: StateFlow<String>` - 사용자 타입 (Light, Pro, Detox)
  - (타임 크레딧 10분 쿨타임 제거로 쿨다운 StateFlow 제거됨)
- **주요 메서드**:
  - `setUserType(userType)`: 사용자 타입 변경
  - `observeCreditBalance()`: PreferenceManager에서 크레딧 잔액 관찰
  - `observeUserType()`: PreferenceManager에서 사용자 타입 관찰
  - (쿨다운 관찰 제거됨)

### 7. PersonaSelectionDialog

**파일**: [`app/src/main/java/com/faust/presentation/view/PersonaSelectionDialog.kt`](app/src/main/java/com/faust/presentation/view/PersonaSelectionDialog.kt)

- **책임**: 페르소나 선택 및 등록 해제 다이얼로그
- **기능**:
  - 모든 페르소나 타입(STREET, CALM, DIPLOMATIC, COMFORTABLE)을 리스트로 표시
  - 각 페르소나에 대한 설명 표시
  - 현재 선택된 페르소나 표시 (체크마크)
  - "등록 해제" 옵션 제공 (랜덤 텍스트 사용)
- **의존성**:
  - `PreferenceManager` (페르소나 타입 저장/조회)
- **주요 메서드**:
  - `onCreateDialog()`: 다이얼로그 생성 및 페르소나 리스트 표시
  - `PersonaAdapter`: 페르소나 리스트 어댑터

### 8. DisclosureDialogFragment

**파일**: [`app/src/main/java/com/faust/presentation/view/DisclosureDialogFragment.kt`](app/src/main/java/com/faust/presentation/view/DisclosureDialogFragment.kt)

- **책임**: 접근성 서비스 및 기기 관리자 권한 요청 전 명시적 고지 및 동의
- **기능**:
  - 구글 플레이 정책 준수: 접근성 API 사용 목적 및 데이터 수집 내용 명시
  - 명시적 동의 버튼 제공
  - 취소 버튼 제공
- **의존성**: 없음 (독립적인 다이얼로그)
- **주요 메서드**:
  - `onCreateDialog()`: 다이얼로그 생성 및 고지 내용 표시
- **사용 위치**:
  - `MainFragment`에서 엄격모드 활성화 버튼 클릭 시
  - 권한 요청 전에 반드시 표시

### 9. MainFragment 엄격모드 기능

**파일**: [`app/src/main/java/com/faust/presentation/view/MainFragment.kt`](app/src/main/java/com/faust/presentation/view/MainFragment.kt)

- **추가 기능**:
  - 엄격모드 활성화 버튼: 집중 시간 입력 다이얼로그 표시
  - 엄격모드 상태 표시: 활성/비활성, 남은 시간 표시
  - 비상구 버튼: 사용자 지정 대기 시간 후 해제 (TODO)
- **의존성**:
  - `StrictModeService` (엄격모드 상태 관리)
  - `StrictBlockService` (접근성 서비스 권한 확인)
  - `FaustAdminReceiver` (기기 관리자 권한 확인)
  - `DisclosureDialogFragment` (명시적 고지)
- **주요 메서드**:
  - `showStrictModeDialog()`: DisclosureDialogFragment 표시
  - `showDurationInputDialog()`: 집중 시간 입력 다이얼로그 표시
  - `checkAndRequestPermissions()`: 권한 확인 및 요청
  - `enableStrictMode()`: 엄격모드 활성화
  - `updateStrictModeStatus()`: 엄격모드 상태 업데이트
  - `showEmergencyExitDialog()`: 비상구 다이얼로그 표시

---

## MVVM 패턴 구현

### 데이터 흐름

```
UI Component (MainActivity)
    ↓
ViewModel (MainViewModel)
    ↓
Database Flow (getTotalPointsFlow, getAllBlockedApps)
    ↓
ViewModel StateFlow 업데이트
    ↓
UI Update (Reactive)
```

### StateFlow 관찰

- **포인트 업데이트**: `viewModel.currentPoints.collect { }`
- **차단 앱 목록 업데이트**: `viewModel.blockedApps.collect { }`
- **거래 내역 업데이트**: `viewModel.transactions.collect { }`

---

## 관련 문서

- [마스터 아키텍처 문서](../ARCHITECTURE.md)
- [도메인 레이어 아키텍처](./arch_domain_persona.md)
- [데이터 레이어 아키텍처](./arch_data.md)
- [이벤트 정의 문서](./arch_events.md)
