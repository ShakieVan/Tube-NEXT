# Hintergrund-Audio und Player-Benachrichtigung

Stand: am 25.07.2026 gegen `master`, `v1.2.1` und den zugehoerigen
Projekt-Task geprueft

## Zweck

Tube NEXT laesst die YouTube-Wiedergabe in einem Gecko-Tab weiterlaufen, wenn
die App in den Hintergrund wechselt. Fuer diesen Zeitraum stellt ein
Foreground-Service eine Android-Medienbenachrichtigung und eine `MediaSession`
bereit.

Die Benachrichtigung ist kein eigenstaendiger Player. Sie spiegelt immer den
Zustand und die Steuerung des zugehoerigen Gecko-Tabs.

## Historischer Fehler

Vor `v1.2.1` konnte folgende Folge eine verwaiste, nicht wegwischbare
Medienbenachrichtigung erzeugen:

1. Ein Video spielte in einem Tab.
2. Der Tab wurde geschlossen.
3. Die App wechselte danach in den Hintergrund.
4. Der Audio-Koordinator verwendete noch den zuletzt gemeldeten Zustand
   `isPlaying = true` und startete damit erneut den Foreground-Service.

Die Gecko-Session war zu diesem Zeitpunkt bereits zerstoert. Deshalb zeigte
Android einen Player ohne dazugehoerige Wiedergabe.

Die Ursache war nicht primaer die Notification selbst, sondern fehlende
Besitzzuordnung im Audio-Zustand.

## Tab-gebundener Zustand

`AndroidBackgroundAudioCoordinator` speichert getrennt:

- `controls` und `controlsTabId` fuer die aktuellen Gecko-Medienbefehle,
- `lastState` und `lastStateTabId` fuer den zuletzt gemeldeten
  Wiedergabestatus.

Eine Notification wird im Hintergrund nur erzeugt, wenn:

- der letzte Zustand wirklich `isPlaying = true` meldet,
- Mediensteuerung vorhanden ist,
- Steuerung und Wiedergabestatus zum selben Tab gehoeren.

Pausenmeldungen eines anderen Tabs duerfen den gerade aktiven Wiedergabetab
nicht ueberschreiben.

## Schliessen und Suspendieren von Tabs

`MainActivity.closeTabById()` ruft
`backgroundAudioCoordinator.onTabClosing(tabId)` auf, bevor Gecko-View und
Gecko-Session entfernt beziehungsweise zerstoert werden.

Gehoert der Audio-Zustand zu diesem Tab, dann:

1. wird ein Stop-Befehl an seine Mediensteuerung gesendet,
2. werden Steuerung, Tab-IDs, Zustand, Notification-Snapshot und ein eventuell
   offener Wiederherstellungsmarker verworfen,
3. wird der Audio-Fokus aufgegeben,
4. wird der Foreground-Service gestoppt.

Dasselbe Grundprinzip gilt beim Suspendieren eines Tabs. Ein tatsaechlich
spielender Tab ist allerdings vor der Ressourcen-Hibernation geschuetzt; nur
ein nicht mehr aktiver Audio-Zustand wird dort bereinigt.

## Lebenszyklus der Benachrichtigung

Beim Wechsel der App in den Hintergrund wird die Notification nur fuer eine
aktuelle aktive Wiedergabe erzwungen. Im Vordergrund wird der Audio-Service
nach einer kurzen Uebergangsfrist beendet.

Wenn der Service einen pausierten Zustand erhaelt, entfernt er die
Foreground-Notification mit `STOP_FOREGROUND_REMOVE` und beendet sich. Dieses
Verhalten ist beabsichtigt: Besonders auf Samsung-Geraeten kann eine pausierte
MediaStyle-Zeile sonst nicht wegwischbar bleiben.

Daraus folgt eine bewusste Produktentscheidung:

- aktive Hintergrundwiedergabe besitzt eine System-Mediensteuerung,
- eine pausierte Wiedergabe besitzt keine dauerhaft sichtbare
  Tube-NEXT-Player-Zeile.

## MediaSession und externe Tasten

`BackgroundAudioService` leitet die Android-Transportbefehle an die
Gecko-Mediensteuerung des zugeordneten Tabs weiter:

- Play,
- Pause,
- Stop,
- Vorlauf,
- Ruecklauf.

Zusätzlich werden passende direkte Media-Button-Ereignisse verarbeitet. Die
Session ist als lokale Medienwiedergabe mit `USAGE_MEDIA` und
`CONTENT_TYPE_MOVIE` deklariert.

Ein partieller Wake-Lock und der Empfaenger fuer Audio-Routenwechsel sind nur
waehrend aktiver Wiedergabe eingeschaltet und werden beim Service-Ende
freigegeben.

## Bluetooth- und Headset-Trennung

Bei `AudioManager.ACTION_AUDIO_BECOMING_NOISY` pausiert der Service die
Wiedergabe. Das verhindert, dass Ton nach dem Verlust von Bluetooth oder eines
Kopfhoerers ploetzlich ueber den Geraetelautsprecher weiterlaeuft.

Diese Pause wird absichtlich ueber
`pauseForAudioRouteChange()` und nicht ueber den normalen Pause-Pfad
ausgefuehrt. Erfolgt sie im Hintergrund, setzt der Koordinator
`foregroundRecoveryPending`.

Beim naechsten `MainActivity.onResume()`:

1. wird der aktuelle Tab normal aktiviert,
2. wird der Wiederherstellungsmarker genau einmal verbraucht,
3. schaltet `recoverFromAudioRouteChange()` dieselbe Gecko-Session kurz
   inaktiv und unfokussiert,
4. nach 250 Millisekunden wird sie wieder aktiv und fokussiert.

Dabei wird die URL nicht neu geladen und der Tab nicht neu erzeugt. Der
gezielte Aktivierungsimpuls behebt den historisch beobachteten Zustand, in dem
der YouTube-Player nach einer externen Audio-Unterbrechung beim Zurueckkehren
in die App dauerhaft einen Ladekringel zeigte.

## Audio-Fokus

Vor dem Start beziehungsweise Fortsetzen fordert der Koordinator Android
Audio Focus an. Bei verweigertem Fokus oder Fokusverlust wird die Wiedergabe
pausiert und der interne Zustand auf nicht spielend gesetzt. Bei Pause,
Tab-Schliessen und Shutdown wird der Fokus wieder aufgegeben.

Der spezielle Gecko-Wiederherstellungsimpuls ist derzeit nur fuer einen
Audio-Routenwechsel vorgesehen. Ein allgemeiner Audio-Fokusverlust setzt
keinen solchen Marker.

## Grenzen und Wartungshinweise

- Die Notification basiert auf den von Gecko gemeldeten Zustands- und
  Steuerungsereignissen. Aenderungen in GeckoView koennen deren Reihenfolge
  oder Timing beeinflussen.
- Fuer einen kurzzeitigen Verlust der Mediensteuerung im Hintergrund besteht
  eine Schonfrist von fuenf Sekunden. Bleibt die Steuerung verschwunden, wird
  der Zustand verworfen.
- Die Wiederherstellung verwendet den beim Vordergrundwechsel aktuellen Tab.
  Der Marker speichert keine eigene Tab-ID. Das entspricht dem historischen
  Fehlerbild, sollte aber bei kuenftigen Mehrtab-Audio-Aenderungen erneut
  geprueft werden.
- Eine pausierte Notification absichtlich nicht persistent zu halten bedeutet,
  dass Androids Player-Uebersicht Tube NEXT nach einer Pause nicht als
  dauerhaft fortsetzbare Session anbietet.

## Historie

`v1.2.1` beziehungsweise Commit `659b5f6` fuehrte gemeinsam ein:

- tab-bewussten Wiedergabe- und Steuerungszustand,
- Bereinigung beim Schliessen des Media-Tabs,
- direkte MediaSession-/Media-Button-Steuerung,
- Pause bei `ACTION_AUDIO_BECOMING_NOISY`,
- Gecko-Reaktivierung nach einer externen Audio-Routen-Unterbrechung.

## Regressionstest

1. Video starten, den spielenden Tab schliessen und die App minimieren:
   Es darf keine Player-Benachrichtigung mehr erscheinen.
2. Video im Hintergrund laufen lassen: Notification und Transporttasten
   muessen den richtigen Tab steuern.
3. Einen anderen pausierten Tab wechseln oder schliessen: Die aktive
   Hintergrundwiedergabe darf nicht irrtuemlich ueberschrieben werden.
4. Pause in der Notification ausloesen: Wiedergabe stoppt und die
   Tube-NEXT-Player-Zeile wird entfernt.
5. Bluetooth oder Kabelkopfhoerer waehrend der Hintergrundwiedergabe trennen:
   Die Wiedergabe muss pausieren und darf nicht ueber den Lautsprecher
   weiterlaufen.
6. Nach diesem Routenwechsel die App oeffnen: Der bestehende YouTube-Tab muss
   ohne Reload bedienbar sein und darf nicht dauerhaft am Ladekringel haengen.
7. Play, Pause, Stop, Vorlauf und Ruecklauf ueber Bluetooth-/Systemtasten
   pruefen.
8. App mit aktiver und pausierter Wiedergabe beenden: Service, Wake-Lock und
   Notification duerfen nicht verwaist bleiben.
