//! dht_signaling.rs — Serverless SDP rendezvous (Jam Phase 2).
//!
//! MISSION (lead brief): eliminate cloud-database signaling entirely.
//!   Option 1 (offline / nearby): a 2D QR code or BLE advertisement carries
//!     the compressed SDP parameters directly.
//!   Option 2 (remote / online): an ephemeral encrypted relay keyed by the
//!     6-character Room Code — `Blake3(RoomCode)` — and the relay is
//!     disconnected the moment the WebRTC DataChannel is established.
//!
//! CRYPTO DESIGN (NIP-44-v2 lineage, honestly scoped):
//!   • Conversation key = `blake3::derive_key("streamify/jam/rendezvous/
//!     nip44-v2", room_code)` — the room code IS the shared-secret carrier
//!     (exactly what the brief pins: "keyed by a 6-character Room Code hash
//!     (Blake3(RoomCode))").
//!   • Envelope = XChaCha20Poly1305 AEAD with a fresh 24-byte nonce per
//!     message and the room code bound as AAD, rendered as
//!     `SF44v2.<base64(nonce || ciphertext)>`. NIP-44 uses ChaCha20 +
//!     HKDF-SHA256 over secp256k1 ECDH; we use Blake3 derive_key over the
//!     room code per the brief and skip the pubkey machinery a room code
//!     already replaces. Every envelope is authenticated: tampering,
//!     wrong-room replay, and wrong keys all fail closed as `AuthFailure`.
//!   • Real NIP-01 relay frames (event id = SHA-256 over the canonical
//!     array, schnorr `sig`) are built by [`build_nostr_event`] with a
//!     pluggable [`NostrSigner`]; `NullSigner` (all-zero sig) is the
//!     dev/local stand-in and is clearly labeled — production relays
//!     require a real secp256k1 signer at the [`NostrSigner`] seam.
//!
//! RELAY MODEL: a relay is an async task behind two channels — a command
//! channel (publish / connect / disconnect) and per-subscriber inbound
//! streams. [`InMemoryRelayHub`] implements Nostr-like semantics: topic
//! fan-out, a 60-second retention window for late joiners, and hard
//! `disconnect_all()` — after which publishes fail and subscribers drain
//! to `RelayDisconnected`, proving the relay is truly ephemeral. A real
//! WebSocket Nostr client implements the same [`RendezvousRelay`] shape
//! with its writer task behind the command channel.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine as _;
use chacha20poly1305::aead::{Aead, AeadCore, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::{mpsc, oneshot};

/// Key-derivation context (domain separation for the room-code key).
const KEY_CONTEXT: &str = "streamify/jam/rendezvous/nip44-v2";
/// Envelope prefix for the AEAD rendezvous format.
const ENVELOPE_PREFIX: &str = "SF44v2.";
/// Envelope prefix for the compressed offline (QR / BLE) format.
const OFFLINE_PREFIX: &str = "SFQR1.";
/// Room code length (brief: "6-character Room Code").
pub const ROOM_CODE_LEN: usize = 6;
/// 32-symbol alphabet: no 0/1/I/O confusables (Crockford-adjacent).
const ROOM_ALPHABET: &[u8] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
/// Relay retention window for late joiners (Nostr `since` semantics).
const RETENTION_WINDOW: Duration = Duration::from_secs(60);
/// Retained envelopes per topic.
const RETENTION_CAPACITY: usize = 128;

// ─────────────────────────────────────────────────────────────── errors

/// Signaling-layer error surface.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SignalingError {
    InvalidRoomCode,
    NotARendezvousEnvelope,
    AuthFailure,
    Crypto,
    Base64,
    Compression,
    Json(String),
    RelayDisconnected,
    Relay(String),
    Timeout,
}

impl std::fmt::Display for SignalingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SignalingError::InvalidRoomCode => {
                write!(f, "room code must be 6 chars from the 32-symbol alphabet")
            }
            SignalingError::NotARendezvousEnvelope => {
                write!(f, "string is not an SF44v2/SFQR1 envelope")
            }
            SignalingError::AuthFailure => write!(
                f,
                "envelope failed authentication (wrong room, tamper, or wrong key)"
            ),
            SignalingError::Crypto => write!(f, "AEAD operation failed"),
            SignalingError::Base64 => write!(f, "base64 decode failed"),
            SignalingError::Compression => write!(f, "deflate/inflate failed"),
            SignalingError::Json(s) => write!(f, "json: {s}"),
            SignalingError::RelayDisconnected => {
                write!(f, "relay is disconnected (rendezvous complete)")
            }
            SignalingError::Relay(s) => write!(f, "relay: {s}"),
            SignalingError::Timeout => write!(f, "rendezvous receive timed out"),
        }
    }
}

impl std::error::Error for SignalingError {}

// ─────────────────────────────────────────────────────────── room codes

/// The 6-character jam room code — the shared-secret carrier.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct RoomCode(String);

impl RoomCode {
    /// Generates a fresh room code (entropy from the OS via `thread_rng`).
    pub fn generate() -> Self {
        let mut rng = rand::thread_rng();
        let code: String = (0..ROOM_CODE_LEN)
            .map(|_| {
                let i = rng.gen_range(0..ROOM_ALPHABET.len());
                ROOM_ALPHABET[i] as char
            })
            .collect();
        RoomCode(code)
    }

    /// Validates a user-entered room code (case-insensitive: QR scans and
    /// shouted codes both arrive in unpredictable cases).
    pub fn parse(s: &str) -> Result<Self, SignalingError> {
        let s = s.trim().to_ascii_uppercase();
        if s.len() != ROOM_CODE_LEN || !s.bytes().all(|b| ROOM_ALPHABET.contains(&b)) {
            return Err(SignalingError::InvalidRoomCode);
        }
        Ok(RoomCode(s))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for RoomCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// Conversation key: Blake3 derive_key over the room code (brief §3.B).
pub fn conversation_key(code: &RoomCode) -> [u8; 32] {
    blake3::derive_key(KEY_CONTEXT, code.as_str().as_bytes())
}

/// Relay topic for a room: never exposes the room code to the relay.
pub fn room_topic(code: &RoomCode) -> String {
    let h = blake3::hash(code.as_str().as_bytes());
    format!("streamify/jam/{}", &h.to_hex()[..16])
}

// ───────────────────────────────────────────────────── AEAD envelopes

/// Seals `plaintext` into `SF44v2.<base64(nonce || ct)>`.
///
/// `aad` binds the envelope to the room (the room code bytes) so an
/// envelope captured from one room cannot be replayed into another even
/// under a hypothetical key collision.
pub fn seal(key: &[u8; 32], aad: &[u8], plaintext: &[u8]) -> Result<String, SignalingError> {
    let mut rng = rand::thread_rng();
    let nonce = XChaCha20Poly1305::generate_nonce(&mut rng);
    let cipher = XChaCha20Poly1305::new(key.into());
    let ct = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad,
            },
        )
        .map_err(|_| SignalingError::Crypto)?;
    let mut blob = nonce.to_vec();
    blob.extend_from_slice(&ct);
    Ok(format!("{ENVELOPE_PREFIX}{}", B64.encode(blob)))
}

/// Opens a [`seal`] envelope. Every failure mode is `AuthFailure` — the
/// caller learns nothing beyond "not for you / tampered".
pub fn unseal(key: &[u8; 32], aad: &[u8], envelope: &str) -> Result<Vec<u8>, SignalingError> {
    let blob_b64 = envelope
        .strip_prefix(ENVELOPE_PREFIX)
        .ok_or(SignalingError::NotARendezvousEnvelope)?;
    let blob = B64
        .decode(blob_b64.trim())
        .map_err(|_| SignalingError::Base64)?;
    if blob.len() < 24 + 16 {
        return Err(SignalingError::AuthFailure);
    }
    let (nonce, ct) = blob.split_at(24);
    let cipher = XChaCha20Poly1305::new(key.into());
    cipher
        .decrypt(XNonce::from_slice(nonce), Payload { msg: ct, aad })
        .map_err(|_| SignalingError::AuthFailure)
}

// ───────────────────────────────────────────── rendezvous message model

/// The SDP rendezvous vocabulary carried inside envelopes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum RendezvousMessage {
    SdpOffer {
        node_id_hex: String,
        sdp: String,
    },
    SdpAnswer {
        node_id_hex: String,
        sdp: String,
    },
    IceCandidate {
        node_id_hex: String,
        candidate: String,
    },
}

impl RendezvousMessage {
    pub fn node_id_hex(&self) -> &str {
        match self {
            RendezvousMessage::SdpOffer { node_id_hex, .. }
            | RendezvousMessage::SdpAnswer { node_id_hex, .. }
            | RendezvousMessage::IceCandidate { node_id_hex, .. } => node_id_hex,
        }
    }
}

// ──────────────────────────────────────── offline path (QR / BLE payload)

/// Option 1 (brief §3.B): compress the message and render it as a
/// self-contained offline payload — `SFQR1.<base64(deflate(json))>`.
/// SDP JSON is extremely redundant; deflate typically halves it, keeping
/// QR density (or BLE advertisement budget) workable.
pub fn encode_offline_payload(msg: &RendezvousMessage) -> Result<String, SignalingError> {
    let json = serde_json::to_vec(msg).map_err(|e| SignalingError::Json(e.to_string()))?;
    let z = miniz_oxide::deflate::compress_to_vec(&json, 6);
    Ok(format!("{OFFLINE_PREFIX}{}", B64.encode(z)))
}

/// Reverses [`encode_offline_payload`].
pub fn decode_offline_payload(payload: &str) -> Result<RendezvousMessage, SignalingError> {
    let z_b64 = payload
        .strip_prefix(OFFLINE_PREFIX)
        .ok_or(SignalingError::NotARendezvousEnvelope)?;
    let z = B64
        .decode(z_b64.trim())
        .map_err(|_| SignalingError::Base64)?;
    let json =
        miniz_oxide::inflate::decompress_to_vec(&z).map_err(|_| SignalingError::Compression)?;
    serde_json::from_slice(&json).map_err(|e| SignalingError::Json(e.to_string()))
}

// ───────────────────────────────────────────────── relay transport model

/// One frame delivered by a relay.
#[derive(Debug, Clone)]
pub struct RelayFrame {
    pub topic: String,
    pub envelope: String,
}

enum RelayCommand {
    Publish {
        topic: String,
        envelope: String,
    },
    Connect {
        topic: String,
        reply: oneshot::Sender<Result<mpsc::UnboundedReceiver<RelayFrame>, SignalingError>>,
    },
    DisconnectAll {
        ack: oneshot::Sender<()>,
    },
}

/// A live relay subscription: the publish side is a command channel (sync
/// to call, fire-and-forget into the relay task), the inbound side is a
/// frame stream. Both a WebSocket Nostr client and the in-memory hub
/// present this same shape.
pub struct RelayConnection {
    topic: String,
    publish_tx: mpsc::UnboundedSender<RelayCommand>,
    pub inbound: mpsc::UnboundedReceiver<RelayFrame>,
}

impl RelayConnection {
    pub fn topic(&self) -> &str {
        &self.topic
    }

    /// Publishes one envelope on this subscription's topic. Fails only
    /// when the relay task is gone (disconnected / dropped).
    pub fn publish(&self, envelope: &str) -> Result<(), SignalingError> {
        self.publish_tx
            .send(RelayCommand::Publish {
                topic: self.topic.clone(),
                envelope: envelope.to_string(),
            })
            .map_err(|_| SignalingError::RelayDisconnected)
    }
}

/// Relay transport seam (static dispatch — a WebSocket Nostr relay
/// implements the same command/task shape).
#[allow(async_fn_in_trait)] // static dispatch only; not dyn-compatible by design
pub trait RendezvousRelay: Send + Sync {
    async fn connect(&self, topic: &str) -> Result<RelayConnection, SignalingError>;
    async fn disconnect_all(&self);
    fn name(&self) -> &'static str;
}

// ─────────────────────────────────────────────────── in-memory relay hub

struct HubSubscriber {
    topic: String,
    tx: mpsc::UnboundedSender<RelayFrame>,
}

/// Thread-safe in-memory relay with Nostr-like semantics: topic fan-out,
/// bounded 60-second retention for late joiners, hard disconnect.
///
/// Construction spawns the relay task — call [`InMemoryRelayHub::new`]
/// inside a Tokio runtime (same contract as `MeshNode::start`).
#[derive(Clone)]
pub struct InMemoryRelayHub {
    cmd_tx: mpsc::UnboundedSender<RelayCommand>,
}

impl Default for InMemoryRelayHub {
    fn default() -> Self {
        Self::new()
    }
}

impl InMemoryRelayHub {
    pub fn new() -> Self {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        tokio::spawn(hub_task(cmd_rx));
        InMemoryRelayHub { cmd_tx }
    }
}

impl RendezvousRelay for InMemoryRelayHub {
    async fn connect(&self, topic: &str) -> Result<RelayConnection, SignalingError> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.cmd_tx
            .send(RelayCommand::Connect {
                topic: topic.to_string(),
                reply: reply_tx,
            })
            .map_err(|_| SignalingError::RelayDisconnected)?;
        let inbound = reply_rx
            .await
            .map_err(|_| SignalingError::RelayDisconnected)??;
        Ok(RelayConnection {
            topic: topic.to_string(),
            publish_tx: self.cmd_tx.clone(),
            inbound,
        })
    }

    async fn disconnect_all(&self) {
        let (ack_tx, ack_rx) = oneshot::channel();
        if self
            .cmd_tx
            .send(RelayCommand::DisconnectAll { ack: ack_tx })
            .is_ok()
        {
            let _ = ack_rx.await;
        }
    }

    fn name(&self) -> &'static str {
        "in-memory-relay"
    }
}

async fn hub_task(mut cmd_rx: mpsc::UnboundedReceiver<RelayCommand>) {
    let mut subscribers: Vec<HubSubscriber> = Vec::new();
    let mut retained: HashMap<String, VecDeque<(Instant, String)>> = HashMap::new();

    while let Some(cmd) = cmd_rx.recv().await {
        match cmd {
            RelayCommand::Publish { topic, envelope } => {
                // Retain for late joiners (ephemeral window).
                let q = retained.entry(topic.clone()).or_default();
                q.push_back((Instant::now(), envelope.clone()));
                while q.len() > RETENTION_CAPACITY {
                    q.pop_front();
                }
                // Fan out to this topic's live subscribers (prune only
                // CLOSED subscriptions — topic filtering happens below and
                // must never evict subscribers of other rooms).
                subscribers.retain(|s| !s.tx.is_closed());
                for s in &subscribers {
                    if s.topic == topic {
                        let _ = s.tx.send(RelayFrame {
                            topic: topic.clone(),
                            envelope: envelope.clone(),
                        });
                    }
                }
            }
            RelayCommand::Connect { topic, reply } => {
                let (tx, rx) = mpsc::unbounded_channel::<RelayFrame>();
                // Replay the retention window for this topic (queue the
                // frames on the sender side before the subscription is
                // registered — the receiver buffers them).
                if let Some(q) = retained.get(&topic) {
                    for (at, env) in q {
                        if at.elapsed() <= RETENTION_WINDOW {
                            let _ = tx.send(RelayFrame {
                                topic: topic.clone(),
                                envelope: env.clone(),
                            });
                        }
                    }
                }
                subscribers.push(HubSubscriber { topic, tx });
                let _ = reply.send(Ok(rx));
            }
            RelayCommand::DisconnectAll { ack } => {
                // Drop every subscriber and all retained frames, ack, then
                // exit: every publisher channel now fails and every
                // subscriber stream drains to None → RelayDisconnected.
                subscribers.clear();
                retained.clear();
                let _ = ack.send(());
                break;
            }
        }
    }
}

// ─────────────────────────────────────────────────── the rendezvous client

/// Room-scoped rendezvous client: seals messages under the room key and
/// exchanges them through a relay subscription.
pub struct RendezvousClient {
    key: [u8; 32],
    aad: Vec<u8>,
    node_id_hex: String,
    conn: RelayConnection,
}

impl RendezvousClient {
    /// Connects to a relay on `code`'s topic and derives the room key.
    pub async fn connect(
        relay: &impl RendezvousRelay,
        code: &RoomCode,
        node_id_hex: &str,
    ) -> Result<Self, SignalingError> {
        let conn = relay.connect(&room_topic(code)).await?;
        Ok(RendezvousClient {
            key: conversation_key(code),
            aad: code.as_str().as_bytes().to_vec(),
            node_id_hex: node_id_hex.to_string(),
            conn,
        })
    }

    pub fn topic(&self) -> &str {
        self.conn.topic()
    }

    pub fn node_id_hex(&self) -> &str {
        &self.node_id_hex
    }

    /// Publishes one sealed rendezvous message.
    pub fn publish(&self, msg: &RendezvousMessage) -> Result<(), SignalingError> {
        let json = serde_json::to_vec(msg).map_err(|e| SignalingError::Json(e.to_string()))?;
        let envelope = seal(&self.key, &self.aad, &json)?;
        self.conn.publish(&envelope)
    }

    /// Awaits the next authenticated rendezvous message.
    ///
    /// Nostr relays echo a subscriber's own published events back to them;
    /// self-authored frames are skipped so callers only ever see the OTHER
    /// side of the handshake.
    pub async fn recv(&mut self, timeout: Duration) -> Result<RendezvousMessage, SignalingError> {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(SignalingError::Timeout);
            }
            let frame = tokio::time::timeout(remaining, self.conn.inbound.recv())
                .await
                .map_err(|_| SignalingError::Timeout)?
                .ok_or(SignalingError::RelayDisconnected)?;
            let plain = unseal(&self.key, &self.aad, &frame.envelope)?;
            let msg: RendezvousMessage =
                serde_json::from_slice(&plain).map_err(|e| SignalingError::Json(e.to_string()))?;
            if msg.node_id_hex() == self.node_id_hex {
                continue; // our own echo
            }
            return Ok(msg);
        }
    }
}

// ───────────────────────────────────────────── NIP-01 relay frame codecs

/// Nostr event signer seam. Production relays verify the schnorr signature
/// against `pubkey`; `NullSigner` is the dev/local stand-in.
pub trait NostrSigner: Send + Sync {
    fn pubkey_hex(&self) -> String;
    fn sign_schnorr(&self, event_id_hex: &str) -> Result<String, SignalingError>;
}

/// Development signer: 64 zero bytes as `sig`. Local relays and tests only.
pub struct NullSigner;

impl NostrSigner for NullSigner {
    fn pubkey_hex(&self) -> String {
        "00".repeat(32)
    }
    fn sign_schnorr(&self, _event_id_hex: &str) -> Result<String, SignalingError> {
        Ok("00".repeat(32))
    }
}

/// Ephemeral kind (NIP-16-style: relays do not persist ephemeral events).
pub const NOSTR_KIND_EPHEMERAL: u64 = 20_000;

/// Builds a NIP-01 EVENT frame. `id` = SHA-256 over the canonical array
/// `[0, pubkey, created_at, kind, tags, content]`.
pub fn build_nostr_event(
    content: &str,
    topic: &str,
    created_at_unix: u64,
    signer: &dyn NostrSigner,
) -> Result<String, SignalingError> {
    let pubkey = signer.pubkey_hex();
    let canonical = serde_json::to_string(&serde_json::json!([
        0,
        pubkey,
        created_at_unix,
        NOSTR_KIND_EPHEMERAL,
        [["t", topic]],
        content,
    ]))
    .map_err(|e| SignalingError::Json(e.to_string()))?;
    let id = hex::encode(Sha256::digest(canonical.as_bytes()));
    let sig = signer.sign_schnorr(&id)?;
    serde_json::to_string(&serde_json::json!({
        "id": id,
        "pubkey": pubkey,
        "created_at": created_at_unix,
        "kind": NOSTR_KIND_EPHEMERAL,
        "tags": [["t", topic]],
        "content": content,
        "sig": sig,
    }))
    .map_err(|e| SignalingError::Json(e.to_string()))
}

/// Builds a NIP-01 REQ subscription limited to the room topic and the
/// ephemeral retention window.
pub fn build_nostr_subscription(topic: &str, since_unix: u64, sub_id: &str) -> String {
    serde_json::to_string(&serde_json::json!([
        "REQ",
        sub_id,
        { "#t": [topic], "since": since_unix, "limit": 0 }
    ]))
    .unwrap_or_default()
}

/// Parses a relay `["EVENT", <sub>, {…}]` notice into `(id, content)`.
pub fn parse_nostr_relay_event(line: &str) -> Option<(String, String)> {
    let v: serde_json::Value = serde_json::from_str(line).ok()?;
    let arr = v.as_array()?;
    if arr.first()?.as_str()? != "EVENT" {
        return None;
    }
    let ev = arr.get(2)?.as_object()?;
    let id = ev.get("id")?.as_str()?.to_string();
    let content = ev.get("content")?.as_str()?.to_string();
    Some((id, content))
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn room_code_generate_and_parse() {
        let code = RoomCode::generate();
        assert_eq!(code.as_str().len(), 6);
        assert_eq!(RoomCode::parse(code.as_str()).unwrap(), code);
        // Confusable-free alphabet: rejects 0/1/I/O and wrong lengths.
        assert_eq!(
            RoomCode::parse("0OI1AA"),
            Err(SignalingError::InvalidRoomCode)
        );
        assert_eq!(RoomCode::parse("ABC"), Err(SignalingError::InvalidRoomCode));
        assert_eq!(
            RoomCode::parse("ABCDEFG"),
            Err(SignalingError::InvalidRoomCode)
        );
        assert_eq!(RoomCode::parse(" abcd23 "), Ok(RoomCode("ABCD23".into())));
    }

    #[test]
    fn envelope_roundtrip_tamper_and_wrong_room() {
        let code = RoomCode::parse("ABCD23").unwrap();
        let key = conversation_key(&code);
        let aad = code.as_str().as_bytes();
        let msg = br#"{"SdpOffer":{"node_id_hex":"aabb","sdp":"v=0..."}}"#;

        let env = seal(&key, aad, msg).unwrap();
        assert!(env.starts_with("SF44v2."));
        assert_eq!(unseal(&key, aad, &env).unwrap(), msg.to_vec());

        // Tampered ciphertext fails closed.
        let mut bad = env.clone();
        let flip = if env.as_bytes()[10] == b'A' { "B" } else { "A" };
        bad.replace_range(10..11, flip);
        assert_eq!(unseal(&key, aad, &bad), Err(SignalingError::AuthFailure));
        assert_eq!(
            unseal(&key, aad, "garbage"),
            Err(SignalingError::NotARendezvousEnvelope)
        );

        // Wrong room code (different key AND different AAD).
        let other = RoomCode::parse("WXYZ78").unwrap();
        let other_key = conversation_key(&other);
        assert_eq!(
            unseal(&other_key, other.as_str().as_bytes(), &env),
            Err(SignalingError::AuthFailure)
        );

        // Envelopes are randomized per call (fresh nonce).
        assert_ne!(seal(&key, aad, msg).unwrap(), env);
    }

    #[test]
    fn offline_payload_roundtrip_and_compresses() {
        let sdp: String = std::iter::repeat(
            "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=ice-ufrag:8hhY\r\na=ice-pwd:asd88fgpdd777uzjYhagZg1x\r\na=fingerprint:sha-256 4A:AD:B9:B1:3F:82:1A:37:8E:53:37:3B:1B:2D:3E:4F:5A:6B:7C:8D:9E:AF:B0:C1:D2:E3:F4:A5:B6:C7\r\n",
        )
        .take(8)
        .collect();
        let msg = RendezvousMessage::SdpOffer {
            node_id_hex: "4242424242424242".into(),
            sdp,
        };
        let json_len = serde_json::to_vec(&msg).unwrap().len();
        let payload = encode_offline_payload(&msg).unwrap();
        assert!(payload.starts_with("SFQR1."));
        assert!(
            payload.len() < json_len,
            "deflate must shrink SDP JSON: {} vs {}",
            payload.len(),
            json_len
        );
        assert_eq!(decode_offline_payload(&payload).unwrap(), msg);
        assert_eq!(
            decode_offline_payload("junk"),
            Err(SignalingError::NotARendezvousEnvelope)
        );
    }

    #[test]
    fn nostr_event_id_is_sha256_of_canonical() {
        let signer = NullSigner;
        let frame =
            build_nostr_event("SF44v2.test", "streamify/jam/abcd", 1_700_000_000, &signer).unwrap();
        let v: serde_json::Value = serde_json::from_str(&frame).unwrap();
        let id = v["id"].as_str().unwrap().to_string();
        // Recompute the canonical digest independently.
        let canonical = serde_json::to_string(&serde_json::json!([
            0,
            signer.pubkey_hex(),
            1_700_000_000u64,
            NOSTR_KIND_EPHEMERAL,
            [["t", "streamify/jam/abcd"]],
            "SF44v2.test",
        ]))
        .unwrap();
        assert_eq!(id, hex::encode(Sha256::digest(canonical.as_bytes())));
        assert_eq!(v["sig"].as_str().unwrap().len(), 64);

        let sub = build_nostr_subscription("streamify/jam/abcd", 1_700_000_000, "sub1");
        assert!(sub.starts_with(r#"["REQ","sub1","#));
        // The EVENT notice wraps the event object at index 2.
        let notice = format!(r#"["EVENT","sub1",{}]"#, frame);
        let (rid, content) = parse_nostr_relay_event(&notice).unwrap();
        assert_eq!(rid, id);
        assert_eq!(content, "SF44v2.test");
    }

    #[tokio::test]
    async fn full_rendezvous_flow_then_relay_disconnect() {
        let hub = Arc::new(InMemoryRelayHub::new());
        let code = RoomCode::parse("JAM4CT").unwrap();

        let mut alice = RendezvousClient::connect(hub.as_ref(), &code, "a11ce00d")
            .await
            .unwrap();
        let mut bob = RendezvousClient::connect(hub.as_ref(), &code, "b0ba11ce")
            .await
            .unwrap();
        assert_eq!(alice.topic(), bob.topic(), "same room → same relay topic");

        // Alice offers, Bob answers — both authenticated under the room key.
        alice
            .publish(&RendezvousMessage::SdpOffer {
                node_id_hex: "a11ce00d".into(),
                sdp: "offer-sdp".into(),
            })
            .unwrap();
        let got = bob.recv(Duration::from_millis(500)).await.unwrap();
        assert_eq!(
            got,
            RendezvousMessage::SdpOffer {
                node_id_hex: "a11ce00d".into(),
                sdp: "offer-sdp".into(),
            }
        );

        bob.publish(&RendezvousMessage::SdpAnswer {
            node_id_hex: "b0ba11ce".into(),
            sdp: "answer-sdp".into(),
        })
        .unwrap();
        let got = alice.recv(Duration::from_millis(500)).await.unwrap();
        assert_eq!(
            got,
            RendezvousMessage::SdpAnswer {
                node_id_hex: "b0ba11ce".into(),
                sdp: "answer-sdp".into(),
            }
        );

        // DataChannel established → relay hard-disconnected (ephemeral).
        hub.disconnect_all().await;
        assert_eq!(
            alice.publish(&RendezvousMessage::IceCandidate {
                node_id_hex: "a11ce00d".into(),
                candidate: "candidate:1".into(),
            }),
            Err(SignalingError::RelayDisconnected)
        );
        assert_eq!(
            bob.recv(Duration::from_millis(50)).await,
            Err(SignalingError::RelayDisconnected)
        );
    }

    #[tokio::test]
    async fn relay_retention_replays_for_late_joiners() {
        let hub = Arc::new(InMemoryRelayHub::new());
        let code = RoomCode::parse("L4T3JR").unwrap();
        let host = RendezvousClient::connect(hub.as_ref(), &code, "h0st")
            .await
            .unwrap();
        host.publish(&RendezvousMessage::SdpOffer {
            node_id_hex: "h0st".into(),
            sdp: "late-offer".into(),
        })
        .unwrap();

        // Carol joins after the offer was published; the retention window
        // (Nostr `since` semantics) replays it to her.
        let mut carol = RendezvousClient::connect(hub.as_ref(), &code, "car01")
            .await
            .unwrap();
        let got = carol.recv(Duration::from_millis(500)).await.unwrap();
        assert!(matches!(got, RendezvousMessage::SdpOffer { .. }));
    }
    #[tokio::test]
    async fn wrong_room_never_sees_other_room_traffic() {
        let hub = Arc::new(InMemoryRelayHub::new());
        let room_a = RoomCode::parse("AAAA22").unwrap();
        let room_b = RoomCode::parse("BBBB33").unwrap();

        let alice = RendezvousClient::connect(hub.as_ref(), &room_a, "a")
            .await
            .unwrap();
        let mut mallory = RendezvousClient::connect(hub.as_ref(), &room_b, "m")
            .await
            .unwrap();
        assert_ne!(alice.topic(), mallory.topic());

        alice
            .publish(&RendezvousMessage::SdpOffer {
                node_id_hex: "a".into(),
                sdp: "secret".into(),
            })
            .unwrap();
        // Mallory shares the relay but not the topic — nothing arrives.
        assert_eq!(
            mallory.recv(Duration::from_millis(100)).await,
            Err(SignalingError::Timeout)
        );
    }
}
