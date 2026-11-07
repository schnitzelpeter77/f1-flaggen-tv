#!/usr/bin/env python3
"""Patcht /config/www/werder.html: Aufstellung nach echter Formation (ESPN 'formation' + Positionen)
statt grober Reihen. Legt vorher eine Sicherung an. Mehrfaches Ausfuehren ist unschaedlich."""
import sys, shutil, datetime

pfad = sys.argv[1] if len(sys.argv) > 1 else "/config/www/werder.html"
s = open(pfad, encoding="utf-8").read()
if "function tiefe(" in s:
    print("schon gepatcht"); sys.exit(0)

alt = "  onPitch.forEach(p => { const g = group(p._for || p); (rows[g] = rows[g] || []).push(p); });"
if alt not in s:
    print("FEHLER: erwartete Zeile nicht gefunden – nichts geaendert"); sys.exit(1)

neu = """  // Reihen nach echter Formation (z. B. 4-2-3-1): Spieler nach Positionstiefe sortieren und auffuellen
  const linien = String(r.formation || '').split('-').map(Number).filter(n => n > 0);
  if (linien.length && linien.reduce((a, b) => a + b, 0) === onPitch.length - 1) {
    const sortiert = onPitch.slice().sort((a, b) => tiefe(a._for || a) - tiefe(b._for || b)
      || (Number(a.formationPlace) || 0) - (Number(b.formationPlace) || 0));
    rows[0] = [sortiert[0]];
    let k = 1;
    linien.forEach((anz, i) => { rows[i + 1] = sortiert.slice(k, k + anz); k += anz; });
  } else {
    onPitch.forEach(p => { const g = group(p._for || p); (rows[g] = rows[g] || []).push(p); });
  }"""

tiefe = """// Positionstiefe fuer die Formations-Reihen (ESPN/Opta-Kuerzel, z. B. CD-L, AM-R, CF-L)
function tiefe(p){
  const ab = (p.position?.abbreviation || p.position?.name || '').toUpperCase();
  const b = ab.replace(/-[LR]$/, '');
  if (b === 'G' || b === 'GK' || /GOAL/.test(b)) return 0;
  if (/^(CD|CB|SW)$/.test(b)) return 10;
  if (/^(LB|RB|D)$/.test(b) || /DEF/.test(b)) return 11;
  if (/^(LWB|RWB|WB)$/.test(b)) return 15;
  if (/^(DM|CDM)$/.test(b)) return 20;
  if (/^(CM|LM|RM|M)$/.test(b) || /MID/.test(b)) return 30;
  if (/^(AM|CAM|LW|RW|LAM|RAM)$/.test(b)) return 40;
  if (/^(SS|LF|RF)$/.test(b) || (b === 'CF' && ab !== b)) return 45; // z. B. CF-L/CF-R hinter der Spitze
  if (/^(F|CF|ST|FW)$/.test(b) || /FORW|STRIK/.test(b)) return 50;
  return 30;
}
"""
seite_alt = """function sideVal(p){
  const ab = (p.position?.abbreviation || '').toUpperCase();
  if(/-L$|^L/.test(ab)) return 0; if(/-R$|^R/.test(ab)) return 2; return 1;
}"""
seite_neu = """function sideVal(p){
  // 0 aussen links, 1 halblinks (CD-L, CM-L ...), 2 Mitte, 3 halbrechts, 4 aussen rechts
  const ab = (p.position?.abbreviation || '').toUpperCase();
  if (/^(LB|LWB|LM|LW|LAM|LF)$/.test(ab)) return 0;
  if (/^(RB|RWB|RM|RW|RAM|RF)$/.test(ab)) return 4;
  if (/-L$/.test(ab)) return 1;
  if (/-R$/.test(ab)) return 3;
  return 2;
}"""
if seite_alt not in s:
    print("FEHLER: sideVal nicht gefunden – nichts geaendert"); sys.exit(1)
s = s.replace(seite_alt, seite_neu, 1)
anker = "function renderLineup("
if anker not in s:
    print("FEHLER: renderLineup nicht gefunden – nichts geaendert"); sys.exit(1)

sicherung = pfad + ".bak-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
shutil.copy2(pfad, sicherung)
s = s.replace(alt, neu, 1).replace(anker, tiefe + anker, 1)
open(pfad, "w", encoding="utf-8").write(s)
print("OK – gepatcht, Sicherung:", sicherung)
