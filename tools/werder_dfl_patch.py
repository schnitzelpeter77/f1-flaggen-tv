#!/usr/bin/env python3
"""Patcht /config/www/werder.html: Formation bevorzugt aus der offiziellen DFL-Angabe
(sensor.werder_dfl_formation, Format 'heim|gast|YYYY-MM-DD'), sonst ESPN. Sicherung vorher."""
import sys, shutil, datetime
pfad = sys.argv[1] if len(sys.argv) > 1 else "/config/www/werder.html"
s = open(pfad, encoding="utf-8").read()
if "function dflFormation(" in s:
    print("schon gepatcht"); sys.exit(0)
ers = [
    ("  const linien = String(r.formation || '').split('-').map(Number).filter(n => n > 0);",
     "  const formation = dflFormation(team) || r.formation || '';\n"
     "  const linien = String(formation).split('-').map(Number).filter(n => n > 0);"),
    ("  const form = r.formation ? `Formation ${esc(r.formation)}` : '';",
     "  const form = formation ? `Formation ${esc(formation)}${dflFormation(team) ? ' (DFL)' : ''}` : '';"),
    ("function renderLineup(", """// Offizielle DFL-Formation aus Home Assistant (sensor.werder_dfl_formation: 'heim|gast|Datum')
function dflFormation(team){
  try{
    const ha = window.parent && window.parent !== window && window.parent.document.querySelector('home-assistant');
    const st = ha && ha.hass && ha.hass.states['sensor.werder_dfl_formation'];
    if(!st || !state.event) return '';
    const [h, a, d] = String(st.state).split('|');
    if(!d) return '';
    const t = new Date(state.event.date);
    const tag = t.getFullYear() + '-' + String(t.getMonth() + 1).padStart(2, '0') + '-' + String(t.getDate()).padStart(2, '0');
    if(tag !== d) return '';
    const f = team.homeAway === 'home' ? h : a;
    return /^[0-9]+(-[0-9]+)+$/.test(f) ? f : '';
  }catch(e){ return ''; }
}
function renderLineup("""),
]
for alt, neu in ers:
    if s.count(alt) != 1:
        print("FEHLER: Stelle nicht eindeutig – nichts geaendert:", alt[:60]); sys.exit(1)
    s = s.replace(alt, neu, 1)
sicherung = pfad + ".bak-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
shutil.copy2(pfad, sicherung)
open(pfad, "w", encoding="utf-8").write(s)
print("OK – DFL-Formation eingebaut, Sicherung:", sicherung)
