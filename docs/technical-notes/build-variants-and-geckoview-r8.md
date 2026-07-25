# Buildvarianten, Signierung und GeckoView/R8

Stand: am 25.07.2026 gegen `master`, `v1.0.1` und `v1.0.2` geprueft

## Getrennte App-Identitaeten

Gradle erzeugt zwei absichtlich getrennte Installationen:

| Variante | Application-ID | sichtbarer Name |
|---|---|---|
| Debug | `de.shakie.tubenext.debug` | `Tube NEXT Debug` |
| Release | `de.shakie.tubenext` | `Tube NEXT` |

Der Debug-Suffix und Namensplatzhalter stehen ausschliesslich im
`debug`-Build-Type. Ein Release kann dadurch nicht versehentlich die
`.debug`-ID erben.

Beide Apps koennen parallel installiert sein. Android behandelt sie aber als
vollstaendig getrennte Apps mit eigenen:

- Gecko-Profilen,
- Cookies und Logins,
- Einstellungen,
- Tabs,
- Dateien und Update-Berechtigungen.

Eine alte Debug-APK, die noch die Release-ID verwendete, kann wegen
unterschiedlicher Signaturen mit dem Release kollidieren und muss gegebenenfalls
einmal entfernt werden.

## Release-Signierung

Private Werte kommen lokal aus der nicht versionierten Datei
`key.properties`; sie ist in `.gitignore` ausgeschlossen. Die referenzierte
JKS und Kennwoerter gehoeren nicht ins Repository oder in Projekt-Chats.

Ist eine eigene Release-Konfiguration vorhanden, verwendet Gradle sie. Ohne
lokale Release-Konfiguration kann zu Entwicklungszwecken weiterhin ein
Release-Build mit Debug-Key entstehen. Daher gilt fuer eine
Veroeffentlichung:

1. eigene Release-Signierung muss vorhanden sein,
2. APK-Signatur muss vor Upload geprueft werden,
3. ein nur erfolgreich gebautes `release`-Artefakt ist noch kein Beleg fuer
   die richtige Produktionssignatur.

## ABI-Aufteilung

Die App erzeugt keine Universal-APK, sondern vier ABI-spezifische Varianten:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`
- `x86`

Das reduziert die einzelne Downloadgroesse gegenueber einer APK mit allen
GeckoView-Nativbibliotheken. ARM-Varianten sind fuer reale Geraete,
x86-Varianten primaer fuer passende Emulatoren vorgesehen.

## R8-Historie

`v1.0.1` aktivierte R8-Minify und Resource-Shrinking. Der Build lief durch und
die arm64-APK wurde kleiner. Auf einem Galaxy S24 Ultra stuerzte diese
veroeffentlichte Variante jedoch unmittelbar beim Start nativ in
GeckoView/`libxul.so` ab.

Der entscheidende A/B-Test verwendete denselben Release-Stand ohne
Minifizierung. Diese APK startete auf demselben Geraet stabil. `v1.0.2`
deaktivierte deshalb beide Schalter wieder und ersetzte `v1.0.1`.

Der aktuelle verbindliche Stand ist:

```kotlin
isMinifyEnabled = false
isShrinkResources = false
```

Ein erfolgreicher Gradle-Build oder Emulatorstart reicht nicht, um diese
Entscheidung aufzuheben. Eine erneute Aktivierung benoetigt mindestens einen
signierten Release-Test auf realer arm64-Hardware und eine kontrollierte
Auswertung nativer Crash-Logs.

## ProGuard-Regeln

`app/proguard-rules.pro` enthaelt weiterhin `dontwarn`-Regeln fuer optionale
`java.beans`-Referenzen. Sie stammen aus dem damaligen R8-Versuch, sind bei
deaktiviertem Minify wirkungslos und duerfen nicht als Nachweis gelten, dass
GeckoView heute sicher minifiziert werden kann.

## BuildConfig und Logging

Die Generierung von `BuildConfig` ist explizit aktiviert. Eigene besonders
laute Debug-Logs werden an relevanten Stellen mit `BuildConfig.DEBUG`
begrenzt.

Das reduziert selbst erzeugtes Release-Logging, aber nicht Geckos, YouTubes
oder Androids Systemmeldungen. Ein grosser Logcat-Strom bei aktivem
WLAN-Debugging beweist daher weder allein einen App-Fehler noch den
Hauptverursacher des Akkuverbrauchs. Verbrauchsmessungen sollten mit der
signierten Release-App, ohne aktive ADB-Sitzung und ueber einen realistischen
Zeitraum erfolgen.

## Groessengrenze

Der groesste Anteil der APK stammt aus GeckoView und seinen nativen
Bibliotheken. Kotlin-/Resource-Shrinking spart im Vergleich dazu nur einen
begrenzten Anteil. Stabilitaet hat gemaess Projektprioritaet Vorrang vor
diesem Groessengewinn.

## Historie

- `v1.0.1`, Commit `c539285`: getrennte Debug-ID, BuildConfig und erster
  R8-/Shrink-Versuch.
- `v1.0.2`, Commit `64ff4c5`: Hotfix mit deaktiviertem Minify und
  Resource-Shrinking nach realem arm64-Startcrash.

## Release-Regressionstest

1. Debug bauen: Paket-ID und App-Name muessen den Debug-Suffix tragen.
2. Release bauen: Paket-ID muss exakt `de.shakie.tubenext` sein.
3. Debug und Release parallel installieren und getrennte Daten bestaetigen.
4. Release-Signatur gegen die erwartete Produktionssignatur pruefen.
5. Alle vier ABI-APKs bauen und Paket-/Versionsdaten kontrollieren.
6. arm64-Release auf realer Hardware installieren und mehrfach kalt starten.
7. Logcat/Crash-Buffer auf native Gecko-/`libxul.so`-Abstuerze pruefen.
8. R8 und Resource-Shrinking nicht ohne eine ausdrueckliche neue
   Hardware-Validierung aktivieren.
