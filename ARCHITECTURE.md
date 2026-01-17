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
│  (AppBlockingService, PointMiningService, etc.)        │
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
│       │   ├── MainActivity.kt                    # 메인 액티비티
│       │   ├── GuiltyNegotiationOverlay.kt        # 유죄 협상 오버레이
│       │   ├── BlockedAppAdapter.kt                # 차단 앱 리스트 어댑터
│       │   ├── AppSelectionDialog.kt              # 앱 선택 다이얼로그
│       │   └── PersonaSelectionDialog.kt           # 페르소나 선택 다이얼로그
│       └── viewmodel/
│           └── MainViewModel.kt                  # 메인 ViewModel (MVVM)
│
├── ⚙️ Service Layer
│   └── services/
│       ├── AppBlockingService.kt                  # 앱 차단 모니터링 서비스
│       └── PointMiningService.kt                  # 포인트 채굴 서비스
│
├── 🧠 Business Logic Layer (Domain)
│   └── domain/
│       ├── PenaltyService.kt                      # 페널티 계산 및 적용
│       ├── WeeklyResetService.kt                 # 주간 정산 로직
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
│       │   ├── FaustDatabase.kt                  # Room 데이터베이스
│       │   ├── AppBlockDao.kt                     # 차단 앱 DAO
│       │   └── PointTransactionDao.kt             # 포인트 거래 DAO
│       │
│       └── utils/
│           ├── PreferenceManager.kt               # EncryptedSharedPreferences 관리
│           └── TimeUtils.kt                       # 시간 계산 유틸리티
│
├── 📦 Models
│   └── models/
│       ├── BlockedApp.kt                          # 차단 앱 엔티티
│       ├── PointTransaction.kt                    # 포인트 거래 엔티티
│       └── UserTier.kt                            # 사용자 티어 enum
│
└── 🚀 Application
    └── FaustApplication.kt                        # Application 클래스
```

---

## 모듈별 상세 문서

### 📱 [Presentation Layer](./docs/arch_presentation.md)

**책임**: UI 컴포넌트 및 사용자 인터랙션 처리

**주요 컴포넌트**:
- `MainActivity`: 메인 UI 표시 및 권한 요청
- `MainViewModel`: 데이터 관찰 및 비즈니스 로직 (MVVM)
- `GuiltyNegotiationOverlay`: 시스템 오버레이로 유죄 협상 화면 표시
- `PersonaSelectionDialog`: 페르소나 선택 및 등록 해제 다이얼로그

**핵심 특징**:
- StateFlow를 통한 반응형 UI 업데이트
- 데이터베이스 직접 접근 제거로 경량화
- Persona Module 통합: 능동적 계약 방식 (사용자 입력 검증)

→ [상세 문서 보기](./docs/arch_presentation.md)

---

### 🧠 [Domain Layer & Persona Module](./docs/arch_domain_persona.md)

**책임**: 비즈니스 로직과 페르소나 기반 피드백 제공

**주요 컴포넌트**:
- `PenaltyService`: 페널티 계산 및 적용
- `WeeklyResetService`: 주간 정산 로직
- `PersonaEngine`: 피드백 조율 엔진 (Safety Net 로직 포함)
- `PersonaProvider`: 페르소나 프로필 제공 (랜덤 텍스트 지원)
- `VisualHandler`, `HapticHandler`, `AudioHandler`: 각 피드백 실행

**핵심 특징**:
- 기기 상태 기반 피드백 모드 자동 조정 (Safety Net)
- 능동적 계약 방식: 사용자가 정확히 문구를 입력해야 강행 버튼 활성화
- 페르소나별 맞춤형 피드백 (시각, 촉각, 청각)

→ [상세 문서 보기](./docs/arch_domain_persona.md)

---

### 💾 [Data Layer](./docs/arch_data.md)

**책임**: 데이터 영속성과 저장소 관리

**주요 컴포넌트**:
- `FaustDatabase`: Room 데이터베이스 (BlockedApp, PointTransaction 엔티티)
- `PointTransactionDao`: 포인트 거래 DAO (Flow 제공)
- `AppBlockDao`: 차단 앱 DAO
- `PreferenceManager`: EncryptedSharedPreferences 관리 (AES256-GCM 암호화)

**핵심 특징**:
- 단일 소스 원칙: 포인트는 `PointTransaction`의 `SUM(amount)`로 계산
- 트랜잭션 보장: 모든 포인트 변경 작업이 원자적으로 처리
- 보안 강화: EncryptedSharedPreferences로 포인트 조작 방지
- 반응형 데이터: Flow를 통한 자동 UI 업데이트

→ [상세 문서 보기](./docs/arch_data.md)

---

### ⚡ [핵심 이벤트 정의 및 시퀀스 다이어그램](./docs/arch_events.md)

**책임**: 비즈니스 로직을 트리거하는 주요 이벤트와 시스템 컴포넌트 간 상호작용 설명

**주요 내용**:
- 앱 차단 플로우 (Event-driven)
- 포인트 채굴 플로우
- Persona 피드백 플로우
- 화면 OFF/ON 감지 및 도주 패널티 플로우
- 주간 정산 플로우
- 상태 전이 모델 (ALLOWED ↔ BLOCKED)

**핵심 특징**:
- 이벤트 기반 감지: AccessibilityService를 통한 실시간 앱 실행 감지
- 오디오 모니터링: AudioPlaybackCallback을 통한 이벤트 기반 오디오 감지
- 상태 전이 시스템: 오버레이 중복 발동 방지

→ [상세 문서 보기](./docs/arch_events.md)

---

## 서비스 아키텍처

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
    │ AppBlockingService    │  │ PointMiningService   │
    │ (AccessibilityService)│  │                      │
    │                       │  │ • 앱 사용 시간 추적  │
    │ • 이벤트 기반 감지     │  │ • 포인트 자동 적립    │
    │ • 오버레이 트리거     │  │                      │
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
  │           └─► PointMiningService.startForeground()
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
  ├─► PointMiningService
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
  └─► PointMiningService (pauseMining/resumeMining)

PointMiningService
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
1. **BIND_ACCESSIBILITY_SERVICE**: 접근성 서비스를 통한 앱 실행 감지
2. **SYSTEM_ALERT_WINDOW**: 오버레이 표시
3. **FOREGROUND_SERVICE**: 백그라운드 서비스 실행 (PointMiningService용)
4. **QUERY_ALL_PACKAGES**: 설치된 앱 목록 조회

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
- 상점 시스템
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
- PointMiningService도 이벤트 기반으로 전환 검토

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
  - `PointMiningService`: `isPausedByAudio()` companion 메서드 추가, 오디오 종료 시 플래그 리셋
  - `AppBlockingService`: 화면 OFF 시 상태 확인 및 저장, ALLOWED 전이 시 조건부 재개
- **변경 사항**:
  - `PreferenceManager`에 `wasAudioBlockedOnScreenOff()`, `setAudioBlockedOnScreenOff()` 메서드 추가
  - `PointMiningService`에 `isPausedByAudio()` companion 메서드 추가
  - `AppBlockingService.registerScreenOffReceiver()`에서 화면 OFF 시 `isPausedByAudio` 상태 확인 및 저장
  - `AppBlockingService.transitionToState()`에서 ALLOWED 전이 시 저장된 상태 확인 후 조건부 재개
  - `PointMiningService.checkBlockedAppAudioFromConfigs()`에서 오디오 종료 시 플래그 리셋
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
  - `PointMiningService`: 오버레이 표시 중일 때 오디오 검사 건너뛰기
- **변경 사항**:
  - `AppBlockingService` companion object에 `isOverlayActive` static 변수 추가 및 `isOverlayActive()` 메서드 추가
  - `AppBlockingService.showOverlay()`에서 오버레이 표시 시 `isOverlayActive = true` 설정
  - `AppBlockingService.hideOverlay()`에서 오버레이 닫힐 때 `isOverlayActive = false` 설정
  - `PointMiningService.checkBlockedAppAudioFromConfigs()`에서 오버레이 표시 중이면 오디오 검사 건너뛰기
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
  - 검사 결과를 저장하여 포인트 채굴 여부를 결정한다는 것을 명확히 설명
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
  - 다른 검사 로직(Cool-down, 오디오 차단 등) 및 포인트 채굴 재개 로직에 영향 없음

---

## 결론

Faust는 **명확한 계층 분리**와 **단일 책임 원칙**을 따르는 구조로 설계되었습니다. 각 컴포넌트는 독립적으로 테스트 가능하며, 향후 기능 확장이 용이한 아키텍처입니다.

**상세 내용은 각 모듈별 문서를 참조하세요:**
- [Presentation Layer](./docs/arch_presentation.md)
- [Domain Layer & Persona Module](./docs/arch_domain_persona.md)
- [Data Layer](./docs/arch_data.md)
- [핵심 이벤트 정의](./docs/arch_events.md)
