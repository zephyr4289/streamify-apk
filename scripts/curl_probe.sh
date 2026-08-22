#!/data/data/com.termux/files/usr/bin/bash
VID="jNQXAC9IVRw"
BODY='{"context":{"client":{"clientName":"ANDROID","clientVersion":"21.26.364","androidSdkVersion":34,"osName":"Android","osVersion":"11","hl":"en","gl":"US"}},"videoId":"'"$VID"'","contentCheckOk":true,"racyCheckOk":true}'

probe() {
  local label="$1"; shift
  echo "── $label"
  timeout 20 curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
    -H "Content-Type: application/json" \
    "$@" \
    --data "$BODY" | python3 -c "
import sys,json
try:
    j=json.load(sys.stdin)
    ps=j.get('playabilityStatus',{})
    fmts=j.get('streamingData',{}).get('adaptiveFormats') or []
    urls=[f for f in fmts if f.get('url')]
    aud=[f for f in urls if str(f.get('mimeType','')).startswith('audio/')]
    print('   playability=',ps.get('status'),'|',str(ps.get('reason',''))[:45])
    print('   fmts=',len(fmts),'direct=',len(urls),'audio_direct=',len(aud))
except Exception as e:
    print('   parse fail:',e)
"
}

probe "curl/OpenSSL ANDROID client"
probe "curl + X-YouTube-Client-Name/3" -H "X-YouTube-Client-Name: 3" -H "X-YouTube-Client-Version: 21.26.364"

IOS_BODY='{"context":{"client":{"clientName":"IOS","clientVersion":"21.26.4","deviceModel":"iPhone16,2","hl":"en","gl":"US"}},"videoId":"'"$VID"'","contentCheckOk":true,"racyCheckOk":true}'
echo "── curl IOS 21.26.4"
timeout 20 curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
  -H "Content-Type: application/json" \
  -H "User-Agent: com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;) gzip" \
  -H "X-YouTube-Client-Name: 5" -H "X-YouTube-Client-Version: 21.26.4" \
  --data "$IOS_BODY" | python3 -c "
import sys,json
try:
    j=json.load(sys.stdin)
    ps=j.get('playabilityStatus',{})
    fmts=j.get('streamingData',{}).get('adaptiveFormats') or []
    urls=[f for f in fmts if f.get('url')]
    aud=[f for f in urls if str(f.get('mimeType','')).startswith('audio/')]
    print('   playability=',ps.get('status'),'|',str(ps.get('reason',''))[:45])
    print('   fmts=',len(fmts),'direct=',len(urls),'audio_direct=',len(aud))
    if aud: 
        u=aud[0]['url']; print('   sample url host:',u.split('/')[2] if '/' in u else '?')
        import urllib.request
        rq=urllib.request.Request(u,headers={'Range':'bytes=0-999','User-Agent':'com.google.ios.youtube/19.29.1'})
        try:
            rr=urllib.request.urlopen(rq,timeout=10); print('   STREAM:',rr.status)
        except Exception as ex: print('   STREAM fail:',str(ex)[:50])
except Exception as e:
    print('   parse fail:',e)
"
