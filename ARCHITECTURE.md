# Faust 아키텍처 문서 (Master)

> **참고**: 이 문서는 Faust 프로젝트의 아키텍처 개요를 제공하는 마스터 문서입니다. 상세 내용은 각 모듈별 문서를 참조하세요.

## 목차

1. [전체 개요](#전체-개요)
2. [아키텍처 패턴](#아키텍처-패턴)
3. [레이어 구조](#레이어-구조)
4. [모듈별 상세 문서](#모듈별-상세-문서)
5. [서비스 아키텍처](#서비스-아키텍처)
6. [의존성 그래프](#의존성-그래프)
7. [데이터 흐름 요약](#데이터-흐름-요약)
8. [보안 및 권한](#보안-및-권한)
9. [확장성 고려사항](#확장성-고려사항)
10. [성능 최적화](#성능-최적화)
11. [테스트 전략](#테스트-전략)
12. [변경 이력](#변경-이력-architecture-change-log)

---

## 전체 개요

Faust는 **계층형 아키텍처(Layered Architecture)**를 기반으로 하며, 각 레이어는 명확한 책임을 가집니다.

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                    │
│  (UI Components, Activities, Fragments, Overlays)          │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│                   Service Layer                         │
│  (AppBlockingService, TimeCreditBackgroundService, etc.)        │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│                  Business Logic Layer                    │
│  (PenaltyService, WeeklyResetService)                   │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│                   Data Layer                            │
│  (Room Database, SharedPreferences, DAOs)               │
└──────────────────────────────────────────────────────────┘
```

---

## 아키텍처 패턴

### 1. 계층형 아키텍처 (Layered Architecture)
- **Presentation Layer**: UI 컴포넌트 및 사용자 인터랙션
- **Service Layer**: 백그라운드 서비스 및 앱 모니터링
- **Business Logic Layer**: 비즈니스 규칙 및 페널티 로직
- **Data Layer**: 데이터 영속성 및 저장소

### 2. MVVM 패턴 (Model-View-ViewModel)
- **View**: `MainActivity` - UI 렌더링 및 사용자 인터랙션
- **ViewModel**: `MainViewModel` - 데이터 관찰 및 비즈니스 로직
- **Model**: `FaustDatabase`, `PreferenceManager` - 데이터 소스
- StateFlow를 통한 반응형 UI 업데이트

### 3. Repository 패턴 (암묵적)
- DAO를 통한 데이터 접근 추상화
- PreferenceManager를 통한 설정 데이터 관리

### 4. Service-Oriented Architecture
- 독립적인 Foreground Service들
- 서비스 간 느슨한 결합

---

## 레이어 구조

### 📁 프로젝트 디렉토리 구조

```
com.faust/
│
├── 📱 Presentation Layer
│   └── presentation/
│       ├── view/
│       │   ├── MainActivity.kt                    # 메인 액티비티 (ViewPager2로 Fragment 통합)
│       │   ├── MainFragment.kt                    # 메인 Fragment (차단 앱 목록)
│       │   ├── CreditFragment.kt                   # 타임크레딧 Fragment (보상 시간 확인/사용자 타입 선택)
│       │   ├── SettingsFragment.kt                # 설정 Fragment (사용자 지정 리셋 시간)
│       │   ├── GuiltyNegotiationOverlay.kt        # 유죄 협상 오버레이
│       │   ├── BlockedAppAdapter.kt                # 차단 앱 리스트 어댑터
│       │   ├── AppSelectionDialog.kt              # 앱 선택 다이얼로그
│       │   └── PersonaSelectionDialog.kt           # 페르소나 선택 다이얼로그
│       └── viewmodel/
│           ├── MainViewModel.kt                  # 메인 ViewModel (MVVM)
│           └── CreditViewModel.kt                 # 타임크레딧 ViewModel (MVVM)
│
├── ⚙️ Service Layer
│   └── services/
│       ├── AppBlockingService.kt                  # 앱 차단 모니터링 서비스
│       └── TimeCreditBackgroundService.kt                  # 타임 크레딧 누적·차감 백그라운드 서비스
│
├── 🧠 Business Logic Layer (Domain)
│   └── domain/
│       ├── PenaltyService.kt                      # 페널티 계산 및 적용
│       ├── WeeklyResetService.kt                 # 주간 정산 로직
│       ├── DailyResetService.kt                  # 일일 초기화 로직 (사용자 지정 시간 지원)
│       ├── TimeCreditService.kt                  # 타임크레딧 정산/사용/Safeguards 로직
│       ├── TimeCreditGracePeriodReceiver.kt      # 타임크레딧 Grace Period 알림 수신
│       ├── AppGroupService.kt                    # 앱 그룹 관리 (SNS/OTT)
│       └── persona/                               # Persona Module (신규)
│           ├── PersonaType.kt                    # 페르소나 타입 Enum
│           ├── PersonaProfile.kt                  # 페르소나 프로필 데이터
│           ├── PersonaEngine.kt                  # 피드백 조율 엔진
│           ├── PersonaProvider.kt                 # 페르소나 설정 제공자
│           ├── FeedbackMode.kt                   # 피드백 모드 Enum
│           └── handlers/
│               ├── VisualHandler.kt              # 시각 피드백 핸들러
│               ├── HapticHandler.kt              # 촉각 피드백 핸들러
│               └── AudioHandler.kt               # 청각 피드백 핸들러
│
├── 💾 Data Layer
│   └── data/
│       ├── database/
│       │   ├── FaustDatabase.kt                  # Room 데이터베이스 (버전 4)
│       │   ├── AppBlockDao.kt                     # 차단 앱 DAO
│       │   ├── PointTransactionDao.kt             # 포인트 거래 DAO
│       │   └── AppGroupDao.kt                     # 앱 그룹 DAO
│       │
│       └── utils/
│           ├── PreferenceManager.kt               # EncryptedSharedPreferences 관리
│           └── TimeUtils.kt                       # 시간 계산 유틸리티 (사용자 지정 시간 지원)
│
├── 📦 Models
│   └── models/
│       ├── BlockedApp.kt                          # 차단 앱 엔티티
│       ├── PointTransaction.kt                    # 포인트 거래 엔티티
│       ├── UserTier.kt                            # 사용자 티어 enum
│       ├── UserCreditType.kt                      # 타임크레딧 사용자 프로필 enum
│       ├── AppGroup.kt                           # 앱 그룹 엔티티
│       └── AppGroupType.kt                       # 앱 그룹 타입 enum
│
└── 🚀 Application
    └── FaustApplication.kt                        # Application 클래스
```

---

## 모듈별 상세 문서

### 📱 [Presentation Layer](./docs/arch_presentation.md)

**책임**: UI 컴포넌트 및 사용자 인터랙션 처리

**주요 컴포넌트**:
- `MainActivity`: ViewPager2로 Fragment 통합 (MainFragment, CreditFragment, SettingsFragment)
- `MainFragment`: 차단 앱 목록 및 포인트 표시, 엄격모드 UI
- `CreditFragment`: 타임크레딧 잔액 확인, 사용자 프로필 선택, 쿨다운 상태 표시
- `SettingsFragment`: 사용자 지정 일일 리셋 시간 설정
- `MainViewModel`: 데이터 관찰 및 비즈니스 로직 (MVVM)
- `ShopViewModel`: 상점 데이터 관찰 및 구매/사용 로직 (MVVM)
- `GuiltyNegotiationOverlay`: 시스템 오버레이로 유죄 협상 화면 표시
- `PersonaSelectionDialog`: 페르소나 선택 및 등록 해제 다이얼로그
- `DisclosureDialogFragment`: 엄격모드 권한 요청 전 명시적 고지 및 동의

**핵심 특징**:
- StateFlow를 통한 반응형 UI 업데이트
- 데이터베이스 직접 접근 제거로 경량화
- Persona Module 통합: 능동적 계약 방식 (사용자 입력 검증)
- ViewPager2를 통한 탭 기반 네비게이션

→ [상세 문서 보기](./docs/arch_presentation.md)

---

### 🧠 [Domain Layer & Persona Module](./docs/arch_domain_persona.md)

**책임**: 비즈니스 로직과 페르소나 기반 피드백 제공

**주요 컴포넌트**:
- `PenaltyService`: 페널티 계산 및 적용
- `WeeklyResetService`: 주간 정산 로직
- `DailyResetService`: 일일 초기화 로직 (사용자 지정 시간 지원)
- `TimeCreditService`: 타임크레딧 정산(Batch Settlement), 사용(Credit Session), Safeguards(Cool-down/Grace Period) 관리
- `TimeCreditGracePeriodReceiver`: Grace Period 알림 수신 (보상 시간 종료 5분/1분 전)
- `AppGroupService`: 앱 그룹 관리 (SNS/OTT 앱 분류, 향후 카테고리별 차등 비율 확장 가능)
- `StrictModeService`: 엄격모드 상태 관리 및 타이머 로직 (AlarmManager 사용)
- `StrictModeExpiredReceiver`: 엄격모드 만료 시 자동 해제 처리
- `PersonaEngine`: 피드백 조율 엔진 (Safety Net 로직 포함)
- `PersonaProvider`: 페르소나 프로필 제공 (랜덤 텍스트 지원)
- `VisualHandler`, `HapticHandler`, `AudioHandler`: 각 피드백 실행

**핵심 특징**:
- 기기 상태 기반 피드백 모드 자동 조정 (Safety Net)
- 능동적 계약 방식: 사용자가 정확히 문구를 입력해야 강행 버튼 활성화
- 페르소나별 맞춤형 피드백 (시각, 촉각, 청각)
- 사용자 지정 시간 기준 일일 초기화
- WorkManager를 통한 백그라운드 타이머 관리

→ [상세 문서 보기](./docs/arch_domain_persona.md)

---

### 💾 [Data Layer](./docs/arch_data.md)

**책임**: 데이터 영속성과 저장소 관리

**주요 컴포넌트**:
- `FaustDatabase`: Room 데이터베이스 (버전 4, BlockedApp, PointTransaction, AppGroup, BlockedDomain 엔티티)
- `PointTransactionDao`: 포인트 거래 DAO (Flow 제공)
- `AppBlockDao`: 차단 앱 DAO
- `AppGroupDao`: 앱 그룹 DAO (Flow 제공)
- `PreferenceManager`: EncryptedSharedPreferences 관리 (AES256-GCM 암호화)
- `TimeUtils`: 시간 계산 유틸리티 (사용자 지정 시간 기준 날짜 계산)

**핵심 특징**:
- 단일 소스 원칙: 포인트는 `PointTransaction`의 `SUM(amount)`로 계산
- 트랜잭션 보장: 모든 포인트 변경 작업이 원자적으로 처리
- 보안 강화: EncryptedSharedPreferences로 포인트 조작 방지
- 반응형 데이터: Flow를 통한 자동 UI 업데이트
- 사용자 지정 시간 지원: 사용자가 설정한 시간 기준으로 "하루" 정의

→ [상세 문서 보기](./docs/arch_data.md)

---

### ⚡ [핵심 이벤트 정의 및 시퀀스 다이어그램](./docs/arch_events.md)

**책임**: 비즈니스 로직을 트리거하는 주요 이벤트와 시스템 컴포넌트 간 상호작용 설명

**주요 내용**:
- 앱 차단 플로우 (Event-driven)
- 절제 시간 누적 및 앱 진입 시 settleCredits 플로우
- Persona 피드백 플로우
- 화면 OFF/ON 감지 및 도주 패널티 플로우
- 주간 정산 플로우
- 일일 초기화 플로우 (사용자 지정 시간 기준)
- 타임크레딧 정산/사용 플로우 (Batch Settlement + Credit Session)
- 상태 전이 모델 (ALLOWED ↔ BLOCKED)

**핵심 특징**:
- 이벤트 기반 감지: AccessibilityService를 통한 실시간 앱 실행 감지
- 오디오 모니터링: AudioPlaybackCallback을 통한 이벤트 기반 오디오 감지
- 상태 전이 시스템: 오버레이 중복 발동 방지

→ [상세 문서 보기](./docs/arch_events.md)

---

## 서비스 아키텍처

### 핵심 이벤트 진입점 (4개 + 전처리/정책/액션)

시스템 이벤트는 **4가지 진입점**으로만 수신하며, 각각 **전처리 → 정책 → 액션** 순서로 처리한다.

| 진입점 | 전처리 | 정책 | 액션 |
|--------|--------|------|------|
| (1) 앱 포그라운드 변경 | 디바운스, Window ID 필터 | 차단 여부, Grace Period, 쿨다운 | transitionToState, 오버레이, Credit Session |
| (2) 화면 OFF | 단일 수신처 일원화 | 도주·오디오 후보 | TCB 정산, ABS 콜백(도주/오디오) |
| (3) 화면 ON | Doze 무시 | 재진입 잔액 분기 | 정산, screenOnSettlementDoneCallback |
| (4) 오디오 재생 상태 변경 | Persona 제외 | 차단 앱 오디오 여부 | transitionToState(BLOCKED/ALLOWED) |

상세 플로우 및 시퀀스는 [docs/arch_events.md](docs/arch_events.md) § 주요 진입점 4개 참조.

### 서비스 간 관계도

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  • 서비스 시작/중지 제어                          │   │
│  │  • 권한 요청                                      │   │
│  │  • UI 업데이트                                    │   │
│  └──────────────────────────────────────────────────┘   │
└───────────────┬───────────────────┬─────────────────────┘
                │                   │
    ┌───────────▼──────────┐  ┌────▼──────────────────┐
    │ AppBlockingService    │  │ TimeCreditBackgroundService   │
    │ (AccessibilityService)│  │                      │
    │                       │  │ • 앱 사용 시간 추적  │
    │ • 이벤트 기반 감지     │  │ • 포인트 자동 적립    │
    │ • 오버레이 트리거     │  │                      │
    │ • 엄격모드 설정 차단   │  │                      │
    └───────────┬──────────┘  └────┬──────────────────┘
                │                   │
                │                   │
    ┌───────────▼───────────────────▼──────────┐
    │         PenaltyService                   │
    │  • 강행/철회 페널티 계산 및 적용          │
    └───────────┬──────────────────────────────┘
                │
    ┌───────────▼──────────────────────────────┐
    │      WeeklyResetService                  │
    │  • AlarmManager로 주간 정산 스케줄링      │
    │  • 포인트 몰수 로직                       │
    └──────────────────────────────────────────┘
                │
    ┌───────────▼──────────────────────────────┐
    │      StrictModeService                   │
    │  • AlarmManager로 엄격모드 타이머 관리     │
    │  • 자동 해제 스케줄링                     │
    └──────────────────────────────────────────┘
```

### 서비스 생명주기

```
앱 시작
  │
  ├─► MainActivity.onCreate()
  │     │
  │     ├─► 권한 확인
  │     │     │
  │     │     ├─► 접근성 서비스 권한
  │     │     └─► Overlay 권한
  │     │
  │     └─► 서비스 시작
  │           │
  │           ├─► AppBlockingService (시스템 자동 시작)
  │           │     └─► 이벤트 기반 감지 (TYPE_WINDOW_STATE_CHANGED)
  │           │
  │           └─► TimeCreditBackgroundService.startForeground()
  │                 └─► 주기적 포인트 계산
  │
  └─► WeeklyResetService.scheduleWeeklyReset()
        └─► AlarmManager에 등록
```

---

## 의존성 그래프

```
MainActivity
  ├─► MainViewModel
  ├─► AppBlockingService
  ├─► TimeCreditBackgroundService
  ├─► WeeklyResetService
  └─► PreferenceManager

MainViewModel
  ├─► FaustDatabase
  └─► PreferenceManager

PersonaSelectionDialog
  └─► PreferenceManager

AppBlockingService
  ├─► FaustDatabase
  ├─► GuiltyNegotiationOverlay
  ├─► PenaltyService
  └─► TimeCreditBackgroundService (pauseMining/resumeMining)

TimeCreditBackgroundService
  ├─► FaustDatabase
  └─► PreferenceManager

GuiltyNegotiationOverlay
  ├─► PenaltyService
  └─► PersonaEngine (신규)
      ├─► PersonaProvider
      │   └─► PreferenceManager
      ├─► VisualHandler
      ├─► HapticHandler
      │   └─► Vibrator (시스템)
      └─► AudioHandler
          ├─► MediaPlayer
          └─► AudioManager (시스템)

PenaltyService
  ├─► FaustDatabase
  └─► PreferenceManager

WeeklyResetService
  ├─► FaustDatabase
  └─► PreferenceManager
```

---

## 데이터 흐름 요약

### 읽기 흐름 (Read Flow)
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

### 쓰기 흐름 (Write Flow)
```
User Action / Service Event
    ↓
Business Logic (withTransaction)
    ↓
PointTransaction 삽입
    ↓
현재 포인트 계산 (SUM)
    ↓
PreferenceManager 동기화 (호환성, 암호화 저장)
    ↓
트랜잭션 커밋 (예외 처리 및 롤백 보장)
    ↓
Database Flow 자동 업데이트
    ↓
ViewModel StateFlow 업데이트
    ↓
UI 반응형 업데이트
```

---

## 보안 및 권한

### 필수 권한
1. **BIND_ACCESSIBILITY_SERVICE**: 접근성 서비스를 통한 앱 실행 감지 및 엄격모드 설정 페이지 차단
2. **SYSTEM_ALERT_WINDOW**: 오버레이 표시
3. **FOREGROUND_SERVICE**: 백그라운드 서비스 실행 (TimeCreditBackgroundService 타임 크레딧용)
4. **QUERY_ALL_PACKAGES**: 설치된 앱 목록 조회
5. **BIND_DEVICE_ADMIN**: 엄격모드 중 앱 삭제 방지 (기기 관리자)

### 보안 강화
1. **EncryptedSharedPreferences**: 포인트 데이터 암호화 저장
   - AES256-GCM 암호화
   - MasterKey 기반 키 관리
   - 포인트 조작 방지
2. **트랜잭션 예외 처리**: 모든 DB 트랜잭션에 예외 처리 및 롤백 보장
3. **동시성 보장**: 모든 포인트 수정 로직이 트랜잭션으로 처리되어 동시 접근 시 데이터 무결성 보장

### 권한 요청 플로우
```
MainActivity
  ↓
권한 확인
  ↓
├─► 접근성 서비스 권한 확인
│     ↓
│     [없음] → 접근성 설정 화면으로 이동
│     ↓
│     [있음] → 다음 권한 확인
│
└─► 오버레이 권한 확인
      ↓
      [없음] → 오버레이 권한 설정 화면으로 이동
      ↓
      [있음] → 서비스 시작
```

**참고**: 접근성 서비스는 시스템이 자동으로 시작하므로 별도의 서비스 시작 호출이 필요 없습니다.

---

## 확장성 고려사항

### 향후 추가 가능한 레이어
1. **Repository Layer**: 데이터 소스 추상화
2. **UseCase Layer**: 비즈니스 로직 캡슐화
3. **Dependency Injection**: Dagger/Hilt 도입
4. **추가 ViewModel**: 다른 화면에 대한 ViewModel 확장

### 확장 포인트
- Standard/Faust Pro 티어 로직
- **타임크레딧 시스템(TimeCreditSystem)**: CBT 기반 수반성 관리, 절제 시간에 비례한 보상 크레딧
  - **정산(Batch Settlement)**: 앱 진입 시 누적 절제 시간 기반 크레딧 일괄 정산
  - **Credit Session**: 차단 앱 접근 시 세션 시작, 매분 크레딧 차감 (TimeCreditBackgroundService 틱 루프 활용)
  - **Safeguards**: Grace Period(종료 5분/1분 전 알림)
  - **사용자 프로필**: Light(4:1), Pro(6:1), Detox(10:1)
  - **시네마 패스**: 75 WP, 재구매 쿨타임 18시간, 지속 시간 4시간 (OTT 앱 그룹 차단 해제)
- **Persona Module 확장**:
  - 새로운 페르소나 타입 추가 (PersonaType Enum 확장)
  - 새로운 핸들러 추가 (인터페이스 구현 후 PersonaEngine에 주입)
  - 오디오 파일 추가 (res/raw에 파일 추가 후 PersonaProfile 업데이트)
- 다차원 분석 프레임워크 (NDA)

---

## 성능 최적화

### 현재 구현
- **이벤트 기반 감지**: `AppBlockingService`가 `AccessibilityService`를 활용하여 앱 실행 이벤트를 실시간 감지
- **메모리 캐싱**: 차단된 앱 목록을 `HashSet`으로 캐싱하여 DB 조회 제거
- **Flow 구독**: 변경사항만 감지하여 불필요한 업데이트 방지
- **반응형 UI**: Room Database의 Flow를 통한 반응형 데이터 업데이트
- **비동기 처리**: Coroutine을 사용한 비동기 처리
- **백그라운드 작업**: AccessibilityService로 시스템 레벨 이벤트 감지

### 최적화 상세

#### AppBlockingService 최적화
- **이전**: Polling 방식 (1초마다 `queryUsageStats()` 호출)
- **현재**: 
  - **이벤트 기반 감지**: `AccessibilityService`의 `TYPE_WINDOW_STATE_CHANGED` 이벤트 활용
  - 서비스 시작 시 1회만 DB 로드
  - `getAllBlockedApps()` Flow 구독으로 변경사항만 감지
  - 메모리 캐시 (`ConcurrentHashMap.newKeySet<String>()`)에서 조회
  - **Polling 루프 완전 제거**
- **효과**: 
  - 배터리 소모 대폭 감소 (이벤트 발생 시에만 처리)
  - 실시간 감지 (앱 실행 즉시 감지)
  - 시스템 리소스 사용 최소화

#### MainActivity UI 최적화
- **이전**: `while(true)` 루프로 5초마다 포인트 업데이트
- **현재**: 
  - `MainViewModel`의 StateFlow를 관찰
  - 포인트 및 차단 앱 목록 변경 시에만 UI 업데이트
  - 데이터베이스 직접 접근 제거로 경량화
- **효과**: 배터리 효율 향상, 불필요한 UI 갱신 제거, 코드 분리로 유지보수성 향상

#### GuiltyNegotiationOverlay 하드웨어 가속 최적화
- **목적**: 오버레이 렌더링 성능 향상 및 리플 애니메이션 부드러운 동작 보장
- **구현**:
  - `WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED` 플래그 추가
  - `PixelFormat.TRANSLUCENT` 유지 (알파 채널 렌더링 시 가속 지원)
  - `dimAmount = 0.5f` 설정 (하드웨어 가속 시 부드러운 배경 어둡게 처리)
  - `AndroidManifest.xml`의 `<application>` 태그에 `android:hardwareAccelerated="true"` 명시
- **효과**: 
  - "non-hardware accelerated Canvas" 경고 제거
  - 버튼 클릭 시 리플 애니메이션 부드럽게 동작
  - 오버레이 UI 반응 속도 향상
  - GPU 가속을 통한 렌더링 성능 개선

### 개선 가능 영역
- 데이터베이스 인덱싱
- 메모리 누수 방지 (Lifecycle-aware 컴포넌트)
- TimeCreditBackgroundService는 이미 이벤트 기반(화면 ON/OFF, 오디오 콜백)으로 동작

---

## 테스트 전략

### 단위 테스트 대상
- `PenaltyService`: 페널티 계산 로직
- `WeeklyResetService`: 정산 로직
- `TimeUtils`: 시간 계산 유틸리티
- `PreferenceManager`: 데이터 저장/로드
- `PersonaEngine`: 피드백 모드 결정 로직 (Safety Net)
- `PersonaProvider`: 페르소나 프로필 제공
- `VisualHandler`: 입력 검증 로직
- `HapticHandler`: 진동 패턴 실행
- `AudioHandler`: 오디오 재생 및 헤드셋 감지

### 통합 테스트 대상
- 서비스 간 통신
- 데이터베이스 CRUD 작업
- 권한 요청 플로우
- Persona Module 통합:
  - 오버레이 표시 시 피드백 실행
  - 사용자 입력 검증 및 버튼 활성화
  - 버튼 클릭 시 피드백 정지
  - 헤드셋 탈착 시 피드백 모드 전환
  - Safety Net 로직 (무음 모드, 헤드셋 연결 상태)

---

## 변경 이력 (Architecture Change Log)

### [2026-02-08] 진입점 축소 리팩터링 (완료)
- **목표**: 시스템 이벤트를 4개 진입점으로 고정하고, 전처리 → 정책 → 액션 순서로만 처리하도록 코드·문서 정리.
- **컴포넌트 영향**: AppBlockingService (진입점 1: shouldEmitAppForegroundEvent, evaluateBlockingIntent, applyBlockingIntent; 화면 OFF 리시버 제거, getScreenOffAudioCandidatePackage·onScreenOffEventImmediate·onScreenOffEventAudioResolved), TimeCreditBackgroundService (화면 OFF/ON 단일 수신, setScreenOffEventCallback·setScreenOffAudioCandidateProvider; evaluateCreditAction·CreditAction; handleZoneTransition 반환값으로 락 밖에서 notifyCreditExhausted), docs/arch_events.md, ARCHITECTURE.md.
- **변경 사항**: (1) 진입점 4개·전처리/정책/액션 문서화. (2) 앱 포그라운드: 전처리 모듈화, 정책(evaluateBlockingIntent)·액션(applyBlockingIntent) 분리. (3) 화면 OFF/ON: TCB만 수신, ABS는 콜백(즉시·200ms 오디오 확정). (4) 오디오: 진입점 4 문서/주석. (5) 크레딧: evaluateCreditAction 일원화, notifyCreditExhausted 단일 경로, syncState 락 해제 후 소진 콜백. (6) 문서 매핑·이력 갱신.

### [2026-02-07] Final Enforcement Logic: Dynamic Branching on Screen ON
- **작업**: Credit 0 & Screen OFF 시 오디오 킬 후 채굴 재개 시점 기록, Screen ON 시 동기 정산 후 잔액 기준으로 포그라운드 재진입 분기(오버레이 vs startCreditSession).
- **컴포넌트 영향**:
  - **PreferenceManager**: `lastMiningResumeElapsedRealtime` get/set 추가 (Audio Kill 시점 기록).
  - **TimeCreditBackgroundService**: `notifyCreditExhausted()`에서 `!isScreenOn`일 때 Mining 재개 시점 기록; `calculateAccumulatedAbstention()`에서 해당 구간 우선 사용; Screen ON 시 `runBlocking { calculateAccumulatedAbstention(); settleCredits() }` 후 `screenOnSettlementDoneCallback` 호출.
  - **AppBlockingService**: `onScreenOnSettlementDone()` 추가 — BLOCKED + 차단 앱 포그라운드 시 balance ≤ 0이면 showOverlay(Strict Punishment), balance > 0이면 startCreditSession + transitionToState(ALLOWED).
- **변경 사항**:
  - Background: Credit 0 & Screen OFF → Kill Audio → Start Mining(재개 시점 저장).
  - Foreground Re-Entry: Screen ON 후 정산 완료 콜백에서 잔액 체크 → 0 이하면 오버레이, 양수면 세션 시작·재개.
  - Logic Sync: Audio Kill~Screen ON 구간을 절제로 정산하여 잔액을 재진입 분기 전에 갱신.
- **영향 범위**: 화면 껐다 켤 때 차단 앱이 포그라운드인 경우 잔액에 따라 일관된 징벌/보상 동작. 기존 Grace Period, Cool-down, 상태 전이 유지.

### [2024-12-XX] 유죄 협상 오버레이 즉시 호출 변경
- **작업**: 차단된 앱 실행 시 유죄 협상 오버레이를 즉시 표시하도록 변경
- **컴포넌트 영향**: `AppBlockingService.transitionToState()`
- **변경 사항**:
  - `DELAY_BEFORE_OVERLAY_MS` 상수 제거 (기존: 4-6초 지연)
  - `overlayDelayJob` 변수 및 관련 로직 제거
  - `transitionToState()` 메서드에서 오버레이를 즉시 표시하도록 수정
- **영향 범위**:
  - 차단된 앱 실행 시 사용자 경험 개선 (즉각적인 피드백)
  - 기존 로직 보존: Grace Period, Cool-down, 중복 방지 메커니즘 유지

### [2026-01-15] 화면 OFF 시 차단 앱 오디오 재생 상태 기록 및 채굴 재개 방지
- **작업**: 화면을 끌 때 차단 앱에서 음성이 출력되면 채굴을 중지하고, 이 기록을 보관하여 화면을 켤 때 허용된 앱으로 변경되어도 채굴을 재개하지 않도록 구현
- **컴포넌트 영향**: 
  - `PreferenceManager`: 화면 OFF 시 차단 앱 오디오 재생 상태 저장/조회 메서드 추가
  - `TimeCreditBackgroundService`: `isPausedByAudio()` companion 메서드 추가, 오디오 종료 시 플래그 리셋
  - `AppBlockingService`: 화면 OFF 시 상태 확인 및 저장, ALLOWED 전이 시 조건부 재개
- **변경 사항**:
  - `PreferenceManager`에 `wasAudioBlockedOnScreenOff()`, `setAudioBlockedOnScreenOff()` 메서드 추가
  - `TimeCreditBackgroundService`에 `isPausedByAudio()` companion 메서드 추가
  - `AppBlockingService.registerScreenOffReceiver()`에서 화면 OFF 시 `isPausedByAudio` 상태 확인 및 저장
  - `AppBlockingService.transitionToState()`에서 ALLOWED 전이 시 저장된 상태 확인 후 조건부 재개
  - `TimeCreditBackgroundService.checkBlockedAppAudioFromConfigs()`에서 오디오 종료 시 플래그 리셋
- **영향 범위**:
  - 화면 OFF 시 차단 앱 오디오 재생 중이면 채굴 중지 상태를 기록
  - 화면 ON 후 허용 앱으로 전환되어도 오디오가 종료될 때까지 채굴 재개하지 않음
  - 오디오 종료 시 자동으로 플래그가 리셋되어 정상적으로 채굴 재개

### [2026-01-XX] 페르소나 관리 UI 구현
- **작업**: UI에 페르소나 선택 창을 추가하고, 등록된 페르소나를 사용하며, 등록되지 않았을 때는 모든 페르소나의 프롬프트 텍스트 중 랜덤으로 출력하도록 구현
- **컴포넌트 영향**:
  - `PersonaSelectionDialog`: 페르소나 선택 다이얼로그 신규 생성
  - `PreferenceManager`: `getPersonaTypeString()` 기본값을 빈 문자열로 변경
  - `PersonaProvider`: 빈 문자열 처리 및 `createRandomProfile()` 메서드 추가
  - `MainActivity`: 페르소나 선택 버튼 및 `showPersonaDialog()` 메서드 추가
  - `activity_main.xml`: 페르소나 선택 버튼 추가
  - `strings.xml`: 페르소나 관련 문자열 리소스 추가
- **변경 사항**:
  - `PersonaSelectionDialog.kt` 신규 생성: 모든 페르소나 타입 표시 및 등록 해제 옵션 제공
  - `PreferenceManager.getPersonaTypeString()`: 기본값을 `"STREET"`에서 `""` (빈 문자열)로 변경
  - `PersonaProvider.getPersonaType()`: 빈 문자열일 때 `null` 반환하도록 수정
  - `PersonaProvider.getPersonaProfile()`: `getPersonaType()`가 `null`일 때 `createRandomProfile()` 호출
  - `PersonaProvider.createRandomProfile()`: 모든 페르소나의 프롬프트 텍스트 중 랜덤 선택
  - `MainActivity`: `buttonPersona` 변수 추가, `showPersonaDialog()` 메서드 추가
  - `activity_main.xml`: 페르소나 선택 버튼 추가
  - `strings.xml`: 페르소나 선택 관련 문자열 리소스 추가
- **영향 범위**:
  - 사용자가 페르소나를 선택하거나 등록 해제할 수 있는 UI 제공
  - 등록되지 않은 경우 모든 페르소나의 프롬프트 텍스트 중 랜덤으로 선택하여 출력
  - 기존 페르소나 로직 보존: 등록된 페르소나는 기존과 동일하게 동작

### [2026-01-16] PersonaProvider 컴파일 오류 수정
- **작업**: `getPersonaType()` 메서드의 변수 스코프 문제로 인한 컴파일 오류 수정
- **컴포넌트 영향**: `PersonaProvider.getPersonaType()`
- **변경 사항**:
  - `typeName` 변수를 `try` 블록 밖으로 이동하여 `catch` 블록에서 접근 가능하도록 수정
  - 변수 스코프 문제 해결로 "Unresolved reference: typeName" 컴파일 오류 해결
- **영향 범위**:
  - 컴파일 오류 해결로 빌드 성공
  - 기존 로직 보존: 예외 처리 및 로깅 기능 유지

### [2026-01-XX] 유죄협상 오버레이 중복 호출 방지
- **작업**: `TYPE_WINDOW_STATE_CHANGED` 이벤트가 반복 발생하여 유죄협상 오버레이가 중복 호출되는 문제 해결
- **컴포넌트 영향**: `AppBlockingService.handleAppLaunch()`
- **변경 사항**:
  - 중복 호출 방지 메커니즘 추가: `lastHandledPackage`, `lastHandledTime`, `HANDLE_APP_LAUNCH_DEBOUNCE_MS` (500ms)
  - `handleAppLaunch()` 시작 부분에 디바운스 로직 추가: 500ms 내 같은 패키지에 대한 중복 호출 차단
  - 마지막 처리 정보를 업데이트하여 중복 호출 방지
- **영향 범위**:
  - `TYPE_WINDOW_STATE_CHANGED` 이벤트가 반복 발생해도 같은 패키지는 500ms 내 한 번만 처리
  - 오버레이 중복 표시 문제 해결
  - 기존 로직 보존: Cool-down, Grace Period, 상태 전이 시스템 유지

### [2026-01-16] PersonaEngine 오디오 재생으로 인한 유죄협상 반복 호출 방지
- **작업**: PersonaEngine의 AudioHandler가 재생하는 오디오가 오디오 검사 로직에 의해 차단 앱 오디오로 잘못 감지되어 유죄협상이 반복 호출되는 문제 해결
- **컴포넌트 영향**: 
  - `AppBlockingService`: 오버레이 표시 상태 추적을 위한 companion object static 변수 추가
  - `TimeCreditBackgroundService`: 오버레이 표시 중일 때 오디오 검사 건너뛰기
- **변경 사항**:
  - `AppBlockingService` companion object에 `isOverlayActive` static 변수 추가 및 `isOverlayActive()` 메서드 추가
  - `AppBlockingService.showOverlay()`에서 오버레이 표시 시 `isOverlayActive = true` 설정
  - `AppBlockingService.hideOverlay()`에서 오버레이 닫힐 때 `isOverlayActive = false` 설정
  - `TimeCreditBackgroundService.checkBlockedAppAudioFromConfigs()`에서 오버레이 표시 중이면 오디오 검사 건너뛰기
- **영향 범위**:
  - PersonaEngine의 AudioHandler가 재생하는 오디오가 오디오 검사에 의해 감지되지 않음
  - 유죄협상 오버레이 표시 중 PersonaEngine 오디오 재생으로 인한 반복 호출 문제 해결
  - 기존 오디오 검사 로직 보존: 차단 앱 오디오 감지 기능은 정상 작동

### [2026-01-XX] PersonaEngine 오디오 정지 타이밍 안전성 개선
- **작업**: 오버레이가 닫힐 때 PersonaEngine 오디오가 완전히 정지된 후 오디오 검사를 재개하도록 개선
- **컴포넌트 영향**: 
  - `AppBlockingService.hideOverlay()`: PersonaEngine 오디오 정지 완료 대기 로직 추가
- **변경 사항**:
  - `DELAY_AFTER_PERSONA_AUDIO_STOP_MS` 상수 추가 (150ms)
  - `hideOverlay()`에서 `dismiss()` 호출 후 150ms 지연 후 `isOverlayActive = false` 설정
  - 시스템 오디오 콜백의 지연을 고려한 안전 지연
- **영향 범위**:
  - 오버레이가 닫힌 직후 발생하는 오디오 콜백에서 PersonaEngine 오디오가 차단 앱 오디오로 오인식되는 것을 방지
  - 오디오 검사 재개 시점이 PersonaEngine 오디오 정지 완료 후로 보장됨
  - 사용자 경험에 미치는 영향 최소화 (150ms 지연)

### [2026-01-XX] 오디오 모니터링 이벤트 기반 명확화
- **작업**: 아키텍처 문서에서 "10초마다" 주기적 검사 설명 제거, 오디오 상태 변경 시 한 번만 검사한다는 것을 명확히 설명
- **컴포넌트 영향**: 
  - `ARCHITECTURE.md`: 오디오 모니터링 설명 수정
- **변경 사항**:
  - "10초마다" 주기적 검사 설명 제거
  - 오디오 상태 변경 시 한 번만 검사한다는 것을 명확히 설명
  - 검사 결과를 저장하여 절제 시간 누적 여부를 결정한다는 것을 명확히 설명
  - 이벤트 처리 로직 설명 강화
- **영향 범위**:
  - 아키텍처 문서의 정확성 향상
  - 기존 코드 로직은 이미 이벤트 기반으로 구현되어 있음 (변경 없음)

### [2026-01-XX] Grace Period 중복 징벌 방지 강화
- **작업**: 강행 버튼 클릭 후 화면 OFF → ON 시나리오에서 유죄협상이 다시 발생하는 중복 징벌 문제 해결
- **컴포넌트 영향**: 
  - `AppBlockingService.handleAppLaunch()`: Grace Period 우선 체크 추가
- **변경 사항**:
  - `handleAppLaunch()`에서 차단 앱 감지 후 Grace Period 체크를 Cool-down 체크보다 먼저 수행
  - `lastAllowedPackage`가 설정되어 있고 현재 패키지와 일치하면 오버레이 표시 차단
  - `transitionToState(BLOCKED, ..., triggerOverlay = false)` 호출하여 채굴은 중단하되 오버레이는 표시하지 않음
  - 화면 재개 시나리오를 포함한 모든 경로에서 중복 징벌 방지
- **영향 범위**:
  - 강행 버튼 클릭 후 화면 OFF → ON 시나리오에서 중복 징벌 방지
  - Grace Period 활성화 시 오버레이만 표시하지 않으며, 채굴 중단 및 상태 전이는 정상 작동
  - 다른 검사 로직(Cool-down, 오디오 차단 등) 및 절제 시간 누적 재개 로직에 영향 없음

### [2026-01-XX] 철회 버튼 클릭 후 빠른 앱 재실행 시 유죄협상 미호출 문제 해결
- **작업**: 철회 버튼 클릭 후 빠르게 차단 앱을 실행했을 때 유죄협상이 호출되지 않는 현상 해결
- **원인 분석**:
  - 참조 불일치: `hideOverlay()`가 비동기로 실행되어 `currentOverlay = null` 설정이 지연됨
  - 재생성 방해: `handleAppLaunch()`에서 `currentOverlay != null`만 체크하여 닫는 중 상태를 고려하지 않음
  - 이벤트 지연: 비동기 작업 완료 전 재실행 시 타이밍 이슈 발생
- **컴포넌트 영향**: 
  - `AppBlockingService.hideOverlay()`: 참조 백업 후 즉시 상태 동기화
  - `AppBlockingService.handleAppLaunch()`: `isOverlayDismissing` 체크 추가
- **변경 사항**:
  - `hideOverlay()`에서 오버레이 참조를 백업한 후 즉시 `currentOverlay = null`, `isOverlayDismissing = true` 설정
  - 비동기 블록에서 백업한 참조로 `dismiss()` 호출하여 리소스 정리 보장
  - `handleAppLaunch()`에서 `currentOverlay != null || isOverlayDismissing` 체크로 `showOverlay()`와 동일한 조건 통일
  - 상태 동기화를 즉시 수행하여 빠른 재실행 시나리오에서도 정상 작동
- **영향 범위**:
  - 철회 버튼 클릭 후 빠르게 앱을 재실행해도 유죄협상이 정상 호출됨
  - PersonaEngine 오디오 정지 로직 보존: `isOverlayActive`는 기존 로직대로 PersonaEngine 오디오 정지 완료 후 해제
  - 리소스 정리 보장: 백업한 참조로 `dismiss()` 정상 호출하여 메모리 누수 방지
  - 기존 로직 보존: Cool-down, Grace Period, 중복 방지 메커니즘 모두 유지

### [2026-01-XX] Cool-down 및 중복 호출 방지 로직 최적화
- **작업**: 철회 버튼 클릭 후 빠른 앱 재실행 시 Cool-down과 중복 호출 방지 로직에 의해 오버레이가 차단되는 문제 해결
- **원인 분석**:
  - Cool-down 로직: 철회 버튼 클릭 시 `navigateToHome()`에서 1초 쿨다운 설정하여 의도적 재실행도 차단
  - 중복 호출 방지 로직: 500ms 내 동일 패키지 재호출 차단으로 빠른 재실행 시나리오에서 문제 발생
  - 체크 순서 문제: 중복 호출 방지가 먼저 실행되어 중요한 체크(Grace Period, Cool-down)보다 우선 적용
- **컴포넌트 영향**: 
  - `AppBlockingService.navigateToHome()`: `applyCooldown` 파라미터 추가
  - `AppBlockingService.hideOverlay()`: `applyCooldown` 파라미터 추가
  - `GuiltyNegotiationOverlay.onCancel()`: `applyCooldown=false` 전달
  - `AppBlockingService.handleAppLaunch()`: 체크 순서 재배치 및 중복 호출 방지 시간 단축
- **변경 사항**:
  - `navigateToHome()`에 `applyCooldown` 파라미터 추가 (기본값: true)
  - `hideOverlay()`에 `applyCooldown` 파라미터 추가 (기본값: true)
  - `GuiltyNegotiationOverlay.onCancel()`에서 `hideOverlay(shouldGoHome=true, applyCooldown=false)` 호출
  - 철회 버튼 클릭 시 쿨다운 면제하여 의도적 재실행 허용
  - `HANDLE_APP_LAUNCH_DEBOUNCE_MS`를 500ms → 200ms로 단축
  - `handleAppLaunch()` 체크 순서 재배치: 오버레이 상태 → Grace Period → Cool-down → 중복 호출 방지
  - 중요한 체크를 먼저 수행하고, 중복 호출 방지는 마지막에 적용하여 빠른 재실행 허용
- **영향 범위**:
  - 철회 버튼 클릭 후 빠르게 앱을 재실행해도 쿨다운 없이 즉시 오버레이 표시
  - 화면 OFF 도주 감지 시에는 기존대로 쿨다운 적용 (의도적 재실행이 아님)
  - 시스템 이벤트 중복은 여전히 차단 (200ms 내 중복 호출 방지)
  - 실수로 다시 실행하는 경우는 쿨다운으로 보호 (자연스러운 홈 이동 시)
  - 기존 로직 보존: Grace Period, Cool-down(자연스러운 홈 이동 시), 중복 방지 메커니즘 모두 유지

### [2026-02-07] 철회 직후 유죄협상 중복 표시 방지 (400ms 동일 패키지 억제)
- **작업**: 철회 버튼 클릭 후 유죄협상 오버레이가 한 번 더 뜨는 현상 해결
- **원인**: `hideOverlay()`에서 `lastWindowId`/`lastProcessedPackage` 리셋 후 스테일 이벤트가 Flow로 전달되어 ALLOWED 강제 전이 후 다시 오버레이 표시
- **컴포넌트 영향**: `AppBlockingService`
- **변경 사항**:
  - `lastWithdrawnPackage`, `lastWithdrawnTime` 추가 (철회 시점·패키지 기록, `@Volatile`)
  - `hideOverlay(shouldGoHome=true, applyCooldown=false)` 호출 시 해당 패키지·타임스탬프 설정
  - `onAccessibilityEvent()` 필터링 4.5: 동일 패키지이고 경과 < 400ms(`WITHDRAW_SUPPRESS_MS`)면 Flow 전송 억제
  - 홈 런처 감지 시 `lastWithdrawnPackage = null`로 클리어하여 재실행 시 유죄협상 보장
- **영향 범위**:
  - 철회 직후 스테일 이벤트로 인한 유죄협상 중복 표시 방지
  - 차단 앱 반복 실행(실행→철회→재실행) 시 우회 방지 유지 (400ms 이후·홈 감지 후 정상 표시)

### [2026-01-XX] Window ID 검사 + 상태 머신 패턴으로 중복 호출 문제 근본 해결
- **작업**: 시스템 이벤트 중복과 사용자 의도적 재실행을 구분할 수 없는 근본 문제 해결
- **원인 분석**:
  - 시간 기반 디바운싱의 한계: 시스템 이벤트 중복과 사용자 의도적 재실행을 구분 불가
  - 상태 동기화 문제: `isOverlayDismissing` 플래그로 인한 빠른 재실행 차단
  - 참조 불일치: 오버레이 닫힘 완료 시점 불명확
- **컴포넌트 영향**: 
  - `AppBlockingService.onAccessibilityEvent()`: Window ID 검사 + 코루틴 Throttling 추가
  - `AppBlockingService`: `OverlayState` enum 추가 (상태 머신 패턴)
  - `AppBlockingService.showOverlay()`: 상태 머신 기반으로 수정
  - `AppBlockingService.hideOverlay()`: 상태 머신 기반으로 수정
  - `GuiltyNegotiationOverlay`: `OverlayDismissCallback` 인터페이스 추가
- **변경 사항**:
  - **Window ID 검사**: `event.windowId`로 실제 창 전환만 감지 (화면 내부 변화 제외)
  - **클래스 이름 검증**: `event.className`으로 Activity/Dialog만 처리 (Toast, Notification 제외)
  - **코루틴 Throttling**: 300ms 지연으로 연속 이벤트를 마지막 것만 처리
  - **상태 머신 패턴**: `OverlayState` enum (IDLE, SHOWING, DISMISSING)으로 오버레이 생명주기 관리
  - **콜백 패턴 강화**: `OverlayDismissCallback`으로 오버레이 닫힘 완료 시점 명확화
  - **상태 전이 제어**: DISMISSING 상태일 때는 어떤 앱 실행 이벤트도 무시
  - **예외 처리 강화**: try-catch-finally로 상태 머신 데드락 방지
- **영향 범위**:
  - 시스템 이벤트 중복 완전 차단: Window ID로 실제 창 전환만 감지
  - 빠른 재실행 시나리오 정상 작동: 상태 머신으로 DISMISSING 중 재생성 차단
  - 상태 동기화 문제 해결: 상태 머신으로 명확한 상태 전이 관리
  - 철회 후 즉시 재실행 정상 작동: DISMISSING → IDLE 전이 후 즉시 허용
  - 기존 로직 보존: Grace Period, Cool-down, PersonaEngine 오디오 정지 로직 모두 유지
  - 시간 기반 중복 호출 방지 로직 제거: Window ID + Throttling이 주 방어선

### [2026-01-XX] Window ID 기억 리셋 및 특별 처리로 재진입 차단 문제 해결
- **작업**: Window ID가 -1(UNDEFINED)인 경우 오버레이 닫힘 후에도 재진입이 차단되는 문제 해결
- **원인 분석**:
  - Window ID 기억 유지: 오버레이가 닫혀도 `lastWindowId`와 `lastProcessedPackage`가 리셋되지 않음
  - Window ID -1 문제: 일부 앱(유튜브 등)이 Window ID를 -1로 보고하여 같은 패키지 재실행 시 계속 무시됨
  - className 필터 엄격: `FrameLayout` 등 일반 뷰로 이벤트 발생 시 무시되어 실제 창 전환도 처리되지 않음
- **컴포넌트 영향**: 
  - `AppBlockingService.onAccessibilityEvent()`: Window ID -1 특별 처리 및 className 필터 완화
  - `AppBlockingService.hideOverlay()`: Window ID 기억 리셋 추가
- **변경 사항**:
  - **Window ID 기억 리셋**: `hideOverlay()`에서 오버레이 닫힘 완료 시 `lastWindowId=-1`, `lastProcessedPackage=null`로 리셋
  - **Window ID -1 특별 처리**: Window ID가 -1인 경우 오버레이 상태를 확인하여 IDLE 상태면 처리 허용
  - **className 필터 완화**: Window ID가 유효한 경우 `Layout` 클래스도 허용 (FrameLayout, LinearLayout 등)
  - **조건부 필터링**: Window ID가 -1이면 Activity/Dialog만 허용 (더 엄격), 유효하면 Layout도 허용 (더 관대)
- **영향 범위**:
  - Window ID -1 앱에서도 오버레이 닫힘 후 재진입 정상 작동
  - FrameLayout 등 일반 뷰로 이벤트 발생하는 앱에서도 오버레이 정상 표시
  - 오버레이 닫힘 시 Window ID 기억이 리셋되어 빠른 재실행 시나리오 정상 작동
  - 기존 로직 보존: Window ID 검사, Throttling, 상태 머신 패턴 모두 유지

### [2026-02-07] Credit 소진 시 강제 미디어 정지 (Forced Media Termination on Credit Exhaustion)
- **작업**: 잔액 0 + 화면 OFF + 오디오 차단 시 배경 미디어 강제 정지 및 사용자 피드백.
- **트리거**: Time Credit Balance == 0 AND isScreenOFF == true AND audioBlocked == true.
- **변경 요약**:
  - **PenaltyService**: `executeForcedMediaTermination(packageName)` 추가 — 오디오 포커스 요청(AUDIOFOCUS_GAIN) 후 해제, ACTION_MEDIA_BUTTON + KEYCODE_MEDIA_PAUSE 브로드캐스트, 3회 짧은 진동(80ms), D/PenaltyService 로그.
  - **TimeCreditBackgroundService**: `notifyCreditExhausted()` 내에서 `!isScreenOn && isPausedByAudio`일 때 PenaltyService.executeForcedMediaTermination() 호출.
- **영향 컴포넌트**: PenaltyService, TimeCreditBackgroundService. 문서: docs/arch_events.md §D.5, docs/arch_domain_persona.md.

### [2026-02-07] Credit Session 차감: 세션 구간 일괄 차감(Session Duration) 방식 전환
- **작업**: 주기적(60초) 차감 제거 → 세션 시작~종료 구간을 한 번에 계산하여 일괄 차감. 1:1 비율 유지, 최소 차감 임계값 없음.
- **변경 요약**:
  - **Start**: `TimeCreditService.startCreditSession()`에서 `startTime = System.currentTimeMillis()`, `creditAtSessionStartSeconds` 저장. `lastKnownAliveSessionTime` 0으로 초기화.
  - **실시간 모니터링**: `TimeCreditBackgroundService`의 **Session Monitor** (30초 주기). `(currentTime - startTime) / 1000 >= creditAtSessionStartSeconds`이면 즉시 차단(notifyCreditExhausted).
  - **종료 시 일괄 차감**: `performFinalDeductionOnSessionEnd()`에서 `totalSeconds = (endTime - startTime) / 1000`, `actualDeduct = min(totalSeconds, balance)` 한 번만 차감.
  - **Last Known Alive**: 30초마다 `setLastKnownAliveSessionTime(now)`. 프로세스 비정상 종료 시 복구: `tryRecoverSessionIfKilled()`에서 `(lastKnownAlive - startTime)/1000` 차감 후 세션 종료.
- **제거/조정**: 틱 루프 내 세션 중 주기 차감 제거. 화면 OFF 시 세션 중 차감 제거(종료 시 일괄만). `calculateAccumulatedAbstention()`에서 세션 활성 시 OFF 구간 부분 차감 제거.
- **영향 컴포넌트**: PreferenceManager(creditAtSessionStart, lastKnownAlive), TimeCreditService, TimeCreditBackgroundService(sessionMonitorJob, tryRecoverSessionIfKilled).

### [2026-02-07] Adaptive Monitoring (60s threshold), syncState(), 96s race fix (Dormant)
- **작업**: 잔여 크레딧 기반 적응형 감시(Smart Credit Governor) 도입. 96초 잔액에서 오디오 중단 감지 시 조기 세션 종료되던 레이스 컨디션 제거.
- **핵심 로직**: `syncState()` 통합 정산 — 사용량 계산(calculateUsageSinceLastSync) → 잔액 갱신 → 구간 판정 → 알람/틱 제어. 구간: 안전(C>60) = 1초 틱 중지, (C-60)초 후 정밀 전환 알람; 위험(0<C≤60) = 1초 정밀 감시 + Grace Period "1분 후 종료" 알림 1회; 소진(C≤0) = notifyCreditExhausted().
- **96초 버그**: 골든 타임 루프에서 "오디오 중단" 시 C==0 우선 검사. C>0이면 세션 종료 금지, 휴면(Dormant) 진입(1초 틱 중지, lastSync 갱신, 정밀 전환 알람 예약). 알람 수신 시 syncState()로 재판정.
- **알람**: ACTION_PRECISION_TRANSITION, cancel 후 FLAG_UPDATE_CURRENT로 예약. Grace Period 알림은 세션 시작 시 스케줄 제거, 위험 구간 첫 진입 시 1회만 발송.
- **영향 컴포넌트**: TimeCreditBackgroundService(syncStateLock, syncState, calculateUsageSinceLastSync, cancel/schedulePrecisionTransitionAlarm, startGoldenTimeJob Dormant 분기), TimeCreditService(ACTION_PRECISION_TRANSITION, setTimeCreditLastSyncTime at session start), AppBlockingService(onCreditSessionStarted 호출), PreferenceManager(getTimeCreditLastSyncTime/setTimeCreditLastSyncTime 기존 사용).

### [2026-02-07] Credit Mining(이중 정산) 방지
- **작업**: Credit Session 활성 중 절제 시간이 누적되어 "사용 중 보상"이 발생하는 논리적 결함 제거.
- **문제**: TimeCreditBgService·PointMiningService가 세션 여부와 무관하게 절제 시간을 누적하여, 차단 앱 사용(크레딧 차감)과 동시에 절제 보상이 쌓이는 이중 정산 가능.
- **수정 사항**:
  - **세션 시작 시 미정산 무효화**: `TimeCreditService.startCreditSession()`에서 세션 활성화 직후 `setAccumulatedAbstentionSeconds(0L)` 호출. 사용 시작 전까지 쌓인 "미정산 절제 시간"은 보상으로 인정하지 않음.
  - **틱 루프 이중 가드**: `TimeCreditBackgroundService` 틱 루프의 절제 누적 분기에서 `!isCreditSessionActive()` 재확인 및 로그 추가 (이중 정산 방지).
  - **화면 ON 절제 누적 가드**: `PointMiningService.calculateAccumulatedPoints()`에서 Credit Session 활성 시 절제 시간 누적 스킵.
- **영향 컴포넌트**: TimeCreditService, TimeCreditBackgroundService, PointMiningService. 공용 상태는 PreferenceManager(isCreditSessionActive) 유지.

### [2026-02-07] Credit Session 최종 차감 및 1:1 비율 보장
- **작업**: Credit Session 종료 시 잔액이 차감되지 않는 버그 수정, 초과 차감 방지, 중복 차감 경로 제거.
- **근본 원인 1**: `endCreditSession()`에 최종 차감 로직 부재. 60초 미만 세션은 Tick이 도달하기 전에 종료되어 잔액 무차감.
- **근본 원인 2**: 모든 차감 경로가 `lastTickElapsedRealtime` 기준으로 계산하여, 세션 시작 전 시간까지 차감에 포함 (1.6초 사용 → 32초 차감).
- **수정 원칙**: `min(마지막Tick이후, 세션시작이후)` — 1초 사용 = 1초 차감 (1:1 비율).
- **컴포넌트 영향**:
  - `TimeCreditBackgroundService`: `performFinalDeductionOnSessionEnd()` 추가 + `performFinalDeduction()` companion 메서드 추가
  - `TimeCreditBackgroundService`: 모든 차감 경로에 `sessionStartElapsedRealtime` 하한 적용 (performFinalDeductionOnSessionEnd, performFinalDeductionOnScreenOff, startTickJob 틱 루프)
  - `AppBlockingService`: `endCreditSession()` 호출 직전 `TimeCreditBackgroundService.performFinalDeduction()` 호출 추가
  - `TimeCreditService.startCreditSession()`: 세션 이미 활성 시 재시작 방지 가드 추가 (`sessionStartElapsedRealtime` 리셋 방지)
  - `PointMiningService`: 중복 크레딧 차감 로직 제거 (TimeCreditBackgroundService에 위임), 포인트 적립 스킵만 유지
- **영향 범위**: Credit Session 차감 1:1 비율 보장, 60초 미만 세션에서도 정확한 잔액 차감, 초과 차감 방지

### [2026-02-07] Remainder Retention and Deferred Persist (나머지 유지·지연 저장)
- **작업**: 정산 시 나머지(ratio로 나누어떨어지지 않는 초) 유지 + 초 단위 저장 시 디스크 부하 감소.
- **Remainder retention**: `settleCredits()`에서 `remainderSeconds = totalSeconds % userRatio`로 계산 후 `setAccumulatedAbstentionSeconds(remainderSeconds)` 호출. 예: 59초 절제/비율 4 → 14초 보상, 3초 나머지 유지; 이후 1초 추가 시 4초가 되어 1초 추가 보상.
- **Deferred persist**: PreferenceManager에 잔액·누적값의 in-memory 캐시 도입. `setTimeCreditBalanceSeconds`/`setAccumulatedAbstentionSeconds`는 캐시+Flow만 갱신. `persistTimeCreditValues()` 호출 시에만 디스크 저장. 호출 시점: (1) 화면 OFF 시 (TimeCreditBackgroundService), (2) 틱 루프 1분 간격, (3) MainActivity.onDestroy(), (4) TimeCreditBackgroundService.onDestroy().

### [2026-02-07] Second-Precision Time Credit Settlement (초 단위 정산)
- **작업**: Time Credit 시스템을 분(Minutes) 단위에서 초(Seconds) 단위로 전환하여 reward loss 방지 (예: 1분 절제 / ratio 4 = 0 → 60초 / 4 = 15초 크레딧).
- **원칙**: Zero-Deletion(기존 로직 유지), 마이그레이션(기존 분 키 → 초 키 일회 변환), High Precision(누적·잔액·차감 모두 초 단위).
- **컴포넌트 영향**:
  - **Data Layer**: PreferenceManager에 `KEY_TIME_CREDIT_BALANCE_SECONDS`, `KEY_ACCUMULATED_ABSTENTION_SECONDS` 추가. `getTimeCreditBalanceSeconds()`/`setTimeCreditBalanceSeconds()`, `getAccumulatedAbstentionSeconds()`/`setAccumulatedAbstentionSeconds()`. `getTimeCreditBalanceFlow()`: Flow&lt;Long&gt;. 기존 분 키 존재 시 첫 조회 시 초로 마이그레이션.
  - **Domain Layer**: TimeCreditService.settleCredits() → earnedSeconds = totalAbstentionSeconds/ratio, 잔액·누적 모두 초. applyPenaltyMinutes(minutes) → 내부적으로 분×60 초 차감. SettlementResult/UseResult 필드 초 단위.
  - **Service Layer**: TimeCreditBackgroundService 틱 루프 60초마다 60초 누적/차감. calculateAccumulatedAbstention() → elapsedRealtime 차이를 초로 계산하여 누적/차감. performFinalDeductionOnScreenOff() → 경과 초 단위 차감. scheduleExhaustionTimer(balanceSeconds: Long).
  - **Presentation Layer**: MainViewModel TimerUiState.Idle(balanceSeconds), getTimeCreditBalanceFlow() 구독 Long. formatBalanceAsHhMmSs(balanceSeconds). CreditViewModel/CreditFragment 잔액·누적 StateFlow&lt;Long&gt;, HH:MM:SS 포맷.
  - **PenaltyService**: 잔액 조회/저장을 getTimeCreditBalanceSeconds/setTimeCreditBalanceSeconds 사용, 패널티 분×60 초 차감.
- **성공 기준**: 1분 절제 시 ratio 4에서 "15초 earned" 로그, 메인 타이머 초 단위 표시, 앱 재시작/화면 이벤트 후에도 부분 분 손실 없음.

### [2026-02-07] 코드 정합성 검토 기반 수정 (기술적 보완 6항목 + 정교화 4+3항목 포함)
- **searchBlockedContentInTree** recycle 삼중 방어: Max Depth(>=50) + finally try/catch(Throwable) + 이중 recycle 제거
- **BroadcastReceiver** goAsync 8초 준수 + applicationScope + NonCancellable 분리(후처리만) + 롤백 보장 + 정산 중복 쿨다운(60초)
- **PointMiningService** 물리적 격리: 파일명 `PointMiningService_DEPRECATED.kt`, 가시성 하향, `@Deprecated(ERROR)`
- **OverlayState** ACTIVE/DISMISSING 추가 + companion 단일 `_overlayState` + `isOverlayActive` 제거 + `isOverlayBlockingEvents()`/`isPersonaAudioPossiblyPlaying()` + onDestroy Ghost Overlay 버그 수정(동기 dismiss 후 scope 취소)
- **onProceed()** PenaltyService 경유 통일 + 중복 호출 방지 가드
- **PreferenceManager** 싱글톤 + `getInstance(context)` 정적 메서드
- **persistTimeCreditValues** `synchronous` 파라미터 + commit() 재시도 + 복구 플래그(KEY_PERSIST_DIRTY) + 비상 파일 덤프(tc_emergency_dump.txt) + 앱 시작 시 복구 로직

### [2026-02-07] 메인 화면 TimeCredit 타이머 UI 전환
- **작업**: 메인 화면 상단 "포인트(WP)" 표시를 제거하고, PreferenceManager TimeCredit 잔액 기반 HH:MM:SS 실시간 타이머로 교체
- **원칙**: Zero-Deletion(포인트/DB 로직 유지), Reactivity(SharedFlow), Precision(단조 시간·하이브리드 저장)
- **컴포넌트 영향**:
  - **Data Layer**: PreferenceManager에 `getTimeCreditBalanceFlow()` (SharedFlow&lt;Int&gt; replay=1) 추가, `setTimeCreditBalance()` 시 emit
  - **Domain Layer**: TimeCreditService 세션 시작/종료 시 `sessionStartElapsedRealtime` (SystemClock.elapsedRealtime()) 런타임 변수 설정
  - **Presentation Layer**: MainViewModel에 `TimerUiState`(Idle/Running/Syncing), `timerUiState` StateFlow, `getRemainingSecondsForDisplay()`, Sync Safeguard(5초), `formatTimeAsHhMmSs`(Locale.US) 추가. MainFragment는 `timerUiState` 구독, `repeatOnLifecycle(STARTED)` 내 1초 틱, textCurrentPoints에 Monospace 적용
  - **리소스**: `time_credit_timer` 문자열(국문/영문), fragment_main.xml textCurrentPoints fontFamily monospace
- **데이터 흐름**: 메인 카드 표시는 PointTransactionDao 대신 PreferenceManager.getTimeCreditBalanceFlow() 구독. 포인트 관련 DB/로직은 삭제 없이 유지.

### [2026-02-06] FreePass → TimeCreditSystem 전환
- **작업**: FreePass 시스템(도파민 샷, 스탠다드 티켓, 시네마 패스) 완전 제거, 절제 시간 비례 보상형 TimeCreditSystem으로 전환
- **핵심 철학**: CBT 기반 수반성 관리, 배터리 효율을 위한 Batch Settlement, 깜짝 선물 방식
- **컴포넌트 영향**:
  - 데이터 레이어: FreePassItem, DailyUsageRecord 엔티티/DAO 제거, FaustDatabase v3→v4 마이그레이션
  - 도메인 레이어: FreePassService, ActivePassService 삭제, TimeCreditService, TimeCreditGracePeriodReceiver 추가
  - 프레젠테이션 레이어: ShopFragment/ShopViewModel 삭제, CreditFragment/CreditViewModel 추가
  - 서비스 레이어: AppBlockingService FreePass 체크 → TimeCredit/CreditSession 체크로 교체
  - TimeCreditBackgroundService: 절제 시간 누적(C-1), Credit Session 차감(C-2) 로직 추가
  - DailyResetService: FreePass 트랜잭션 블록 제거, TimeCredit 확장점 확보(C-3)
  - PreferenceManager: FreePass 키/메서드 제거, TimeCredit/CreditSession/Cooldown/GracePeriod 키/메서드 추가
  - MainActivity: onResume() TimeCredit 정산, WorkManager PassExpirationWorker 정리(M-2)
- **영향 범위**:
  - 사용자가 절제한 시간에 비례하여 보상 크레딧을 획득하고, 차단 앱 접근 시 자동으로 사용
  - 종료 임박 시 Grace Period 알림
  - 유죄협상은 크레딧 없을 경우 즉시 실행

### [2026-01-XX] 프리 패스 시스템 구현 (→ 2026-02-06 TimeCreditSystem으로 대체됨)
- **상태**: ~~Deprecated~~ → TimeCreditSystem으로 완전 대체됨
- **원본 작업**: 도파민 샷, 스탠다드 티켓, 시네마 패스 구매 및 사용 시스템 구현

### [2026-01-XX] 상태 전이 즉시화로 빠른 재실행 문제 근본 해결
- **작업**: 오버레이 닫힘 후 빠른 재실행 시 상태 전이 지연과 Throttling 지연의 누적으로 인한 차단 문제 해결
- **원인 분석**:
  - 상태 전이 지연: `hideOverlay()`에서 `overlayState = IDLE` 전환이 비동기 블록의 여러 delay 후에 발생 (총 약 400ms)
  - Throttling 지연: `onAccessibilityEvent()`에서 300ms 지연으로 연속 이벤트 처리
  - 누적 차단 시간: 최대 약 700ms 동안 재진입 차단
  - className 필터 엄격: Window ID가 -1일 때 Layout을 제외하여 FrameLayout 이벤트 무시
- **컴포넌트 영향**: 
  - `AppBlockingService.hideOverlay()`: 상태 전이 즉시화 및 Window ID 기억 즉시 리셋
  - `AppBlockingService.onAccessibilityEvent()`: className 필터 완화 (Layout 항상 허용)
- **변경 사항**:
  - **상태 전이 즉시화**: `hideOverlay()`에서 `overlayState = IDLE`을 즉시 설정 (비동기 작업 완료 대기하지 않음)
  - **Window ID 기억 즉시 리셋**: `lastWindowId=-1`, `lastProcessedPackage=null`을 즉시 리셋하여 재진입 허용
  - **중복 호출 체크 제거**: `overlayState == DISMISSING` 체크 제거 (즉시 IDLE로 전환하므로 불필요)
  - **className 필터 완화**: Layout을 항상 허용 (Window ID와 무관하게 FrameLayout, LinearLayout 등 처리)
  - **리소스 정리 분리**: 오버레이 닫기 로직은 비동기 블록에서 리소스 정리만 수행 (상태는 이미 IDLE)
- **영향 범위**:
  - 오버레이 닫힘 후 즉시 재진입 허용 (상태 전이 지연 제거)
  - FrameLayout 이벤트 정상 처리 (유튜브 등에서 정상 작동)
  - 빠른 재실행 시나리오에서도 정상 작동 (Throttling 지연만 존재, 상태 전이 지연 없음)
  - 기존 로직 보존: PersonaEngine 오디오 정지 로직, 홈 이동 지연 로직 모두 유지
  - 리소스 정리 보장: 백업한 참조로 `dismiss()` 호출하여 리소스 정리 보장

### [2026-01-29] 엄격모드(Strict Mode) 기능 구현
- **작업**: 사용자가 설정한 집중 시간 동안 앱 삭제와 접근성 서비스 해제를 기술적으로 제한하는 기능 구현
- **컴포넌트 영향**:
  - **Domain Layer**: `StrictModeService`, `StrictModeExpiredReceiver` 신규 생성
  - **Service Layer**: `FaustAdminReceiver` 신규 생성, `AppBlockingService`에 엄격모드 차단 로직 통합
  - **Presentation Layer**: `DisclosureDialogFragment` 신규 생성, `MainFragment` UI 추가
  - **Data Layer**: `PreferenceManager` 엄격모드 관련 메서드 추가
- **변경 사항**:
  - `StrictModeService`: 엄격모드 상태 관리 및 AlarmManager를 통한 자동 해제 스케줄링
  - `StrictModeExpiredReceiver`: 엄격모드 만료 시 자동 해제 처리
  - `AppBlockingService`: 엄격모드 활성 시 설정 페이지 접근 차단 로직 통합 (기존 앱 차단 로직과 함께)
  - `FaustAdminReceiver`: DeviceAdminReceiver로 앱 삭제 방지
  - `DisclosureDialogFragment`: 구글 플레이 정책 준수를 위한 명시적 고지 및 동의
  - `MainFragment`: 엄격모드 활성화 버튼, 상태 표시, 비상구 버튼 추가
  - `PreferenceManager`: 엄격모드 상태 저장/조회 메서드 추가
  - `AndroidManifest.xml`: 새로운 리시버 등록 (접근성 서비스는 AppBlockingService 하나만 사용)
  - `accessibility_service_config.xml`: 이벤트 타입 및 권한 업데이트 (typeWindowContentChanged 추가, canRetrieveWindowContent=true)
  - `device_admin.xml`: XML 리소스 파일 추가
- **영향 범위**:
  - 사용자가 집중 시간 동안 앱 삭제와 설정 변경을 제한할 수 있음
  - 구글 플레이 정책 준수: 명시적 고지, 타이머 기반 자동 해제, 비상구 제공
  - 기존 로직 보존: Zero-deletion Policy 준수, 모든 기존 서비스 및 도메인 로직 유지
  - 접근성 서비스 통합: 두 개의 서비스를 하나로 통합하여 사용자 혼란 방지 및 구글 플레이 정책 최적화

### [2026-01-29] 구간별 차등 비상구 구현
- **작업**: 엄격모드 비상구를 남은 시간에 따라 차등 보상을 제공하는 시스템으로 구현
- **컴포넌트 영향**:
  - **Domain Layer**: `StrictModeService.processEmergencyExit()` 신규 추가
  - **Data Layer**: `PreferenceManager`에 비상구 쿨타임 관련 메서드 추가
  - **Presentation Layer**: `MainFragment.showEmergencyExitDialog()` 로직 변경
- **변경 사항**:
  - 구간별 처리: 5분 이하 즉시 해제, 10분/15분/20분 구간별 시간 단축(각 5분) 및 쿨타임 적용
  - 20분 이상: 절반 단축 + 점진적 쿨타임 (5-20분)
  - AlarmManager 재예약으로 종료 시각 동기화
  - 쿨타임 관리로 비상구 남용 방지
  - `EmergencyExitResult` 데이터 클래스 추가
  - `PreferenceManager`에 `getEmergencyExitLastClickTime()`, `setEmergencyExitLastClickTime()`, `getEmergencyExitCooldownMinutes()`, `setEmergencyExitCooldownMinutes()` 메서드 추가
  - `MainFragment`에 쿨타임 체크 로직 추가 (버튼 클릭 시점 및 다이얼로그 내부 이중 체크)
  - `strings.xml`에 구간별 메시지 문자열 추가
- **영향 범위**:
  - 사용자가 남은 시간에 따라 다른 보상을 받는 구간별 차등 비상구 시스템 제공
  - 게임 이론 기반의 동기 부여 시스템으로 사용자 참여도 향상
  - 쿨타임 관리로 비상구 남용 방지
  - 기존 로직 보존: Zero-deletion Policy 준수, 모든 기존 메서드 및 로직 유지

### [2026-01-29] 접근성 서비스 통합 (StrictBlockService → AppBlockingService)
- **작업**: 두 개의 AccessibilityService를 하나로 통합하여 사용자 혼란 방지 및 구글 플레이 정책 최적화
- **컴포넌트 영향**:
  - **Service Layer**: `StrictBlockService` 삭제, `AppBlockingService`에 엄격모드 차단 로직 통합
  - **Presentation Layer**: `MainFragment`에서 `StrictBlockService` 참조 제거
- **변경 사항**:
  - `AppBlockingService.onAccessibilityEvent()`: 엄격모드 활성 시 설정 페이지 차단 로직 추가
  - `AppBlockingService`: `isStrictModeActive()`, `containsBlockedContent()` 메서드 추가
  - `accessibility_service_config.xml`: `typeWindowContentChanged` 이벤트 타입 추가, `canRetrieveWindowContent="true"` 설정
  - `strings.xml`: `accessibility_service_description` 업데이트 (앱 차단 + 엄격모드 보호 명시)
  - `AndroidManifest.xml`: `StrictBlockService` 등록 제거
  - `MainFragment`: `StrictBlockService` 참조를 `AppBlockingService`로 변경
  - `strict_block_service_config.xml`: 삭제
- **영향 범위**:
  - 사용자가 하나의 접근성 서비스만 활성화하면 됨 (사용자 혼란 방지)
  - 구글 플레이 정책 준수: 단일 목적 원칙 준수 (하나의 서비스가 두 가지 목적 수행 명시)
  - 기존 로직 보존: 모든 기존 앱 차단 로직 유지, 엄격모드 차단 로직만 추가

### [2026-01-XX] 홈 런처 감지로 홈 이동 후 상태 동기화 보장 (강제 상태 초기화 제거)
- **작업**: 홈으로 이동할 때 홈 런처 패키지를 감지하여 상태를 `ALLOWED`로 전이하도록 변경 (강제 상태 초기화 제거)
- **원인 분석**:
  - 강제 상태 초기화의 타이밍 문제: `navigateToHome()` 직후 즉시 상태 전이로 인해 홈 이동이 완료되기 전에 유튜브 앱이 여전히 활성 상태
  - 중복 호출: 홈 이동 완료 전에 유튜브 앱이 다시 감지되어 `ALLOWED → BLOCKED` 전이 및 오버레이 재표시
  - 홈 런처 패키지 불확실성: 런처마다 패키지명이 다르고, 이벤트가 발생하지 않을 수 있음
- **컴포넌트 영향**: 
  - `AppBlockingService.hideOverlay()`: 강제 상태 초기화 로직 제거
  - `AppBlockingService`: 홈 런처 패키지 목록 초기화 및 감지 로직 추가
  - `AppBlockingService.handleAppLaunch()`: 홈 런처 감지 로직 추가
- **변경 사항**:
  - **강제 상태 초기화 제거**: `hideOverlay()`에서 `navigateToHome()` 호출 직후 상태 전이 제거
  - **홈 런처 패키지 초기화**: `initializeHomeLauncherPackages()` 추가 - `PackageManager.queryIntentActivities()`로 `CATEGORY_HOME` Intent를 처리할 수 있는 모든 앱을 찾아 저장
  - **홈 런처 이벤트 필터링 우회**: `onAccessibilityEvent()`에서 홈 런처 패키지는 className 필터링을 우회하여 Flow로 전송 보장 (런처마다 className이 다를 수 있음)
  - **홈 런처 감지**: `handleAppLaunch()`에서 홈 런처 패키지가 감지되면 상태를 `ALLOWED`로 전이
  - 홈 화면 이벤트가 실제로 발생했을 때만 상태를 전이하여 중복 호출 방지
  - 홈 화면 이벤트가 지연되거나 누락되어도 홈 런처 감지로 상태가 올바르게 유지됨
- **영향 범위**:
  - 홈 이동 후 홈 런처가 실제로 감지되었을 때만 상태가 `ALLOWED`로 전이되어 중복 호출 방지
  - 홈 이동 완료 전 유튜브 앱 재감지 문제 해결
  - 이벤트 지연/누락 시나리오에서도 홈 런처 감지로 상태 일관성 유지
  - 기존 로직 보존: `transitionToState()`의 중복 전이 방지 및 오디오 상태 확인 로직 모두 유지
- **추가 수정 (2026-01-XX)**: 같은 제한 앱 반복 실행 시나리오 대응
  - **문제**: 같은 제한 앱을 반복 실행(a실행 → 철회 → a실행 → 철회 → a실행) 시 Window ID 중복 체크로 이벤트가 Flow로 전송되지 않아 유죄협상이 호출되지 않음
  - **해결**: Window ID 중복 체크 완화 - 오버레이가 닫힌 후(IDLE)에는 같은 앱 재실행 허용
  - **방어막 유지**: 기존 방어막(Grace Period, Cool-down)이 `handleAppLaunch()`에서 정상 작동하여 중복 호출 방지
  - **결과**: 짧은 시간 내 반복 실행해도 유죄협상이 정상 호출됨 (철회 시 `applyCooldown=false`로 쿨다운 면제)
- **추가 수정 (2026-01-XX)**: 홈 런처에서 다른 앱으로 빠르게 전환하는 경우 불일치 체크 우회
  - **문제**: 홈 이동 후 빠르게 제한 앱 실행 시 `latestActivePackage` 불일치로 이벤트가 무시됨
  - **해결**: 홈 런처에서 다른 앱으로 전환하는 경우는 불일치 체크를 우회하여 제한 앱 이벤트 처리 보장
- **추가 수정 (2026-01-18)**: 쿨다운 변수 스레드 안전성 및 정합성 개선
  - **문제**: 철회 버튼 클릭 후 같은 차단 앱 재실행 시 오버레이가 표시되지 않음
    - 쿨다운 변수(`lastHomeNavigationPackage`, `lastHomeNavigationTime`)가 `@Volatile` 없이 선언되어 스레드 안전성 문제
    - `applyCooldown=false`일 때 쿨다운 변수가 리셋되지 않아 이전 값이 남아있음
  - **해결**:
    - 쿨다운 변수에 `@Volatile` 어노테이션 추가로 스레드 안전성 보장
    - `navigateToHome()`에서 `applyCooldown=false`일 때 쿨다운 변수를 명시적으로 리셋
    - 쿨다운 체크 로직에 상세 로그 추가 (경과 시간, 쿨다운 만료 여부)
  - **영향 범위**:
    - 철회 버튼 클릭 후 같은 차단 앱 재실행 시 오버레이가 정상적으로 표시됨
    - 다중 스레드 환경에서 쿨다운 변수 접근 시 가시성 보장
    - 디버깅 용이성 향상 (상세 로그)
- **추가 수정 (2026-01-18)**: 오버레이 FrameLayout 이벤트 필터링
  - **문제**: 오버레이가 표시된 후 FrameLayout 이벤트가 Flow로 전송되어 불필요한 체크 발생
    - 오버레이의 FrameLayout이 `TYPE_WINDOW_STATE_CHANGED` 이벤트를 발생시켜 Flow로 전송됨
    - debounce 후 처리 시 `currentOverlay != null`이어서 "오버레이 이미 표시 중" 로그 발생
  - **해결**:
    - `onAccessibilityEvent()`에서 오버레이 패키지(`com.faust`)의 FrameLayout 이벤트를 사전 필터링
    - 필터링 1과 2 사이에 오버레이 패키지 필터링 추가
  - **영향 범위**:
    - 오버레이 표시 후 불필요한 이벤트 처리 제거
    - Flow 처리 부하 감소
    - 로그 노이즈 감소
- **추가 수정 (2026-01-18)**: 스레드 안전성 개선 - 공유 변수에 @Volatile 추가
  - **문제**: 여러 스레드에서 접근하는 공유 변수에 `@Volatile`이 없어 가시성 문제 발생 가능
    - `onAccessibilityEvent()`는 메인 스레드에서 실행
    - `hideOverlay()`, `showOverlay()`는 `Dispatchers.Main`에서 실행
    - `collectLatest`, `handleAppLaunch()`는 `Dispatchers.Default`에서 실행
    - 엄격 모드 빌드에서 가시성 문제로 인한 중복 호출 가능
  - **해결**:
    - 모든 공유 변수에 `@Volatile` 어노테이션 추가
      - `currentOverlay`: 오버레이 인스턴스 참조
      - `lastWindowId`, `lastProcessedPackage`: Window ID 기반 중복 호출 방지
      - `lastAllowedPackage`: Grace Period 체크
      - `currentBlockedPackage`, `currentBlockedAppName`: 현재 협상 중인 앱 정보
    - 기존 `@Volatile` 변수와 일관성 유지
      - `overlayState`, `latestActivePackage`, `lastHomeNavigationPackage`, `lastHomeNavigationTime`
  - **영향 범위**:
    - 유죄협상 중복 호출 방지 메커니즘의 정합성 보장
    - 멀티스레드 환경에서 변수 가시성 보장
    - 엄격 모드 빌드에서도 안전하게 동작
    - 경쟁 조건으로 인한 중복 오버레이 표시 방지

### [2026-02-02] URL 차단 기능 추가 (Universal Browser URL Extraction)
- **작업**: 브라우저 주소창에서 URL을 추출하여 도메인 기반 차단 기능 구현
- **컴포넌트 영향**:
  - **Utils Layer**: `UrlDiscoveryEngine`, `UrlNormalizer` 신규 생성
  - **Data Layer**: `BlockedDomain` 엔티티, `BlockedDomainDao` 신규 생성, `FaustDatabase` 버전 2→3 마이그레이션
  - **Models**: `BlockingPreset`, `BlockingPresetRegistry` 신규 생성
- **변경 사항**:
  - `UrlDiscoveryEngine`: Geometric-First 휴리스틱(Top 20% Rule) + Safe Early-Exit로 브라우저 주소창 URL 추출
  - `UrlNormalizer`: URL 입력 검증 및 정규화 (검색어 트랩 방지, 도메인만 추출)
  - `BlockedDomain`: 차단된 도메인 Room 엔티티 (domain PK, displayName, blockedAt)
  - `BlockedDomainDao`: 도메인 차단 CRUD 및 조회
  - `BlockingPreset` / `BlockingPresetRegistry`: 인기 사이트 원탭 차단 프리셋 (YouTube, Facebook, Instagram 등)
  - `FaustDatabase` 버전 3: `blocked_domains` 테이블 추가
- **영향 범위**:
  - 브라우저에서 차단된 도메인 접속 시 차단 가능 (End-to-End 플로우는 후속 구현)
  - 기존 앱 차단 로직(`BlockedApp`)과 별개로 동작
  - 기존 로직 보존: Zero-deletion Policy 준수

---

## 결론

Faust는 **명확한 계층 분리**와 **단일 책임 원칙**을 따르는 구조로 설계되었습니다. 각 컴포넌트는 독립적으로 테스트 가능하며, 향후 기능 확장이 용이한 아키텍처입니다.

**상세 내용은 각 모듈별 문서를 참조하세요:**
- [Presentation Layer](./docs/arch_presentation.md)
- [Domain Layer & Persona Module](./docs/arch_domain_persona.md)
- [Data Layer](./docs/arch_data.md)
- [핵심 이벤트 정의](./docs/arch_events.md)
