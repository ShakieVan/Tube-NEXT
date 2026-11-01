# Datensparsames Teilen von Watch-Seiten

Stand: 01.11.2026

Ein Debug-A/B-Test mit vollstaendig deaktiviertem Ausblenden bestaetigte die
Kausalitaet: Mit sichtbarem YouTube-Share-Button waren alle Action-Pills wieder
vollstaendig abgerundet. Der native datensparsame Share-Button blieb bei diesem
Kontrolltest unveraendert aktiv.

## Ziel und Grenze

Tube NEXT bietet auf einer gueltigen YouTube-Watch-Seite einen eigenen
Teilen-Button in der nativen App-Leiste an. Der an Androids Teilen-Dialog
uebergebene Link enthaelt ausschliesslich die kanonische Watch-Adresse und die
Video-ID:

`https://www.youtube.com/watch?v=<VIDEO_ID>`

Parameter wie `si`, `feature`, `list`, `index`, `t` und weitere Kontextwerte
werden nicht uebernommen. Ausserhalb einer unterstuetzten Watch-URL oder ohne
gueltigen `v`-Wert bleibt der native Button ausgeblendet. `youtu.be`-Links
werden vor dem Teilen in dieselbe kanonische Form ueberfuehrt.

Eine Startzeit kommt nur hinzu, wenn der Nutzer sie im Zwischendialog
ausdruecklich waehlt:

`https://www.youtube.com/watch?v=<VIDEO_ID>&t=3m2s`

## Zwischendialog mit aktueller Position

Seit v1.4.10: Der Tap auf den nativen Button fragt zuerst die Position des laufenden Players
ab. Gibt es eine Position groesser null, bietet `Video teilen` die Eintraege
`Ab aktueller Position (3:02)` und `Von Anfang an` an; erst danach oeffnet sich
Androids Teilen-Dialog. Gibt es keine, oeffnet sich der Teilen-Dialog wie bisher
direkt mit dem Link ohne Zeit, denn ein Dialog mit nur einer Wahl waere nutzlos.
Die Wiedergabe laeuft waehrend des Dialogs unveraendert weiter; geteilt wird der
Stand im Moment des Taps.

Die Position stammt nicht aus dem Checkpoint fuer die Wiederherstellung, denn
dieser ist waehrend der Wiedergabe bis zu fuenf Sekunden alt und der Dialog
zeigte dann eine andere Zeit als der Player. Stattdessen laeuft ein Austausch
ueber den vorhandenen nativen Port:

1. `GeckoBrowserEngine` sendet `WATCH_POSITION_REQUEST` mit einer `requestId`.
2. `content.js` besitzt den Port und antwortet mit `WATCH_POSITION`. Den Wert
   liefert `globalThis.tubeNextWatchPosition()` aus `watch-progress.js`; beide
   Inhaltsskripte teilen dieselbe, fuer die Seite unsichtbare Sandbox.
3. Der Wert durchlaeuft dieselbe Pruefung wie ein Checkpoint (`validatedPlayback`):
   Hauptplayer, passende Video-ID, keine Werbung, kein Live/DVR, kein laufendes
   Spulen, keine laufende SPA-Navigation. Sonst fehlen die Positionsfelder.

Der Austausch sendet keinen Checkpoint und veraendert weder Drossel noch Player.
Der Rueckruf kommt immer genau einmal: bei Antwort, nach 500 ms, bei fehlendem
Port oder wenn Port oder Tab verschwinden. Fehlt der Live-Wert, gilt der
Checkpoint desselben Videos (etwa waehrend einer Zwischenwerbung). Dabei wird
wie im Checkpoint auf die volle Sekunde abgerundet; ein beendetes Video gilt als
Position null.

Zeitformat im Link: `45s`, `3m2s`, `3m0s`, `1h0m5s`. Am 01.11.2026 auf dem
SM-G991B geprueft: Eine Watch-Seite mit `t=3m2s` startet bei 182 s, `t=0h1m5s`
bei 65 s; YouTube schreibt den Parameter dabei selbst in `t=182s` um.

Die Bereinigung kann verhindern, dass Tube NEXT unnoetige Parameter im
geteilten Link weitergibt. Sie macht keine Aussage darueber, welche Daten
YouTube beim Laden oder bei sonstiger Nutzung der offiziellen Seite erhebt.

## YouTube-Button auf der Watch-Seite

Die WebExtension blendet nur den Teilen-Renderer in der Aktionsleiste von
`ytd-watch-metadata #actions` aus. Sie erkennt klassische Renderer vorrangig
am semantischen YouTube-Endpunkt `shareEntityServiceEndpoint` beziehungsweise
`/share/get_share_panel`.

YouTubes am 08.08.2026 live beobachtete View-Model-Variante besitzt weder am
`yt-button-view-model` noch am eingeschlossenen `button-view-model` einen
auslesbaren Endpunkt. Fuer diese Variante wird der aeussere Layout-Wrapper
gezielt ueber `aria-label` (`Share` oder `Teilen`) erkannt. Die aktuelle
Share-Icon-Pfadsignatur dient als sprachunabhaengiger Fallback. Die Signatur
ist bewusst nur innerhalb der Watch-Aktionsleiste wirksam.

Der erkannte Wrapper wird nicht mit `display: none` und auch nicht mit Breite
null aus dem Layout entfernt. Beide Varianten liessen YouTubes verbleibende
Action-Pills am unteren Rand abschneiden. Auch das Beibehalten der echten
Wrapperhoehe bei gleichzeitiger Neutralisierung seiner horizontalen Belegung
reichte allein nicht aus. Der kontrollierte Gegenversuch mit sichtbarem
Share-Button zeigte dagegen sofort wieder vollstaendige Rundungen.

Die heutige Variante misst deshalb die echte Breite des Share-Wrappers, laesst
seine fuer YouTubes Zeilenberechnung relevante Hoehe unveraendert und
neutralisiert nur die horizontale Belegung mit einem gleich grossen negativen
`margin-inline-end`. `visibility: hidden` und deaktivierte Pointer-Events
entfernen Darstellung und Bedienbarkeit. Dadurch kann der folgende
Speichern-Button in den frei gewordenen Platz ruecken, ohne dass YouTubes
Hoehenberechnung ihren Referenz-Wrapper verliert. Da die effektive Flex-Breite
unter Gecko dennoch eine zu kleine Aktionszeile ausloest, werden die fuenf
betroffenen Container im Portrait-Watch zusaetzlich auf YouTubes normale
Buttonhoehe von 40 CSS-Pixeln gesetzt; ihr Overflow bleibt sichtbar.

Der kombinierte Stand wurde anschliessend auf dem Samsung SM-S928B mit
GeckoView geprueft: Der YouTube-Share-Button blieb verborgen, `Speichern`
rueckte in den freien Platz und Like/Dislike, Speichern sowie Mehr wurden
wieder mit vollstaendiger oberer und unterer Rundung dargestellt.

Ein `MutationObserver` betrachtet nur neu eingefuegte Elemente und wird nur
auf Watch-Seiten betrieben. Dadurch greift die Anpassung auch nach
YouTube-SPA-Navigation und spaet nachgeladenem DOM, ohne andere Seiten oder
andere Watch-Aktionen wie Like, Speichern und das Player-Einstellungsmenue zu
entfernen.

## Automatisierte Regression

`YouTubeNavigationPolicyTest` prueft:

1. Watch- und `youtu.be`-Links mit Tracking- und Kontextparametern ergeben
   exakt `https://www.youtube.com/watch?v=<VIDEO_ID>`.
2. Seiten ohne Video-ID und fremde Hosts liefern keine teilbare URL.
3. Eine gewaehlte Position ergibt zusaetzlich genau ein `t` im Format von oben;
   Position null ergibt den Link ohne `t`.

`WatchProgressTest` prueft, dass der Live-Wert vor dem Checkpoint gilt, nur
dasselbe Video zaehlt, Position null keinen Dialog ausloest und die Beschriftung
der Player-Uhr entspricht. `node --test tools/watch-progress.test.js` prueft,
dass die Abfrage die exakte Zeit liefert, nichts sendet und die Drossel nicht
beruehrt und dass sie bei Werbung, Live, Spulen, SPA-Navigation, Anfangs-null
und ausserhalb von Watch-Seiten nichts liefert.

## Manuelle Regression

1. Eine Watch-Seite mit zusaetzlichen Parametern wie `si`, `list` und `t`
   oeffnen und abspielen. Der native Teilen-Button muss sichtbar sein. Im Dialog
   muss die Zeit dem Player entsprechen. `Von Anfang an` muss nur den kanonischen
   Link mit `v` an Android uebergeben, `Ab aktueller Position` zusaetzlich `t`.
   `Abbrechen` darf nichts teilen, und zwei schnelle Taps duerfen nur einen
   Dialog oeffnen.
2. Zu Startseite, Suche und Kanal navigieren sowie Tabs zwischen Watch und
   Nicht-Watch wechseln. Der native Teilen-Button muss dort verschwinden und
   auf Watch-Seiten wieder erscheinen.
3. Auf der Watch-Seite pruefen, dass YouTubes eigener Teilen-Button verborgen
   ist, die verbleibenden Action-Pills vollstaendig abgerundet dargestellt
   werden und Like/Dislike, Speichern, Kommentare sowie Player-Einstellungen
   weiterhin funktionieren.
4. Innerhalb der YouTube-SPA zu einem anderen Video wechseln. Der ausgeblendete
   YouTube-Button und die neue Video-ID im nativen Share-Link muessen ohne
   Reload aktualisiert sein.
5. Die Pruefung mit deutscher und englischer YouTube-Oberflaeche wiederholen.
6. Einen Livestream und ein Video waehrend einer Werbung teilen. Beim Livestream
   muss sich Androids Teilen-Dialog direkt oeffnen; bei der Werbung darf nur die
   letzte echte Videoposition oder gar keine angeboten werden, nie die Werbezeit.

Am 01.11.2026 auf dem SM-G991B bestanden: laufende Wiedergabe, beide Eintraege,
`Abbrechen`, Doppeltap und direkter Teilen-Weg auf einer Seite ohne Video (per
Logausgabe in einem voruebergehenden Testbau, da das Handy gesperrt blieb). Der
Android-Teilen-Dialog selbst sowie die Punkte 2 bis 6 sind damit noch nicht
abgenommen.
