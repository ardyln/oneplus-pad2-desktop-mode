# SPDX-License-Identifier: GPL-3.0-or-later
"""Map framework resource IDs hard-coded in the launcher (Google build) to PixelOS IDs.

Model: PixelOS = Google + the resources declared in PixelOS custom_symbols.xml.
aapt2 gives private resources IDs in sorted order per type, so a resource's
Google ID is its PixelOS ID minus the custom resources of the same type that
sort before it.

usage: remap-resource-ids.py fwres.txt custom_symbols.xml ids.txt

  fwres.txt: `aapt2 dump resources framework-res.apk` from the device
  custom_symbols.xml: from the ROM's frameworks/base/core/res/res/values/
  ids.txt: "<0xID> <count>" per framework-range constant in the APK, e.g. from
           dexdump -d classes*.dex (grep "const v.*// #01......$")

Every hit must still be checked against the code that uses it: the model was
wrong for strings in this ROM.
"""
import re
import sys

fwres, customs_xml, ids_file = sys.argv[1:4]

by_id = {}       # id -> (type, name, public)
type_ids = {}    # type name -> type id byte
cur_type = None
for line in open(fwres):
    m = re.match(r'\s+type (\S+) id=([0-9a-f]+)', line)
    if m:
        cur_type = m.group(1)
        type_ids.setdefault(cur_type, set()).add(int(m.group(2), 16))
        continue
    m = re.match(r'\s+resource (0x01[0-9a-f]{6}) (\S+?)/(\S+)( PUBLIC)?', line)
    if m:
        by_id[int(m.group(1), 16)] = (m.group(2), m.group(3), bool(m.group(4)))

customs = set(re.findall(r'<java-symbol type="([a-z]+)" name="([^"]+)"',
                         open(customs_xml).read()))
custom_ids = sorted(i for i, (t, n, p) in by_id.items() if (t, n) in customs)
missing = customs - {(by_id[i][0], by_id[i][1]) for i in custom_ids}
print(f"customs declared {len(customs)}, found in framework-res {len(custom_ids)}"
      + (f", missing {sorted(missing)}" if missing else ""))

def google_id(rid):
    """Google ID of a PixelOS resource, or None if it is PixelOS-only."""
    t, n, _ = by_id[rid]
    if (t, n) in customs:
        return None
    shift = sum(1 for c in custom_ids if c >> 16 == rid >> 16 and c < rid)
    return rid - shift

g2p = {}
for rid in by_id:
    g = google_id(rid)
    if g is not None:
        g2p[g] = rid

results = []
for line in open(ids_file):
    hexid, count = line.split()
    c = int(hexid.lstrip('#'), 16)
    if c >> 16 not in {0x0100 | t for s in type_ids.values() for t in s}:
        results.append((c, int(count), 'not-a-resource-type', None))
        continue
    p = g2p.get(c)
    if p is None:
        results.append((c, int(count), 'no-match', None))
    elif p == c:
        results.append((c, int(count), 'same', p))
    else:
        results.append((c, int(count), 'SHIFTED', p))

for c, count, verdict, p in results:
    if verdict == 'same':
        continue
    here = by_id.get(c)
    here = f"{here[0]}/{here[1]}" if here else "-"
    want = f"{by_id[p][0]}/{by_id[p][1]} @ {p:#010x}" if p else ""
    print(f"{c:#010x} x{count:<3} {verdict:20} PixelOS has here: {here:55} intended: {want}")
print("same:", sum(1 for r in results if r[2] == 'same'))
