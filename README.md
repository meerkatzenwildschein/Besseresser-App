# 🥗 Besseresser

**Kein Kalorienzählen. Keine endlosen Lebensmitteltabellen. Einfach fotografieren und sofort wissen, ob dein Essen zu deinen Zielen passt.**

**Besseresser** ist dein persönlicher KI-Ernährungsbegleiter für Android. Du legst einmal fest, worauf du achten möchtest – zum Beispiel *„Blutdruck senken"*, *„Weniger Zucker"*, *„Gewicht verlieren"* oder *„Mehr Eiweiß"* –, machst ein Foto von deinem Teller und erhältst in Sekunden eine ehrliche **Ampel-Bewertung (🟢 Grün · 🟡 Gelb · 🔴 Rot)** für jedes einzelne Ziel.

---

## 📸 Einblicke

| Start & Ziele | Mahlzeiten-Auswertung | Verlauf & Tagesfazit |
| :---: | :---: | :---: |
| <img src="screenshots/Start.jpg" width="250" alt="Startbildschirm mit persönlichen Zielen" /> | <img src="screenshots/Details.jpg" width="250" alt="Detailansicht einer ausgewerteten Mahlzeit" /> | <img src="screenshots/Verlaufsansicht.jpg" width="250" alt="Verlaufsansicht mit Ampelschnitt und Tagesfazit" /> |

---

## ✨ Warum Besseresser?

- **🎯 Deine Ziele, deine Maßstäbe:**
  Statt starrer Diätprogramme formulierst du deine eigenen Gesundheits- oder Ernährungsziele einfach als Freitext. Auf Wunsch hilft dir die KI beim Einrichten sogar dabei, längere Sätze in klare Einzelziele aufzuteilen.
- **📷 Fotografieren, ergänzen, fertig:**
  Knipse dein Essen direkt in der App. Falls man etwas auf dem Bild nicht sieht (z. B. *„mit Olivenöl zubereitet"* oder *„nur die halbe Portion"*), gibst du kurz einen Hinweis dazu – häufig genutzte Notizen lassen sich mit einem ⭐ als Favorit speichern.
- **🚦 Klare Ampeln statt Zahlenwüsten:**
  Zu jedem deiner Ziele zeigt dir Besseresser sofort Grün, Gelb oder Rot mit einer kurzen, verständlichen Begründung, einer Mengenschätzung der erkannten Speisen und einem Gesamt-Ampelschnitt (0–100).
- **✏️ Jederzeit korrigierbar:**
  Hat die KI einmal Tofu für Feta gehalten? Ein Tipp auf das Stift-Symbol genügt, um das erkannte Essen anzupassen – die Bewertung wird sofort passend aktualisiert.
- **📅 Tages- & Wochenfazit mit Augenmaß:**
  Ein einzelnes Stück Schokolade macht noch keinen schlechten Tag. Abends (oder jederzeit auf Fingertipp) zieht Besseresser ein ausgewogenes **Tagesfazit** über alle Mahlzeiten hinweg – und sonntags ein **Wochenfazit**.
- **➕ Vergessenes Essen nachtragen & duplizieren:**
  Kein Foto gemacht? Über das **„+"** am jeweiligen Tag kannst du Mahlzeiten jederzeit nachträglich erfassen – mit Foto oder rein per Textbeschreibung. Wiederkehrende Lieblingsgerichte (wie dein Standard-Frühstück) lassen sich aus der Detailansicht mit einem Tipp auf jeden beliebigen Kalendertag **duplizieren**.
- **👤 Optionales persönliches Profil:**
  Wenn du möchtest, kannst du Alter, Gewicht, Größe oder Besonderheiten (z. B. Diabetes, Unverträglichkeiten, Medikamente) hinterlegen. Diese Angaben fließen automatisch in dein Tages- und Wochenfazit ein.
- **🔒 Volle Kontrolle & Datenschutz (Bring Your Own AI):**
  Kein Abo-Zwang, kein fremder Cloud-Account: Alle deine Mahlzeiten, Fotos, Ziele und dein Profil bleiben **lokal auf deinem Smartphone**. Dein API-Schlüssel wird hardwaregeschützt im Android-Keystore verschlüsselt.

---

## 🚀 In 2 Minuten startklar

1. **Einrichten:** Beim ersten Start trägst du deine persönlichen Ziele ein und wählst deinen bevorzugten KI-Anbieter.
2. **KI-Anbieter verbinden:** Besseresser funktioniert mit nahezu jedem OpenAI-kompatiblen Dienst – eine Schnellauswahl ist direkt integriert:
   - **Google Gemini** *(bietet ein kostenloses Kontingent)*
   - **OpenRouter** *(Zugriff auf hunderte Modelle über einen Account)*
   - **OpenAI**, **Mistral**, **Groq**, **xAI (Grok)**, **Anthropic** u. v. m.
   - Über *„Modelle laden & auswählen"* zeigt dir die App automatisch alle bildfähigen Modelle deines Anbieters an.
3. **Loslegen:** Essen fotografieren, Ampeln prüfen und bei Gefallen im Verlauf speichern!

---

## 📦 Installation & Bauen

Du kannst die App direkt aus dem Quellcode mit Android Studio oder über die Kommandozeile auf deinem Android-Smartphone (ab Android 8.0) installieren:

```bash
./gradlew installDebug
```

---

## 📄 Lizenz

Veröffentlicht unter der [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
