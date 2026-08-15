# Hintergrund-Audio und Player-Benachrichtigung

Stand: am 15.08.2026 gegen `master` und den aktuellen Android-Audiofokus-
Vertrag geprueft

## Zweck

Tube NEXT laesst die YouTube-Wiedergabe in einem Gecko-Tab weiterlaufen, wenn
die App in den Hintergrund wechselt. Eine pro Activity-Aufgabe gehaltene
Android-`MediaSession` spiegelt die Gecko-Mediensteuerung fuer System und
externe Geraete. Nur waehrend aktiver Hintergrundwiedergabe stellt ein
Foreground-Service die Android-Medienbenachrichtigung bereit.

Die Benachrichtigung ist kein eigenstaendiger Player. Sie spiegelt immer den
Zustand und die Steuerung des zugehoerigen Gecko-Tabs.

## MediaSession und Foreground-Service sind getrennt

Die native `MediaSession` gehoert zum `AndroidBackgroundAudioCoordinator` und
bleibt aktiv, solange Gecko fuer einen Tab eine aktive Mediensteuerung meldet.
Das gilt unabhaengig davon, ob Tube NEXT im Vordergrund oder Hintergrund ist,
die Wiedergabe gerade spielt oder pausiert ist und die MediaStyle-
Benachrichtigung sichtbar ist.

Der Coordinator wird ueber ein Activity-`ViewModel` gehalten. Dadurch bleibt
dieselbe native Session bei einem Activity-Neustart erhalten und wird erst bei
einem echten Ende der Aufgabe freigegeben.

Diese Trennung ist fuer Bluetooth- und Autoradio-Steuerung notwendig. Vor der
Korrektur wurde die einzige native Session zusammen mit dem
`BackgroundAudioService` im Vordergrund nach 750 Millisekunden sowie direkt
nach Pause zerstoert. Android meldete auf dem laufenden Testtelefon deshalb
bei eingehenden Bluetooth-Play-Befehlen `Media button session is null`.

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
  Tube-NEXT-Player-Zeile,
- die unsichtbare native MediaSession bleibt dennoch erhalten, solange Gecko
  den Tab steuern kann; externe Play-Befehle koennen die Wiedergabe deshalb
  wieder aufnehmen.

## MediaSession und externe Tasten

Die vom Coordinator gehaltene Android-`MediaSession` leitet Transportbefehle
an die Gecko-Mediensteuerung des zugeordneten Tabs weiter:

- Play,
- Pause,
- Stop,
- Vorlauf,
- Ruecklauf,
- naechster Titel,
- vorheriger Titel.

Direkte Media-Button-Ereignisse verwendet der Android-Standardhandler. Dadurch
wird insbesondere `PLAY_PAUSE` anhand des veroeffentlichten Zustands korrekt
als Play oder Pause behandelt. Position, Dauer, Titel, Interpret und Artwork
werden aus der Gecko-MediaSession an Android weitergegeben. Die Session ist
als lokale Medienwiedergabe mit `USAGE_MEDIA` und `CONTENT_TYPE_MOVIE`
deklariert.

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
Audio Focus an. Duckbare kurze Unterbrechungen wie normale Benachrichtigungstoene
werden von Android automatisch leiser gemischt; Tube NEXT pausiert Gecko dafuer
nicht mehr.

Bei einem transienten exklusiven Fokusverlust wird eine zuvor laufende
Wiedergabe normalerweise pausiert. Ein eingehender, noch nicht angenommener
Anruf ist davon ausgenommen: Manche Headset-Routen melden bereits beim Klingeln
`AUDIOFOCUS_LOSS_TRANSIENT`, waehrend der Lautsprecherpfad erst beim Annehmen
reagiert. Tube NEXT prueft deshalb nach einer kurzen Stabilisierungsfrist den
Audio-Modus und ignoriert den Fokusverlust in `MODE_RINGTONE`.

Erst der Wechsel in `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` oder einen
entsprechenden Redirect-Modus pausiert eine laufende Wiedergabe. Dieser
Moduswechsel wird ab Android 12 direkt ueber einen
`AudioManager.OnModeChangedListener` beobachtet und macht das Verhalten von der
Audio-Route unabhaengig. Waehlt der Nutzer waehrend des Gespraechs aktiv Play
oder startet ein anderes Video, darf die Wiedergabe wieder anlaufen; dieser
Eingriff hebt zugleich die sonst vorgemerkte automatische Wiederaufnahme auf.
Ohne solchen Eingriff wird die zuvor laufende Wiedergabe nach dem Gespraech
automatisch fortgesetzt. War sie vorher pausiert oder loest der Nutzer
waehrenddessen Pause beziehungsweise Stop aus, erfolgt keine automatische
Wiederaufnahme. Ein permanenter Fokusverlust ausserhalb eines aktiven
Gespraechs bleibt pausiert.

Eine bewusst im Tube-NEXT-Vordergrund gestartete Wiedergabe darf auch dann
laufen, wenn Android Audio Focus wegen eines bereits aktiven Telefon- oder
VoIP-Gespraechs (`MODE_IN_CALL` beziehungsweise `MODE_IN_COMMUNICATION`)
verweigert. Als aktive Handlung gilt ein MediaSession-Play-Befehl oder ein
Gecko-Play-Ereignis innerhalb von zehn Sekunden nach einer Bedienung in der
App. Diese Ausnahme gilt nicht fuer einen fokuslosen Start im Hintergrund und
nicht bei normaler Audiokonkurrenz. Eine danach in den Hintergrund wechselnde,
bereits laufende Wiedergabe bleibt unberuehrt. Android kann bereits vor dem
Anruf laufende Medien waehrend eines eingehenden Anrufs systemseitig
stummschalten; Tube NEXT kann diese Plattformgrenze nicht aufheben. Bei Pause,
Tab-Schliessen und Shutdown wird ein gehaltener Fokus weiterhin aufgegeben.

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
- Eine pausierte Notification wird absichtlich nicht persistent gehalten.
  Ob Android die aktive Session zusaetzlich in seiner Systemoberflaeche zeigt,
  entscheidet das Betriebssystem.
- Nach einem echten Prozess- oder Aufgabenende existiert auch die Gecko-Session
  nicht mehr. Externe Tasten stellen in diesem Fall absichtlich keinen
  eigenstaendigen Player wieder her.

## Historie

`v1.2.1` beziehungsweise Commit `659b5f6` fuehrte gemeinsam ein:

- tab-bewussten Wiedergabe- und Steuerungszustand,
- Bereinigung beim Schliessen des Media-Tabs,
- direkte MediaSession-/Media-Button-Steuerung,
- Pause bei `ACTION_AUDIO_BECOMING_NOISY`,
- Gecko-Reaktivierung nach einer externen Audio-Routen-Unterbrechung.

Am 09.08.2026 wurde die native MediaSession vom kurzlebigen
Benachrichtigungs-Service getrennt. Anlass war der auf dem Testtelefon
nachgewiesene Zustand, dass Bluetooth-Befehle ankamen, waehrend Tube NEXT im
Vordergrund keine Media-Button-Session besass.

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
7. Play, Pause, Play/Pause-Toggle, Stop, Vorlauf, Ruecklauf, Next und Previous
   ueber Bluetooth-/Systemtasten pruefen; dies muss auch im Vordergrund und
   nach einer extern ausgeloesten Pause funktionieren.
8. App mit aktiver und pausierter Wiedergabe beenden: Service, Wake-Lock und
   Notification duerfen nicht verwaist bleiben.
9. Eine Watch-Seite vor ihrer vorgesehenen Wiedergabebereitschaft oeffnen:
   Das blosse Erzeugen der nativen MediaSession darf keinen automatischen
   Videoanlauf ausloesen.
10. Bei einem kurzen Benachrichtigungston: Wiedergabe laeuft geduckt weiter und
    darf nicht pausiert zurueckbleiben.
11. Bei laufender Wiedergabe mit internem Lautsprecher und mit verbundenem
    Headset anrufen: Das Video muss waehrend des blossen Klingelns in beiden
    Faellen weiterlaufen. Erst beim Annehmen muss es pausieren. Ohne weiteren
    Eingriff wird es nach dem Anruf nur dann
    automatisch fortgesetzt, wenn es zuvor lief und nicht bewusst pausiert
    oder gestoppt wurde.
12. Waehrend eines aktiven Telefon- oder VoIP-Gespraechs Tube NEXT in den
    Vordergrund holen und aktiv das pausierte oder ein anderes Video starten:
    Die Wiedergabe darf trotz verweigertem Audio Focus anlaufen und nach dem
    Gespraech nicht ein zweites Mal automatisch gestartet werden. Ohne diesen
    Nutzereingriff bleibt das beim Anruf pausierte Video waehrend des
    Gespraechs stehen und wird erst nach dessen Ende automatisch fortgesetzt.
    Dieselbe Ausnahme darf im Hintergrund oder ohne aktives Gespraech nicht
    greifen.
