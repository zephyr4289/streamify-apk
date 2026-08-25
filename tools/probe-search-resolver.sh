#!/usr/bin/env bash
# tools/probe-search-resolver.sh — Hard end-to-end verification of Search -> VideoID -> Player API -> CDN Audio Stream.
set -euo pipefail

QUERY="${1:-The Weeknd Blinding Lights}"
echo "================================================================="
echo "🔍 PROBING SEARCH -> CDN STREAM RESOLUTION FOR: '$QUERY'"
echo "================================================================="

# 1. Live Search Query via YouTube Music Innertube API
SEARCH_PAYLOAD=$(jq -nc --arg q "$QUERY" '{
  context: {
    client: {
      clientName: "WEB_REMIX",
      clientVersion: "1.20240101.01.00",
      hl: "en",
      gl: "US"
    }
  },
  query: $q,
  params: "egWKAQIIAWoMEAMQBBAJEAoQBRAV"
}')

echo "[Step 1] Querying YouTube Music search endpoint..."
SEARCH_RESP=$(curl -s --compressed --max-time 15 "https://music.youtube.com/youtubei/v1/search" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  -H "Accept: */*" \
  -H "X-YouTube-Client-Name: 67" \
  -H "X-YouTube-Client-Version: 1.20240101.01.00" \
  -H "Origin: https://music.youtube.com" \
  -H "Referer: https://music.youtube.com/" \
  --data "$SEARCH_PAYLOAD")

# 2. Extract Video ID from search response
VIDEO_ID=$(printf '%s' "$SEARCH_RESP" | jq -r '
  [
    .. | .musicResponsiveListItemRenderer? | select(. != null) |
    (.playlistItemData.videoId // .navigationEndpoint.watchEndpoint.videoId // .overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint.videoId)
  ] | map(select(. != null and length == 11)) | first // empty
')

if [ -z "$VIDEO_ID" ]; then
  VIDEO_ID=$(printf '%s' "$SEARCH_RESP" | jq -r '
    [
      .. | .musicCardShelfRenderer? | select(. != null) |
      .onTap.watchEndpoint.videoId // empty
    ] | map(select(. != null and length == 11)) | first // empty
  ')
fi

if [ -z "$VIDEO_ID" ]; then
  VIDEO_ID=$(printf '%s' "$SEARCH_RESP" | jq -r '.. | .videoId? // empty' | head -n 1)
fi

if [ -z "$VIDEO_ID" ] || [ "$VIDEO_ID" = "null" ]; then
  echo "❌ FAILED: Could not extract valid 11-char videoId from search response for '$QUERY'."
  echo "Response preview:"
  printf '%s\n' "$SEARCH_RESP" | head -n 30
  exit 1
fi

echo "✅ PASS: Extracted Video ID: $VIDEO_ID"

# 3. Resolve Video ID to GoogleVideo CDN stream via ANDROID client
echo "[Step 2] Resolving CDN stream via Innertube ANDROID player endpoint..."
PLAYER_PAYLOAD=$(jq -nc --arg vid "$VIDEO_ID" '{
  context: {
    client: {
      clientName: "ANDROID",
      clientVersion: "21.26.364",
      androidSdkVersion: 34,
      hl: "en",
      gl: "US"
    }
  },
  videoId: $vid,
  contentCheckOk: true,
  racyCheckOk: true,
  playbackContext: {
    contentPlaybackContext: {
      signatureTimestamp: 19850,
      html5Preference: "HTML5_PREF_WANTS"
    }
  }
}')

PLAYER_RESP=$(curl -s --compressed --max-time 15 "https://www.youtube.com/youtubei/v1/player" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "User-Agent: com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip" \
  -H "X-YouTube-Client-Name: 3" \
  -H "X-YouTube-Client-Version: 21.26.364" \
  --data "$PLAYER_PAYLOAD")

PLAYABILITY=$(printf '%s' "$PLAYER_RESP" | jq -r '.playabilityStatus.status // "NO_STATUS"')
if [ "$PLAYABILITY" != "OK" ]; then
  REASON=$(printf '%s' "$PLAYER_RESP" | jq -r '.playabilityStatus.reason // "Unknown reason"')
  echo "❌ FAILED: Player endpoint returned playability status '$PLAYABILITY': $REASON"
  exit 1
fi

CDN_URL=$(printf '%s' "$PLAYER_RESP" | jq -r '
  (
    [.streamingData.adaptiveFormats[]? | select(.mimeType | startswith("audio/")) | .url // empty] +
    [.streamingData.formats[]? | .url // empty]
  ) | first // empty
')

if [ -z "$CDN_URL" ]; then
  echo "❌ FAILED: No direct stream URL in streamingData (formats may require signature decryption)."
  exit 1
fi

echo "✅ PASS: Resolved GoogleVideo CDN URL: ${CDN_URL:0:80}..."

# 4. Probe CDN URL with byte-range request to verify ExoPlayer will receive audio bytes
echo "[Step 3] Probing GoogleVideo CDN with Range: bytes=0-1024..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -r 0-1024 "$CDN_URL")

if [ "$HTTP_STATUS" -ne 200 ] && [ "$HTTP_STATUS" -ne 206 ]; then
  echo "❌ FAILED: CDN returned HTTP $HTTP_STATUS instead of 200/206 (Forbidden / Expired URL)."
  exit 1
fi

echo "================================================================="
echo "🎉 [PASS] END-TO-END SEARCH -> CDN RESOLVER VERIFIED: HTTP $HTTP_STATUS"
echo "================================================================="
exit 0
