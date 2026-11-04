"""Supplement the Maven graph SBOM with packaged embedded Maven coordinates; no network requests."""
import json
from pathlib import Path
import urllib.parse
from audit_dependencies import coordinates

if __name__=="__main__":
    root=Path(__file__).resolve().parents[1]
    packages,_,embedded=coordinates(root,root/".m2-cache",False)
    components=[]
    for coordinate,version in packages:
        group,artifact=coordinate.split(":",1)
        purl="pkg:maven/"+urllib.parse.quote(group,safe="")+"/"+urllib.parse.quote(artifact,safe="")+"@"+urllib.parse.quote(version,safe="")
        components.append({"type":"library","group":group,"name":artifact,"version":version,"purl":purl})
    (root/"target/driverkit-embedded-sbom.json").write_text(json.dumps({"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
      "metadata":{"properties":[{"name":"driverkit:inventory","value":"resolved-and-packaged-embedded-coordinates"}]},
      "components":components},indent=2)+"\n",encoding="utf-8")
    print("Resolved/embedded coordinates:",len(components),"embedded detections:",embedded)
