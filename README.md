# NX Antisplit

Bölünmüş APK paketlerini (**XAPK / APKS / APKX / APKM**) tek, kurulabilir, düz bir **APK**'ye dönüştüren Android uygulaması.

## Ne yapar?
1. Cihazdan bir `.xapk` / `.apks` / `.apkx` / `.apkm` dosyası seçersin.
2. Uygulama arşivi açar, içindeki `base.apk` + `config/split` parçalarını bulur.
3. [ARSCLib](https://github.com/REAndroid/ARSCLib) ile parçaları (resources + dex + native lib + assets) tek bir APK'de **birleştirir**.
4. [apksig](https://android.googlesource.com/platform/tools/apksig/) ile çıktıyı **imzalar** (v1+v2+v3) — böylece kurulabilir olur.
5. Sonucu **Download** klasörüne kaydeder veya doğrudan **kurar**.

> Not: Birleştirme yeniden imzalama gerektirir, yani çıktı orijinal geliştirici imzasını taşımaz; "bu araçtan gelen" kendi imzasıyla kurulur. Bu, bölünmüş APK'leri birleştirmenin doğası gereği kaçınılmazdır.

## Derleme

### GitHub Actions (otomatik)
`main` dalına her push'ta veya **Actions → Build APK → Run workflow** ile derlenir.
Çıktı:
- Actions çalışmasının **Artifacts** kısmında `NX-Antisplit-APK`
- Otomatik oluşturulan **Release** içinde `NX-Antisplit.apk`

### Yerel (Android Studio)
Projeyi Android Studio'da aç → Gradle sync → **Build > Build APK(s)**.
Veya terminalde:
```bash
./gradlew assembleRelease
```
Çıktı: `app/build/outputs/apk/release/`

## Gereksinimler
- minSdk 29 (Android 10+)
- JDK 17 (CI bunu otomatik kurar)

## Lisans
ARSCLib (Apache-2.0) ve apksig (Apache-2.0) kullanır.
