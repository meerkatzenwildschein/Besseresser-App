# REFACTORING.md – Besseresser

Modernisierungs-Roadmap der App von einem „alles in einem Package / Store-Objekte"-Stand
hin zu einer sauberen MVVM-Schichtenarchitektur. Status: **abgeschlossen**.

---


## Phase 6 – Aufräumen & Dependency-Updates ✅

**Ziel:** Naming, Docs, Versions-Pflege.

- `Theme.GesundEssen` → `Theme.Besseresser` (Manifest + themes.xml).
- `REFACTORING.md` angelegt (diese Roadmap; vorher nur referenziert, nicht vorhanden).
- Dependency-Bump: Kotlin 2.0.21, KSP 2.0.21-1.0.25, Compose BOM 2024.12.01, core-ktx 1.15.0, lifecycle 2.8.7, activity-compose 1.9.3, coroutines-test 1.9.0.

---

## Offene Punkte & Lint-Findings

### 2. Mittlerer Nutzen (Benutzererfahrung & Plattform-Konformität)
- **Themed App Icon unterstützen (`MonochromeLauncherIcon`)**: `<monochrome android:drawable="..." />` in `ic_launcher.xml` für adaptive Icons auf Android 13+ ergänzen.
- **App-Bundle Sprachpaket-Splitting (`AppBundleLocaleChanges`)**: In `app/build.gradle.kts` `bundle { language { enableSplit = false } }` konfigurieren, damit beim dynamischen In-App-Sprachwechsel keine Strings im App Bundle fehlen.

### 3. Gezielte Dependency-Updates (Wägung von Stabilität vs. Aktualität)
- **AndroidX & Lifecycle-Bibliotheken schrittweise und testgetrieben aktualisieren**:
  - `androidx.core:core-ktx`: `1.15.0` → `1.19.1`
  - `androidx.activity:activity-compose`: `1.9.3` → `1.13.0`
  - `androidx.lifecycle:*`: `2.8.7` → `2.11.0`
  - `androidx.camera:*`: `1.4.2` → `1.6.2`
  - `androidx.room:*`: `2.6.1` → `2.8.5`
  - `androidx.datastore:datastore-preferences`: `1.1.1` → `1.3.0`
  - `androidx.navigation:navigation-compose`: `2.8.4` → `2.10.2`
