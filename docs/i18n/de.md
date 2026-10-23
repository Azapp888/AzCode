# AzCode (Android)

[简体中文](zh-CN.md) | [English](en.md) | [日本語](ja.md) | [한국어](ko.md) | [Français](fr.md) | [Русский](ru.md) | [Español](es.md)

AzCode ist ein lokal auf Android-Geräten laufender Automatisierungsassistent. Beschreibe eine Aufgabe in natürlicher Sprache, und die integrierte Agent-Schleife liest den Bildschirm, tippt Steuerelemente an, wischt und führt Befehle über den Bedienungshilfe-Dienst aus, bis die Aufgabe erledigt ist. Alle Daten bleiben auf dem Gerät, ohne Cloud-Synchronisierung.

- App-Name: **AzCode**
- GUI-Formbezeichnung: **magic**
- CLI-Formbezeichnung: **Listen**
- Aktuelle Version: **1.9.15** (versionCode 70)
- Voraussetzungen: Android 8.0+ (minSdk 26), targetSdk 34

AzCode läuft eigenständig oder bietet eine Funktionsbrücke, die nur an `127.0.0.1:8848` gebunden ist, damit die Listen-CLI oder andere Clients dieselben Gerätefunktionen über `adb forward` nutzen können.

## Funktionsübersicht

| Funktion | Beschreibung |
| --- | --- |
| Aufgaben in natürlicher Sprache | Chat-Eingabe, mehrstufige Agent-Planung, Live-Anzeige der Werkzeugaufrufe |
| Bildschirmsteuerung | Knoten lesen, Tippen per Text oder Koordinaten, Wischen, Systemnavigation, Unicode-Texteingabe |
| App-Start | Start per Paket- oder App-Name mit Shell- und Bedienungshilfe-Fallback |
| Befehlsausführung | Termux, integrierte Shell, Shizuku und Root, je nach Berechtigungsmodus automatisch gewählt |
| Mehrere Modelle | Mehrere Anbieter-Konten, OpenAI-kompatibel, Anthropic und Gemini |
| Denktiefe | Vier Stufen (aus, schnell, standard, tief) mit modellgerechter Parameterunterstützung |
| Sitzungen | Getrennte Sitzungen, Verlaufs-Schublade, automatische Titel, Kontextfortsetzung und -verdichtung |
| Anhänge | Bilder, Audio, PDF, Office, Text und Code |
| Spracheingabe | Umschaltbare Offline-Systemerkennung und Online-Erkennung von Xunfei |
| Schwebender Assistent | Standard-Digitalassistenten-Einstieg, transparenter Schein, In-Page-Fragen und Tastatur |
| Langzeitgedächtnis | Sensible Token, Gewohnheiten, Methoden und lokale Personalisierung |
| Skills und Plugins | Integrierte Skills, eigene Skills, GitHub-SKILL.md-Installation, Scan populärer Plugins |
| GitHub | Dateien lesen und schreiben, Branches und Commits durchsuchen, Issues und Pull Requests verwalten |
| Bildgenerierung | Text-zu-Bild über mehrere Anbieter, direkt im Chat sichtbar und speicherbar |
| Office-Dokumente | Erzeugung von docx / xlsx / pptx über GenOffice MCP mit Vorschau |
| Eingabemethode | Integrierte AzCode-Tastatur als Fallback für Unicode-Texteingabe |
| Funktionsbrücke | HTTP-API auf Loopback und Listen-CLI |
| Protokolle | Absturz- und Laufzeitprotokolle auf dem Datenträger, einsehbar, kopierbar, löschbar |

## Schnellstart

### Installation

Lade eine APK aus den GitHub Releases `android-v*` oder nutze das `azcode-magic-apk`-Artefakt aus GitHub Actions. Debug- und Release-Builds verwenden denselben Signaturschlüssel im Repository und können sich daher gegenseitig überschreiben.

### Ersteinrichtung

1. Öffne Einstellungen > Gerätefunktionen > Bedienungshilfen und aktiviere **AzCode Screen Control** in den Systemeinstellungen.
2. Gehe zu Einstellungen > Modellverwaltung, füge einen Anbieter hinzu, aktiviere ihn und gib einen API-Key ein. Basis-URL und Modell können Vorlagen verwenden.
3. Kehre zum Hauptbildschirm zurück und bestätige das aktive Modell in der Modellauswahl.
4. Gib eine Aufgabe ein und sende sie, zum Beispiel „Einstellungen öffnen und Android-Version anzeigen“.

### Berechtigungen

| Berechtigung | Zweck |
| --- | --- |
| Bedienungshilfe-Dienst | Bildschirm lesen, Tippen, Wischen, Systemnavigation, Texteingabe |
| Mikrofon | Spracheingabe und schwebender Assistent |
| Benachrichtigungen | Aufgabenabschluss- und Fragemeldungen |
| Über anderen Apps anzeigen | Schein des schwebenden Assistenten |
| Kamera | Foto-Anhänge |
| Termux RUN_COMMAND | Vollständige Linux-Befehle über Termux ausführen |
| Shizuku | Systembefehle als adb ausführen |

### Aus dem Quellcode bauen

Erforderlich sind JDK 17 und das Android SDK. Das Repository enthält kein `gradle-wrapper.jar`, verwende daher das System-`gradle`:

```bash
gradle assembleDebug
gradle assembleRelease
```

Nach einem Push baut `.github/workflows/android.yml` Debug- und Release-APKs auf GitHub Actions. Ein `android-v*`-Tag erstellt ein GitHub Release.

## Gerätefunktionen und Befehlsausführung

| Modus | Kanal | Identität | Voraussetzung | Timeout |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | Termux-uid | Termux installiert, RUN_COMMAND erteilt, `allow-external-apps=true` | 90 s |
| NORMAL + integrierte Shell | `/system/bin/sh -c` | App-uid | Keine | 60 s |
| SHIZUKU | Shizuku binder | adb uid 2000 | Shizuku gestartet und autorisiert | 60 s |
| ROOT | `su -c` | uid 0 | Gerät gerootet | 60 s |

Der NORMAL-Modus versucht zuerst Termux und fällt auf die integrierte Shell zurück. Der Zugriff auf private Verzeichnisse anderer Apps oder das Ändern von Systemeinstellungen erfordert Shizuku oder Root.

## Lokale Daten und Speicherung

AzCode verwendet kein SQLite, Room oder Server-Datenbank. Die Datenschicht besteht aus app-privaten `SharedPreferences` und JSON-Dateien:

| Pfad | Inhalt |
| --- | --- |
| `SharedPreferences` | Modellanbieter, Sitzungscursor, Skills, Befehlsgedächtnis, GitHub-/GenOffice-Konfiguration, Sprach- und Berechtigungsmodus |
| `files/sessions/<id>.json` | Chat-Verlauf und Agent-Kontext einer Sitzung |
| `files/memory.json` | Langzeitgedächtnis-Einträge |
| `files/logs/YYYY-MM-DD.log` | Täglich rotierende Protokolle, bis zu 10 Dateien, 512 KB pro Datei |
| `files/genoffice/` | Erzeugte Office-Dokumente |
| `cache/az_images/`, `cache/captures/` | Bild-Cache und temporäre Fotodateien |

Sitzungen werden über temporäre Datei plus Umbenennen geschrieben. Der Agent-Kontext behält höchstens 40 Nachrichten, ältere Inhalte werden zusammengefasst. Deinstallieren löscht alle lokalen Daten.

## Funktionsbrücke und Listen-CLI

Die Brücke lauscht nur auf `127.0.0.1:8848`, ist aus dem Internet nicht erreichbar und hat keine Authentifizierung. Fernzugriff erfordert `adb forward`.

| Methode | Pfad | Beschreibung |
| --- | --- | --- |
| GET | `/health` | Status von Bedienungshilfe, Shizuku, Root und Berechtigungsmodus |
| GET | `/screen` | Sichtbare Bildschirmknoten, bis zu 200 |
| POST | `/tap` | `{"x":123,"y":456}` oder `{"text":"OK"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`, unterstützt back / home / recents / notifications |
| POST | `/shell` | `{"cmd":"pm list packages"}`, im aktuellen Berechtigungsmodus ausgeführt |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"optionale id"}`, liefert `answer` und `events` |

Mit per adb verbundenem Gerät:

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "Einstellungen öffnen und Android-Version anzeigen"
listen screen
listen tap --text "OK"
```

## Datenschutz und Sicherheit

- API-Keys, GitHub-Token, GenOffice-Token und Xunfei-Zugangsdaten liegen nur im app-privaten Speicher; die App liest keine Umgebungsvariablen.
- Lokale Personalisierungs-Snapshots entstehen auf dem Gerät und werden nie in Cloud-Gespräche eingefügt.
- Die Brücke bindet nur an Loopback; Fernzugriff beruht auf adb-Weiterleitung.
- Die Online-Erkennung von Xunfei sendet Audio an die offene Plattform von Xunfei; die integrierte Systemerkennung bevorzugt Offline-Verarbeitung auf dem Gerät.
- Shizuku und Root können Systembefehle ausführen; Fehler können Gerät oder Daten beschädigen. Nur auf Geräten verwenden, die du besitzt oder für die du ausdrücklich autorisiert bist.

## Bekannte Einschränkungen

- Bildschirmlesen und UI-Steuerung hängen vom Bedienungshilfe-Dienst ab, den manche Hersteller im Hintergrund einschränken.
- Systemweiter Shell-Zugriff hängt von Shizuku oder Root ab; der NORMAL-Modus ist durch die App-Sandbox begrenzt.
- Audio- und Bildeingabe hängen von der multimodalen Fähigkeit des gewählten Modells ab.
- Bildgenerierung hängt von Anbieter-APIs ab, die unterstützte Größen und Anzahlen unterscheiden sich.
- Keine Cloud-Synchronisierung; Daten werden mit der App entfernt.

## Links

- Vollständige Dokumentation: `README.md` im Wurzelverzeichnis
- Produktübersicht: `PRODUCT.md`
- Designsystem: `DESIGN.md`
- CLI-Werkzeuge: `tools/listen` und `tools/install.sh`

## Lizenz

MIT. Die Module für Bedienungshilfe und Shizuku-Ausführung sind angelehnt an [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) (MIT). Die Shizuku-API stammt von [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API).
