# REFACTORING.md – Besseresser

Modernisierungs-Roadmap der App von einem „alles in einem Package / Store-Objekte"-Stand
hin zu einer sauberen MVVM-Schichtenarchitektur. Status: **abgeschlossen**.

---


## Phase 6 – Aufräumen & Dependency-Updates ✅

**Ziel:** Naming, Docs, Versions-Pflege.

- `Theme.GesundEssen` → `Theme.Besseresser` (Manifest + themes.xml).
- `REFACTORING.md` angelegt (diese Roadmap; vorher nur referenziert, nicht vorhanden).
- Dependency-Bump: Kotlin 2.0.21, KSP 2.0.21-1.0.26, Compose BOM 2024.12.01, core-ktx 1.16.0, lifecycle 2.8.7, activity-compose 1.10.1, camera 1.5.0, room 2.7.0, datastore 1.1.3, navigation-compose 2.8.8, coroutines-test 1.9.0.
