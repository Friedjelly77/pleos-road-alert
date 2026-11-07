# SDK 접근 확인표

확인일은 2026-11-07이다. **문서 확인**은 공식 설명에서 확인한 기능, **실제 접근 가능**은 이 PC에서 확보하고 컴파일한 API 또는 호출 가능한 실행 환경, **에뮬레이터 검증 완료**는 지정한 이미지에서 직접 관찰한 결과다. 컴파일과 런타임 권한은 별도로 기재했다.

| 항목 | 문서 확인 | 실제 접근 가능 | 에뮬레이터 검증 완료 | 미확인 또는 적용 범위 |
|---|---|---|---|---|
| 좌석별 에어백 전개 | Safety의 capability·현재값·변화 구독 | Vehicle 2.3.2 AAR에서 세 메서드 확인, 실제 타입으로 앱 빌드 | 전개 신호 수신 미확인 | 좌석별 권한, 최초값, 실제 변화 콜백, 전개 주입 |
| 에어백 경고등 | Telltales API와 Console 경고등 조작 | 전개 판정에 사용하지 않음 | 미확인 | 경고등을 바꿔도 이 앱의 전개 감지기로 전달되지 않음 |
| 브레이크·비상등 | Brake 페달 상태, Console의 Brake Pedal·Hazard Lights·Stop Lights | 공식 페달 capability·조회·구독을 컴파일. 페달이 눌린 경우 제보 화면에서 급정차 선택을 강조 | 페달 콜백 미확인 | 비상등과 제동등의 실차 읽기 권한 미확인. 비상등 주입은 가상 모드만 구현 |
| 속도·정차 | AAOS `PERF_VEHICLE_SPEED`, 속도 단위 m/s | API 35의 `android.car.jar`로 `CarPropertyManager` 구독 컴파일 | 실시간 차량 속도 미확인 | `CAR_SPEED` 권한과 VHAL 가용성 필요. 측정 간격·속도 감소량으로 의심 신호만 생성. 속도 0을 주차 허용 근거로 사용하지 않음 |
| 위치·방향·측정 시각 | Pleos FusedLocation 1.1.2, Android Location | Android LocationManager 콜백과 Location의 accuracy·bearing·elapsedRealtimeNanos 기반 진단 | 일반 API 35 에뮬레이터에 좌표 두 개를 주입해 APK에서 ±5 m·방향 0°·측정 경과 0초 표시 확인. 실제 도로 모드는 구간 확인 대기 유지 | 주입 위치와 실차 GNSS 정확도는 별개. Pleos FusedLocation·상용 도로 매칭 미확인 |
| TTS | Pleos TextToSpeech 3.3.1, 차량 오디오 포커스 | Android TTS 설치 음성·한국어·오프라인 조건과 일시적 포커스 획득 경로 구현 | 한국어 출력 미확인 | 기본 꺼짐. 정차 시 선택, 사건 ID로 중복 억제. 주행 음성 승인 및 Pleos 엔진 별도 시험 |
| STT | Pleos SpeechToText 3.3.1 | 해당 SDK·마이크 권한 호출은 이번 빌드에 포함하지 않음 | 미확인 | 공식 엔진 이용 조건·인식 결과 미확인. 버튼 제보로 시연 |
| 내비 현재 위치·경로·진행 상태 | NaviHelper 3.0.5의 내비 정보·경로 조회와 진행 상태 이벤트. 위치 서비스는 별도 FusedLocation 문서 참조 | 본 앱이 내장 내비의 현재 위치·경로를 읽는 권한 미확인 | 미확인 | 내비 탑재 여부, 안내 세션과 반환 필드, 구간·상하부 식별 품질 확인 필요 |
| 앱 카테고리·주행 화면 | Mobility Services를 신청 후보로 검토. 지도/내비 카테고리의 주행 예외는 경로 안내 성격·지역·심사에 의존 | AAOS `CarUxRestrictionsManager` 구독 컴파일 | 일반 Android 전경 화면만 검증 | 카테고리 승인과 지역별 주행 허용 미확인. DO 선언을 넣지 않았으며 제한 또는 상태 미상 시 조작 잠금 |
| 백그라운드·음성 정책 | 자원 해제, 실행 조건 명시, 시스템 오디오 포커스 준수 | 전경 Activity 수명주기로 조회·신호·TTS 제어 | 백그라운드 장시간 동작·차량 오디오 중재 미확인 | 상시 서비스·백그라운드 위치 수집 제외 |

### 에어백 전개를 어떻게 구별했는가

실제 AAR 안에서 `checkAirbagDeployedCapabilities`, `hasSeatAirbagBeenDeployed`, `registerSeatAirbagDeployed`, 해제 메서드를 확인했다. 앱은 capability가 읽기를 허용한 좌석마다 초기 상태를 읽고 리스너를 연결한다. 최초에 이미 전개된 상태는 기준값으로만 쓰며, 이후 false→true 변화를 새로운 전개로 처리한다. 차량 신호 출처와 미확인 상태로 기록한다. 가상 모델에서는 여러 좌석의 중복 이벤트·중복 콜백 억제를 테스트했다. [Safety](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/entry) · [전개 조회](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-seat-airbag-been-deployed) · [전개 구독](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/register-seat-airbag-deployed)

Virtual Console의 Safety 표에서 찾은 조작은 경고등 상태다. 에어백 전개 이벤트를 생성하는 조작은 확인하지 못했다. 먼저 **SDK + 가상 도로**에서 Brake Pedal을 바꾸고, 제보 화면과 `adb logcat -s RoadAlertSDK`에서 콜백을 대조하는 것이 재현 가능한 첫 시험이다. 에어백은 지원 좌석과 초기 상태 조회 성공을 별도로 기록한 뒤, 공식 이벤트 주입 수단을 확보한 경우에만 전개 수신으로 표시한다. [Virtual Console](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide) · [도메인 권한](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)

### 확인한 배포물과 권한

- 공식 Maven에서 Vehicle 2.3.2 POM·AAR·메타데이터를 받았다. AAR SHA-256은 `e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937`이다. 문서 기준 Vehicle Service는 2.3.21이다. Playground 프로젝트별 기능 상태는 별도이므로 팀 프로젝트의 **Ready to Use**를 확인해야 한다. [Vehicle SDK](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro) · [공식 Maven 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup) · [접근 신청](https://document.pleos.ai/en/docs/pleos-only/create-my-project)
- 공식 Windows용 Pleos Connect v2.1.0 x86_64 이미지 4,087,751,964바이트를 내려받아 배포 목록의 SHA-1 `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa`과 대조하고 작업 폴더에 풀었다. APK와 소스 배포 ZIP에는 전용 이미지가 들어 있지 않다. [공식 설치 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- 위치·경로·음성은 각각 별도 SDK·서비스다. 차량 모델별 AAOS 속성, 내비 탑재와 실제 계정 권한에 따라 결과가 달라진다. [위치](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro) · [내비](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro) · [TTS](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro) · [STT](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro) · [AAOS 속도](https://developer.android.com/reference/android/car/VehiclePropertyIds#PERF_VEHICLE_SPEED)
- 주행 제약은 지역과 차량 상태에 따라 다르다. 본 시연의 UI와 권한은 정차 전경용으로 설계했다. [카테고리](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-category) · [주행 제한](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy) · [백그라운드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model) · [오디오 포커스](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt)
