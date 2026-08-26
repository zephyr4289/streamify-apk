#!/usr/bin/env python3
"""tools/write_fleet_config.py — turn canary probe output into fleet-config.json.

Reads probe-clients.sh tabular output and writes a fleet-config.json containing
the surviving clients (OK on both standard + licensed probes), preserving the
app's ladder order (ANDROID, ANDROID_VR, IOS). Exits non-zero when no survivor
exists so CI surfaces the rot instead of shipping an empty fleet.
"""
import json
import sys

# label in probe table -> full Innertube client spec (must mirror baked defaults)
KNOWN_SPECS = {
    "ANDROID": {
        "clientName": "ANDROID",
        "clientVersion": "21.26.364",
        "clientNumber": "3",
        "userAgent": "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        "osName": "Android",
        "osVersion": "11",
    },
    "IOS": {
        "clientName": "IOS",
        "clientVersion": "21.26.4",
        "clientNumber": "5",
        "userAgent": "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        "deviceMake": "Apple",
        "deviceModel": "iPhone16,2",
        "osName": "iPhone",
        "osVersion": "18.3.2.22D82",
    },
    "VR_1.60.19": {
        "clientName": "ANDROID_VR",
        "clientVersion": "1.60.19",
        "clientNumber": "28",
        "userAgent": "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
        "deviceMake": "Oculus",
        "deviceModel": "Quest 3",
        "osName": "Android",
        "osVersion": "12",
    },
}

ORDER = ["ANDROID", "VR_1.60.19", "IOS"]


def survivors(probe_path):
    ok_rows = {}
    with open(probe_path) as f:
        for line in f:
            parts = line.split()
            # CLIENT VIDEO STATUS AUDIO FMTS CIPHERED [REASON...]
            if len(parts) < 6 or parts[0] in ("CLIENT",):
                continue
            label, _vid, status, audio = parts[0], parts[1], parts[2], parts[3]
            if status == "OK" and audio.split("/")[0].isdigit() and int(audio.split("/")[0]) > 0:
                ok_rows.setdefault(label, 0)
                ok_rows[label] += 1
    # a survivor must pass BOTH probe videos
    return [label for label in ORDER if ok_rows.get(label, 0) >= 2]


def main():
    probe_path, out_path = sys.argv[1], sys.argv[2]
    winners = survivors(probe_path)
    if not winners:
        print("::error::no surviving clients — refusing to write an empty fleet")
        sys.exit(1)

    config = {
        "version": int(time_now_stamp()),
        "updated": __import__("datetime").date.today().isoformat(),
        "note": "Auto-maintained by resolver-canary CI. Merged over baked defaults by FleetConfig.",
        "signatureTimestamp": 19850,
        "audioClients": [KNOWN_SPECS[w] for w in winners],
    }
    with open(out_path, "w") as f:
        json.dump(config, f, indent=2)
        f.write("\n")
    print(f"fleet-config.json written with winners: {winners}")


def time_now_stamp():
    import time

    return time.strftime("%Y%m%d")


if __name__ == "__main__":
    main()
