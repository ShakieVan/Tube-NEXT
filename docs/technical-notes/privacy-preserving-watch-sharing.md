# Datensparsames Teilen von Watch-Seiten

Stand: 08.08.2026

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

## Manuelle Regression

1. Eine Watch-Seite mit zusaetzlichen Parametern wie `si`, `list` und `t`
   oeffnen. Der native Teilen-Button muss sichtbar sein; der Android-Dialog
   muss nur den kanonischen Link mit `v` erhalten.
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
