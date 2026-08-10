import struct, sys, collections

PATH = "/private/tmp/claude-501/-Users-mkyu-Desktop-SOPT-solply-server/0e364f62-3437-4013-82c8-af307e8aa0ed/scratchpad/skeleton.hprof"

TSIZE = {2: None, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}

data = open(PATH, "rb").read()
# header
i = data.index(b"\0") + 1
IDSIZE = struct.unpack_from(">I", data, i)[0]
i += 4 + 8
assert IDSIZE == 8, IDSIZE
TSIZE[2] = IDSIZE
HDR_END = i


def rid(off):
    return struct.unpack_from(">Q", data, off)[0]


strings = {}          # id -> str
class_name_by_objid = {}
class_dump = {}       # objid -> dict(super=, ifields=[(name,type)], size=)

ROOT_SIZES = {
    0xFF: IDSIZE, 0x01: IDSIZE * 2, 0x02: IDSIZE + 8, 0x03: IDSIZE + 8,
    0x04: IDSIZE + 4, 0x05: IDSIZE, 0x06: IDSIZE + 4, 0x07: IDSIZE,
    0x08: IDSIZE + 8,
}


def skip_class_dump(p):
    p += IDSIZE + 4 + IDSIZE * 6 + 4
    cpc = struct.unpack_from(">H", data, p)[0]; p += 2
    for _ in range(cpc):
        p += 2
        t = data[p]; p += 1
        p += TSIZE[t]
    sfc = struct.unpack_from(">H", data, p)[0]; p += 2
    for _ in range(sfc):
        p += IDSIZE
        t = data[p]; p += 1
        p += TSIZE[t]
    ifc = struct.unpack_from(">H", data, p)[0]; p += 2
    p += ifc * (IDSIZE + 1)
    return p


def parse_class_dump(p):
    objid = rid(p); p += IDSIZE
    p += 4
    superid = rid(p); p += IDSIZE
    p += IDSIZE * 5
    size = struct.unpack_from(">I", data, p)[0]; p += 4
    cpc = struct.unpack_from(">H", data, p)[0]; p += 2
    for _ in range(cpc):
        p += 2
        t = data[p]; p += 1
        p += TSIZE[t]
    sfc = struct.unpack_from(">H", data, p)[0]; p += 2
    statics = []
    for _ in range(sfc):
        nid = rid(p); p += IDSIZE
        t = data[p]; p += 1
        raw = data[p:p + TSIZE[t]]; p += TSIZE[t]
        statics.append((nid, t, raw))
    ifc = struct.unpack_from(">H", data, p)[0]; p += 2
    ifields = []
    for _ in range(ifc):
        nid = rid(p); p += IDSIZE
        t = data[p]; p += 1
        ifields.append((nid, t))
    class_dump[objid] = {"super": superid, "ifields": ifields, "size": size,
                         "statics": statics}
    return p


def iter_segment(start, end, handler):
    p = start
    while p < end:
        tag = data[p]; p += 1
        if tag in ROOT_SIZES:
            p += ROOT_SIZES[tag]
        elif tag == 0x20:
            p = handler("class", p)
        elif tag == 0x21:
            objid = rid(p)
            cid = rid(p + IDSIZE + 4)
            nb = struct.unpack_from(">I", data, p + IDSIZE + 4 + IDSIZE)[0]
            body = p + IDSIZE + 4 + IDSIZE + 4
            handler("inst", (objid, cid, body, nb))
            p = body + nb
        elif tag == 0x22:
            objid = rid(p)
            n = struct.unpack_from(">I", data, p + IDSIZE + 4)[0]
            acid = rid(p + IDSIZE + 8)
            body = p + IDSIZE + 8 + IDSIZE
            handler("objarr", (objid, acid, n, body))
            p = body + n * IDSIZE
        elif tag == 0x23:
            objid = rid(p)
            n = struct.unpack_from(">I", data, p + IDSIZE + 4)[0]
            t = data[p + IDSIZE + 8]
            body = p + IDSIZE + 9
            handler("primarr", (objid, t, n))
            p = body + n * TSIZE[t]
        else:
            raise SystemExit("unknown subtag %02x at %d" % (tag, p - 1))
    return p


# ---------- pass 1: strings, load_class, class dumps ----------
def h1(kind, arg):
    if kind == "class":
        return parse_class_dump(arg)
    return None


i = HDR_END
while i < len(data):
    tag = data[i]
    ln = struct.unpack_from(">I", data, i + 5)[0]
    body = i + 9
    if tag == 0x01:
        strings[rid(body)] = data[body + IDSIZE:body + ln].decode("utf-8", "replace")
    elif tag == 0x02:
        class_name_by_objid[rid(body + 4)] = strings.get(rid(body + 4 + IDSIZE + 4))
    elif tag in (0x0C, 0x1C):
        iter_segment(body, body + ln, h1)
    i = body + ln

# fix names (string record may come after load_class); rebuild
i = HDR_END
while i < len(data):
    tag = data[i]
    ln = struct.unpack_from(">I", data, i + 5)[0]
    body = i + 9
    if tag == 0x02:
        class_name_by_objid[rid(body + 4)] = strings.get(rid(body + 4 + IDSIZE + 4))
    i = body + ln

by_name = {}
for oid, nm in class_name_by_objid.items():
    by_name.setdefault(nm, []).append(oid)

TARGETS = {
    "org/sopt/solply_server/domain/place/cache/PlaceSkeleton": "skel",
    "org/sopt/solply_server/domain/place/cache/PlaceSkeletonSnapshot": "snap",
    "java/lang/String": "str",
    "java/util/ImmutableCollections$MapN": "mapn",
    "java/lang/Long": "long",
}
tgt_cid = {}
for nm, key in TARGETS.items():
    for oid in by_name.get(nm, []):
        tgt_cid[oid] = key
    if not by_name.get(nm):
        print("MISSING class", nm)


def full_fields(cid):
    out = []
    c = cid
    while c and c in class_dump:
        out.extend(class_dump[c]["ifields"])
        c = class_dump[c]["super"]
    return out


layouts = {}
for cid, key in tgt_cid.items():
    layouts[cid] = [(strings.get(n), t) for n, t in full_fields(cid)]

# ---------- pass 2 ----------
skels = []          # (name_id, imageUrl_id, mainTag_id)
str_val = {}        # string objid -> (value_arr_id, coder)
barr_len = {}       # byte[] objid -> length
oarr_len = {}       # Object[] objid -> (length, class)
snap_maps = []
mapn_tables = {}    # mapn objid -> table arr id
long_vals = set()


def read_fields(cid, body, want):
    p = body
    res = {}
    for nm, t in layouts[cid]:
        sz = TSIZE[t]
        if nm in want:
            if t == 2:
                res[nm] = rid(p)
            elif t == 11:
                res[nm] = struct.unpack_from(">q", data, p)[0]
            elif t == 8:
                res[nm] = data[p]
            elif t == 10:
                res[nm] = struct.unpack_from(">i", data, p)[0]
        p += sz
    return res


def h2(kind, arg):
    if kind == "class":
        return skip_class_dump(arg)
    if kind == "inst":
        objid, cid, body, nb = arg
        key = tgt_cid.get(cid)
        if key == "skel":
            r = read_fields(cid, body, {"name", "imageUrl", "mainTagName", "id", "townId"})
            skels.append((r.get("name"), r.get("imageUrl"), r.get("mainTagName")))
        elif key == "str":
            r = read_fields(cid, body, {"value", "coder"})
            str_val[objid] = (r.get("value"), r.get("coder"))
        elif key == "snap":
            r = read_fields(cid, body, {"map"})
            snap_maps.append(r.get("map"))
        elif key == "mapn":
            r = read_fields(cid, body, {"table"})
            mapn_tables[objid] = r.get("table")
        elif key == "long":
            long_vals.add(objid)
    elif kind == "objarr":
        objid, acid, n, body = arg
        oarr_len[objid] = n
    elif kind == "primarr":
        objid, t, n = arg
        if t == 8:
            barr_len[objid] = n
    return None


i = HDR_END
while i < len(data):
    tag = data[i]
    ln = struct.unpack_from(">I", data, i + 5)[0]
    body = i + 9
    if tag in (0x0C, 0x1C):
        iter_segment(body, body + ln, h2)
    i = body + ln

print("PlaceSkeleton instances:", len(skels))
print("String instances:", len(str_val))
print("snapshot.map ids:", snap_maps, [class_name_by_objid.get(0)])


def align8(x):
    return (x + 7) // 8 * 8


def report(label, ids):
    ids_nonnull = [x for x in ids if x]
    distinct = set(ids_nonnull)
    nnull = len(ids) - len(ids_nonnull)
    # string shell 24B each (hdr12 + ref4 + int4 + byte1 + bool1 -> 24 after align)
    tot_shell = 0
    tot_arr = 0
    coders = collections.Counter()
    lens = []
    for sid in distinct:
        v = str_val.get(sid)
        if v is None:
            continue
        arr, coder = v
        coders[coder] += 1
        L = barr_len.get(arr, 0)
        lens.append(L)
        tot_shell += 24
        tot_arr += align8(16 + L)
    print(f"\n[{label}] refs={len(ids)} null={nnull} distinct_instances={len(distinct)}")
    print(f"  coder counts (0=LATIN1,1=UTF16): {dict(coders)}")
    if lens:
        print(f"  byte[] len avg={sum(lens)/len(lens):.2f} min={min(lens)} max={max(lens)}")
    print(f"  distinct String shells={tot_shell}B  byte[]={tot_arr}B  sum={tot_shell+tot_arr}B")
    return tot_shell + tot_arr


a = report("name", [s[0] for s in skels])
b = report("imageUrl", [s[1] for s in skels])
c = report("mainTagName", [s[2] for s in skels])

print("\n=== map structure ===")
for m in set(snap_maps):
    nm = class_name_by_objid.get(0)
    print("map objid", m, "-> table arr", mapn_tables.get(m), "len", oarr_len.get(mapn_tables.get(m)))

print("\nPlaceSkeleton shells:", len(skels) * 40)
print("String+byte[] (distinct) total:", a + b + c)

# ---- verify MapN table contents ----
tarr = mapn_tables.get(snap_maps[0])
# re-scan to grab that array's elements
elems = None
def h3(kind, arg):
    global elems
    if kind == "class":
        return skip_class_dump(arg)
    if kind == "objarr":
        objid, acid, n, body = arg
        if objid == tarr:
            elems = [rid(body + k*IDSIZE) for k in range(n)]
    return None
i = HDR_END
while i < len(data):
    tag = data[i]
    ln = struct.unpack_from(">I", data, i + 9 - 4)[0]
    body = i + 9
    if tag in (0x0C, 0x1C):
        iter_segment(body, body + ln, h3)
    i = body + ln
nz = [e for e in elems if e]
keys = set(e for e in nz if e in long_vals)
skelset = set()
print("\ntable len", len(elems), "nonnull", len(nz), "Long keys(distinct)", len(keys))
