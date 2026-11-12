# BRAND-ICON-01 — QuickCut 앱 아이콘 교체

기준: 2026-11-12
선정 콘셉트: A · 영상 프레임 + 재생 삼각형 + 컷/가위. Generate image 제안 시안에서 시각적 요소를 추출해 단색·중성 계열로 정리.
색상: 차콜 `#181818` + 화이트; 글자·그림자·그라데이션·3D·광택 없음.
기획 참고: https://docs.google.com/document/d/1hyNminChkLsX3MHes3xM_m4KuuHTHD1gC0K8O5UnRCo/edit

## 반영 리소스

| 파일 | 역할 | 규격 |
|---|---|---|
| `design/brand/quickcut-brand-icon-01-master.png` | 브랜드 마스터 | PNG RGB, 1024×1024 |
| `store/google-play/icon.png` | Google Play 스토어 아이콘 | PNG RGB, 512×512 |
| `app/src/main/res/drawable-nodpi/quickcut_brand_foreground.png` | 단색 투명 전경 | PNG RGBA, 432×432 |
| `app/src/main/res/drawable/quickcut_brand_foreground_safe.xml` | 원형/마스크 안전 여백 | 108dp 중 사방 10dp 인셋 |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Android Adaptive icon | 기존 마스크 유지, 전경 교체 |
| `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml` | Android 13 테마 아이콘 | 동일 투명 전경의 모노크롬 틴트 |
| `app/src/main/res/values-v31/styles.xml` | 시작 화면 스플래시 | 동일 브랜드 전경 |
| `app/src/main/java/com/hebi/quickcut/ui/BrandArtwork.kt` | 홈 헤더 로고 | 동일 브랜드 전경 |
| `app/src/main/res/values/colors.xml` | 런처 배경 | `#181818` |

기존 배포된 Google Play 등록정보는 이번 Draft PR로 변경되지 않음.

## 검증

1. `python3 scripts/test-launcher-icon.py`: Android manifest 런처 식별자 보존, Adaptive(26)/mono(33)/Splash(31)/홈 리소스 일치, PNG IHDR/IDAT/IEND CRC 및 실제 zlib 압축 해제, 1024/512/432 규격, 화이트/블랙 대비, 투명 전경과 66dp 안전 원형 마스크 확인. Python 표준 라이브러리만 사용.
2. `python3 scripts/publish-play-listing-images.py --check-only`: 스토어 PNG 512×512와 기존 8개 한국어 스크린샷 승인 해시 보존.
3. `python3 scripts/test-play-listing-images.py`: Play 이미지 업로드는 정확한 승인/해시 없이는 수정하지 않는 회귀 검증.
4. 최신 GitHub Android build 및 API29/35/36 QA; 아이콘 화면 캡처로 확인 가능한 크기의 실제 Android 런처·테마 아이콘은 추후 실기기/에뮬레이터 전수 QA.
5. 작은 크기 (48/96px) 및 라이트·다크 배경 역상 가독성을 제안 시안과 원본 PNG에서 검토.

## 배포 차단 경계

- UI-08-A 미해결 QA(#58~#62) 완료 전에는 **본 아이콘 PR은 Draft 유지·main 미병합**. 기존 Play 아이콘도 실제 서버에 미변경 상태.
- 기존 `.github/workflows/play-listing-images.yml`은 main Android 검증 성공 후 스토어 **전체 10장** 업로드를 시도할 수 있음. 그러므로 UI-08-A/Play 이미지 교체 절차를 검토한 후 별도 사용자 승인 범위에서 병합해야 함.
- Google Play API publish workflow의 검증·서명·WIF·패키지명·버전·기타 스크린샷·기존 출시 트랙은 이번 PR에서 건드리지 않음.
- 앱 코드 버전 자동 증가나 트랙 게시 성공을 주장하지 않음.

Google Drive QuickCut 작업 폴더: https://drive.google.com/drive/folders/1V8N0OS1j57eoD0OGDtZfcBtwIWxKjMA5

Google Drive 디자인 검수·배포용 원본 5종: https://drive.google.com/drive/folders/1jLd-3WsaAy5IH2d6F0sikWJQc3Dwd1Ks
- 1024×1024 브랜드 마스터: https://drive.google.com/file/d/1c3PGS73bznGLlwR0A4CLxkvkrm4KTAOI/view
- 512×512 Google Play 아이콘: https://drive.google.com/file/d/1WKyxRHxBp0TQXgLBHqq16jGmlOhoc9oZ/view
- 432×432 Android 적응형 전경: https://drive.google.com/file/d/1KGHjzgZjVX9SNNStxS2q8j0QlV6TZOYX/view
- 432×432 모노크롬: https://drive.google.com/file/d/1PcVS50iqmtHakqkbweJ8dKts5Hf3t6Lh/view
- 크기별 48·96px 검수: https://drive.google.com/file/d/1Ekm_CkDmPoiFhNSBfTRnCBXnx9sjNNVv/view

**자료/배포 구분:** Drive에 보관한 마스터·모노크롬 원본은 시안 검수용입니다. 앱·Play 배포에 사용할 실제 파일은 이 브랜치의 버전 관리 자산이며, 커밋된 바이너리의 CI 검증 및 최종 캡처 확인 이후 승인해야 합니다. Drive 업로드는 Google Play 업로드가 아닙니다.
