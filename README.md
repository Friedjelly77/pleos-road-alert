# Pleos Road Alert

사고·급정차·도로 위험을 제보하고 같은 방향에서 접근하는 참여 차량에 알려주는 공모전용 Android 프로토타입이다. 기본 시연은 임의의 두 도로, 진행 방향, 높이, 도로 기점으로부터의 거리를 사용한다. 앱을 설치한 두 기기 또는 기기 하나와 Python 클라이언트로 시연할 수 있다. 지도 키·계정·유료 서비스가 필요 없다.

- [앱 구동 기획](기획문서/Pleos_Road_Alert_앱_구동_기획.md)
- [SDK 및 정책 확인표](docs/SDK.md)
- [실행한 검증과 남은 작업](docs/VERIFICATION.md)
- [실제 APK 수신 화면](docs/approach.png)

## 1. 서버 실행

프로젝트 루트에서 Python 3.10 이상으로 실행한다. 표준 라이브러리 HTTP 서버와 SQLite를 선택해 설치할 서버 패키지를 없앴다. 기본 주소는 `127.0.0.1:8787`이다.

```powershell
python -m server.app
```

이 PC에서 Windows의 Python 별칭이 동작하지 않으면 다음 명령으로 확인한 Python 3.12 런타임을 사용할 수 있다.

```powershell
$py = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py -m server.app
```

다른 터미널에서 `curl.exe http://127.0.0.1:8787/health`를 실행해 `ready`를 확인한다. 서버는 Ctrl+C로 종료한다.

## 2. APK 설치와 실행

Android Studio Device Manager에서 기기를 실행하고 `adb devices -l`로 일련번호를 확인한다. 이 PC의 adb 기본 위치는 `%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe`다. 아래 예시는 프로젝트 폴더 바로 위에 배포 APK를 둔 경우다.

```powershell
$adb = "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$device = "emulator-5554"
& $adb -s $device install -r ..\PleosRoadAlert-debug.apk
& $adb -s $device reverse tcp:8787 tcp:8787
& $adb -s $device shell am start -n kr.roadalert.pleos/kr.roadalert.MainActivity
```

두 번째 기기도 해당 일련번호로 같은 명령을 실행한다. 서버 포트를 예를 들어 8790으로 바꾸면 `reverse tcp:8787 tcp:8790`으로 연결한다. 앱의 개발용 접속 포트는 8787이다. 에뮬레이터를 재시작하면 reverse를 다시 설정한다.

## 3. 첫 시연

1. B에서 **데모 설정 → B · 600 m 전방 접근 → 메인으로**를 선택한다. 기기가 하나면 B 대신 별도 터미널에서 `python tools/participant.py watch --offset 400`을 실행한다.
2. A에서 **데모 설정 → A · 제보 지점 → 메인으로 → 상황 제보 → 사고 제보**를 누른다. A는 서버 응답 후 접수 완료를 표시한다. B에는 전방 약 600 m의 사고 제보와 정체 가능성 안내가 나온다.
3. B의 위치를 반대 방향·이미 통과·교차 도로·고가·하부도로로 바꿔 경고가 사라지는지 확인한다. A에서 **최근 제보 상태 → 해소 제보**를 누른 뒤 B를 접근 위치로 되돌려도 경고가 다시 나오지 않는다. 잘못 올린 사건은 **제보 취소**로 닫는다.
4. 기기 하나로 앱의 수신 화면을 보려면 앱을 B에 놓고 `python tools/participant.py report --offset 1000`을 실행한다. CLI에 `resolve`를 입력하면 해소된다.
5. A에서 **가상 입력 → 비상등 켜기** 또는 **급감속**을 선택한다. 확인 화면에서 건너뛰면 서버에 사건이 생기지 않는다. **에어백 전개**는 명시적으로 주입한 가상 전개 변화다. 이 경우 사고 유형과 미확인 상태로 접수된다.
6. 오프라인 시연은 A의 `adb reverse --remove tcp:8787` 후 제보하고 같은 포트 연결을 복원한다. 대기 중인 제보는 같은 ID로 재시도한다. 너무 오래된 신규 제보는 거절된다.

**SDK + 가상 도로**에서는 실제 SDK 콜백만 차량 신호로 받으며 도로는 계속 가상이다. **실제 도로**에서는 동의 후 기기 GNSS의 정확도·방향·측정 시각을 진단할 수 있다. 실제 도로·높이 매칭 공급자가 없어 이 모드의 사건 업로드와 경고는 보류한다. 주행 제한이 걸리면 조작을 잠근다. 백그라운드에서는 신호 수집과 새 조회를 중지하며, 진행 중이던 요청은 제한 시간 내에 종료된다.

## 소스 빌드와 테스트

Android Studio에서 `settings.gradle`이 있는 폴더를 연다. 이 PC에서 확인한 조합은 Android Studio Ladybug 2024.2.1 계열, JDK 17.0.12, Gradle 8.9, Android Gradle Plugin 8.6.1, Kotlin 1.9.25, Build Tools 34.0.0이다. compileSdk 35와 targetSdk 34로 Pleos Android 14 이미지에 맞췄고 minSdk는 28이다. 첫 동기화에는 Google Maven·Maven Central·Pleos 공식 Maven 접속이 필요하다. JDK 17과 Android SDK 경로를 설정한 뒤 실행한다. [Pleos 환경 요구사항](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) · [AGP 호환성](https://developer.android.com/build/releases/agp-8-6-0-release-notes)

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
python -m unittest discover -s tests -v
# 서버 실행 및 APK 설치 후. 다른 사건이 없는 시연용 DB에서 실행한다.
python tools/device_check.py --adb "$adb" --serial "$device" --evidence .\device-evidence
```

빌드된 APK 경로는 `app/build/outputs/apk/debug/app-debug.apk`이다. 배포물은 개발용 서명본이다. Pleos 전용 이미지를 새 PC에 설치하는 공식 절차는 [Pleos 에뮬레이터 설치 문서](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)에 있다. **이 작업 PC**에는 작업 폴더에 체크섬 검증을 마친 이미지가 있다. Windows 탐색기에서 `tools/start-installed-pleos.cmd`를 실행하면 해당 작업 폴더의 AVD로 부팅을 시도한다. 이동한 프로젝트에서는 Device Manager를 사용한다.

## 서버 설정

환경 변수는 시작할 때 읽는다. 기본값은 다음과 같다.

| 변수 | 기본값 | 의미 |
|---|---:|---|
| ROAD_PORT | 8787 | 호스트 루프백 포트 |
| ROAD_DB | road-alert.sqlite3 | 사건 DB 경로 |
| ROAD_TTL_SECONDS | 300 | 접수 또는 독립 참여자 추가 제보 이후 유효시간 |
| ROAD_FIX_SECONDS | 20 | 접근 차량 위치 허용 연령 |
| ROAD_REPORT_SECONDS | 90 | 신규 제보의 최대 전송 지연 |
| ROAD_MERGE_SECONDS | 120 | 최초 발생 시각 기준 병합 범위 |
| ROAD_MERGE_METERS | 80 | 같은 유형·구간·높이·방향에서 병합할 최대 거리 |
| ROAD_AHEAD_METERS | 1500 | 전방 조회 최대 거리 |
| ROAD_RETAIN_SECONDS | 3600 | 종료 후 보관 시간. 오래된 재시도를 걸러낼 기간까지는 유지 |

## 데이터와 통신

- `POST /v1/events`로 접수하고 `GET /v1/events/{id}`로 상세를 조회한다. 최초 제보자만 `PATCH /v1/events/{id}`로 해소·취소할 수 있다. 독립 참여자의 근접 제보는 확인 수준과 만료 시각을 갱신한다. 최초 위치·발생 시각·최초 출처는 고정한다.
- `POST /v1/watch`는 현재 접근 조건과 이전 결과 식별자를 받는 HTTP 롱폴링이다. 관련 사건이 바뀌면 즉시 응답하고 앱은 다음 요청을 연다. 만료·위치 신선도 경계에서도 결과를 다시 계산한다. 최대 세 건에서 가장 가까운 한 건을 화면에 보여준다.
- 앱 설치별 난수 자격증명과 UUID를 조합해 재전송을 식별한다. 같은 키에 다른 내용은 409로 거절한다. SQLite 트랜잭션으로 사건과 접수 기록을 함께 기록한다. 앱에는 Android Keystore 키로 암호화한 대기열·접수 ID를 남긴다.
- 서버의 가상 도로 위치는 구간·높이·방향·거리만 쓴다. 접근 위치는 메모리에서 비교한 뒤 폐기한다. 실제 GNSS 좌표·VIN·번호판·이동 경로·원시 브레이크 시계열을 보내지 않는다. 개발 서버는 루프백으로만 바인딩하고 앱의 평문 예외는 디버그 로컬 주소로 한정한다.
- 서비스 공개 전에는 인증·권한 관리와 남용 방지, TLS, 보관·삭제 정책, 위치 사용 동의, 외부 전송과 차량 내 동작에 대한 심사를 완료해야 한다. [Pleos 개인정보 지침](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/security-privacy)
