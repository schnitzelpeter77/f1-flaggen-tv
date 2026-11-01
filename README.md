# F1 Flaggen-Erkennung (Android TV)

Liest das TV-Bild per Bildschirmaufnahme und Texterkennung aus und meldet
Rennleitungs-Einblendungen an Home Assistant (Webhook, lokal):

| Einblendung im Bild | Ereignis |
|---|---|
| SAFETY CAR … | `SC` |
| VIRTUAL SAFETY CAR / VSC DEPLOYED | `VSC` |
| RED FLAG | `RED` |
| YELLOW FLAG / DOUBLE YELLOW | `YELLOW` |
| TRACK CLEAR / GREEN FLAG | `CLEAR` |
| CHEQUERED FLAG | `FINISH` |

Zusätzlich: `started`, `stopped`, `test`, `debug` (erkannter Text), `black_screen` (Bild schwarz → Kopierschutz).

Webhook: `POST <HA>/api/webhook/<id>` mit JSON `{"event": "...", "text": "...", "ts": ...}`.

ADB-Steuerung:

```
am start -n de.wolff.f1flaggen/.MainActivity --ez autostart true
am start -n de.wolff.f1flaggen/.MainActivity --ez stop true
am start -n de.wolff.f1flaggen/.MainActivity --es ha_url http://IP:8123 --es webhook f1_tv_bild
```

Gebaut wird automatisch per GitHub Actions; die APK liegt im Release `latest`.
