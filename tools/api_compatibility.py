"""Offline binary-surface compatibility checks for DriverKit-owned public/protected classes.

Snapshots are reviewed artifacts, never auto-updated by a passing build. Generic signatures,
annotations, behavior, serialization and source-overload ambiguity need separate review.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

PUBLIC, PROTECTED, STATIC, FINAL, INTERFACE, ABSTRACT = 1, 4, 8, 16, 512, 1024
SEMANTIC = PUBLIC | PROTECTED | STATIC | FINAL | INTERFACE | ABSTRACT

class Reader:
    def __init__(self, data): self.data, self.offset = data, 0
    def take(self, count):
        end = self.offset + count
        if end > len(self.data): raise ValueError("Truncated class file")
        value, self.offset = self.data[self.offset:end], end
        return value
    def u1(self): return self.take(1)[0]
    def u2(self): return int.from_bytes(self.take(2), "big")
    def u4(self): return int.from_bytes(self.take(4), "big")

def class_surface(data):
    r = Reader(data)
    if r.u4() != 0xCAFEBABE: raise ValueError("Invalid class file")
    r.u2(); r.u2()
    pool = [None] * r.u2()
    index = 1
    while index < len(pool):
        tag = r.u1()
        if tag == 1: pool[index] = (tag, r.take(r.u2()).replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogatepass"))
        elif tag in (7, 8, 16, 19, 20): pool[index] = (tag, r.u2())
        elif tag in (3, 4): pool[index] = (tag, r.take(4).hex())
        elif tag in (5, 6): pool[index] = (tag, r.take(8).hex()); index += 1
        elif tag in (9, 10, 11, 12, 17, 18): pool[index] = (tag, r.u2(), r.u2())
        elif tag == 15: pool[index] = (tag, r.u1(), r.u2())
        else: raise ValueError("Unknown constant pool tag")
        index += 1
    def text(index): return pool[index][1]
    def name(index): return text(pool[index][1]) if index else None
    def attributes():
        result = {}
        for _ in range(r.u2()):
            attribute_name = text(r.u2()); result[attribute_name] = r.take(r.u4())
        return result
    flags, own, parent = r.u2(), r.u2(), r.u2()
    interfaces = sorted(name(r.u2()) for _ in range(r.u2()))
    def members():
        result = {}
        for _ in range(r.u2()):
            access, member_name, descriptor = r.u2(), text(r.u2()), text(r.u2())
            attrs = attributes()
            if not access & (PUBLIC | PROTECTED): continue
            entry = {"flags": access & SEMANTIC}
            if "ConstantValue" in attrs:
                value = pool[int.from_bytes(attrs["ConstantValue"], "big")]
                entry["constant"] = [value[0], text(value[1]) if value[0] == 8 else value[1]]
            result[member_name + descriptor] = entry
        return result
    fields, methods = members(), members()
    attrs = attributes()
    if "InnerClasses" in attrs:
        inner = Reader(attrs["InnerClasses"])
        for _ in range(inner.u2()):
            inner_index = inner.u2(); inner.u2(); inner.u2(); access = inner.u2()
            if inner_index == own: flags = (flags & ~(PUBLIC | PROTECTED | STATIC | FINAL | ABSTRACT)) | (access & SEMANTIC)
    return name(own), {"flags": flags & SEMANTIC, "super": name(parent), "interfaces": interfaces,
                       "fields": fields, "methods": methods}

def snapshot(jar):
    jar = Path(jar)
    if jar.stat().st_size > 128 * 1024 * 1024: raise ValueError("Oversized API input JAR")
    classes = {}
    with zipfile.ZipFile(jar) as archive:
        entries = [e for e in archive.infolist() if e.filename.startswith("io/github/samhom/driverkit/") and e.filename.endswith(".class")]
        if len(entries) > 10000: raise ValueError("Too many API classes")
        for entry in entries:
            if entry.file_size > 16 * 1024 * 1024: raise ValueError("Oversized API class")
            name, surface = class_surface(archive.read(entry))
            if surface["flags"] & (PUBLIC | PROTECTED): classes[name] = surface
    for name in list(classes):
        if any(name.split("$")[0] + "$".join([""] + name.split("$")[1:i]) not in classes for i in range(1, len(name.split("$")))):
            classes.pop(name)
    return {"format": 1, "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "classes": dict(sorted(classes.items()))}

def incompatibilities(before, after):
    failures = []
    for name, old in before["classes"].items():
        new = after["classes"].get(name)
        if new is None: failures.append("Removed API class: " + name); continue
        if old["super"] != new["super"]: failures.append("Changed superclass: " + name)
        if not set(old["interfaces"]) <= set(new["interfaces"]): failures.append("Removed interface: " + name)
        if not old["flags"] & FINAL and set(new["interfaces"]) - set(old["interfaces"]):
            failures.append("Added inherited interface obligation: " + name)
        # Making a class final/abstract or changing class/interface shape breaks existing consumers.
        if new["flags"] & (FINAL | ABSTRACT) & ~old["flags"] or (old["flags"] ^ new["flags"]) & INTERFACE:
            failures.append("Incompatible class flags: " + name)
        if old["flags"] & PUBLIC and not new["flags"] & PUBLIC: failures.append("Reduced class visibility: " + name)
        for category in ("fields", "methods"):
            for member, prior in old[category].items():
                current = new[category].get(member)
                if current is None: failures.append("Removed " + category + ": " + name + "." + member); continue
                a, b = prior["flags"], current["flags"]
                if (a ^ b) & STATIC or b & (FINAL | ABSTRACT) & ~a or a & PUBLIC and not b & PUBLIC:
                    failures.append("Incompatible member flags: " + name + "." + member)
                if prior.get("constant") != current.get("constant"):
                    failures.append("Changed compile-time constant: " + name + "." + member)
        if not old["flags"] & FINAL:
            for member, current in new["methods"].items():
                if member not in old["methods"] and current["flags"] & ABSTRACT:
                    failures.append("Added abstract obligation: " + name + "." + member)
    return failures

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write-baseline", type=Path)
    group.add_argument("--baseline", type=Path)
    parser.add_argument("--label", default="reviewed development baseline")
    args = parser.parse_args()
    current = snapshot(args.jar)
    if args.write_baseline:
        current["label"] = args.label
        args.write_baseline.parent.mkdir(parents=True, exist_ok=True)
        args.write_baseline.write_text(json.dumps(current, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        print("Wrote reviewable API baseline for", len(current["classes"]), "classes")
    else:
        previous = json.loads(args.baseline.read_text(encoding="utf-8"))
        if previous.get("format") != 1: raise ValueError("Unsupported API baseline format")
        failures = incompatibilities(previous, current)
        if failures:
            raise SystemExit("\n".join(failures))
        print("API compatibility passed:", len(previous["classes"]), "baseline classes")
if __name__ == "__main__": main()
