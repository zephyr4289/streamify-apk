#ifndef STREAMIFY_HARMONIC_TRANSITION_ENGINE_H
#define STREAMIFY_HARMONIC_TRANSITION_ENGINE_H
// ============================================================================
//  HarmonicTransitionEngine.h — Camelot Wheel key compatibility (Phase 2,
//  BEHIND.md gaps #26 & #39: Smart-Reorder-aware sequencing / Automix)
// ============================================================================
//
//  CAMELOT MODEL
//  ------------
//  A Camelot key is (number 1..12, mode A=minor / B=major). The wheel is
//  ordered in perfect fifths: moving +1 on the wheel moves the tonic +7
//  semitones. Pitch classes (C=0 .. B=11):
//
//      B (major): pc = 7*(n-1)          mod 12   (1B=C, 2B=G, ... 8B=Db)
//      A (minor): pc = (9 + 7*(n-1))    mod 12   (1A=Am, 2A=Em, ... 8A=Bbm)
//
//  KEY TRANSITION LADDER (directive §3B anchors, precedence order)
//  ---------------------------------------------------------------
//   1. Exact match          same number, same mode           1.00
//      (8B -> 8B)
//   2. Relative maj/min     same number, opposite mode       0.95
//      (8B -> 8A)
//   3. Adjacent wheel       same mode, number +-1 (wraps     0.90
//      neighbors            12 <-> 1)  (8B -> 7B / 9B)
//   4. Energy boosts        SAME-MODE directed root shift    0.75..0.85
//      (shifting a whole track up preserves its mode — the
//      classic MIK moves):
//        +2 st (whole step, e.g. 8A -> 10A)                  0.85
//        +1 st (half  step, e.g. 8A -> 3A)                   0.80
//      The directive's +7 st shift lands exactly on the wheel-adjacent
//      key (7 st == +1 Camelot step), which rule 3 already scores
//      0.90 > 0.85 — precedence keeps the stronger anchor.
//      Boosts are DIRECTIONAL by design: 8A -> 10A scores 0.85 while
//      the energy-dropping inverse 10A -> 8A falls to the two-step
//      score (0.60). A DJ raises energy INTO the next track.
//   5. Diagonal             opposite mode, wheel neighbor     0.70
//      (8A -> 9B / 7B — the "relative of the neighbor" move)
//   6. Two-step             wheel distance 2, not an energy  0.60
//      boost (e.g. 10A -> 8A, the energy-drop inverse)
//   7. Clash                wheel distance D in [3..6]:      -> 0.10
//      smoothstep falloff  score = 0.60 - 0.50 * s((D-2)/4)
//      (D=3 -> 0.52, D=4 -> 0.35, D=5 -> 0.18, D=6 -> 0.10)
//
//  The distance ladder (rules 5-7) is mode-agnostic and symmetric; the
//  ONLY asymmetric pairs are the same-mode energy boosts vs their
//  inverses — every other transition scores identically in both
//  directions (verified exhaustively over all 24x24 ordered pairs).
//
//  Inputs outside 1..12 (missing/unknown key metadata) score the NEUTRAL
//  0.5 — a missing Camelot key must not punish an otherwise perfect pair.
//  All outputs are clamped to [0, 1]; scoring is pure float math with no
//  heap allocations and no global state.
// ============================================================================

#include <cstdint>

#include "MathExport.h"

namespace streamify {
namespace math {

// Directive §3B anchor scores (exact literals; see ladder above).
inline constexpr float kKeyScoreExactMatch = 1.00f;
inline constexpr float kKeyScoreRelative = 0.95f;
inline constexpr float kKeyScoreAdjacent = 0.90f;
inline constexpr float kKeyScoreEnergyBoostWholeStep = 0.85f;   // +2 st
inline constexpr float kKeyScoreEnergyBoostHalfStep = 0.80f;    // +1 st
inline constexpr float kKeyScoreDiagonal = 0.70f;
inline constexpr float kKeyScoreTwoStep = 0.60f;
inline constexpr float kKeyScoreClashFloor = 0.10f;
inline constexpr float kKeyScoreNeutral = 0.50f;   // unknown/invalid key

// Named relation for UI/debug surfaces (Kotlin additive JNI, tests).
enum class KeyRelation : uint8_t {
    Invalid = 0,        // key outside 1..12 (neutral score)
    ExactMatch,
    Relative,           // same number, opposite mode
    Adjacent,           // same mode, +-1 on the wheel
    EnergyBoostWhole,   // same mode, +2 semitones
    EnergyBoostHalf,    // same mode, +1 semitone
    Diagonal,           // opposite mode, +-1 on the wheel
    TwoStep,            // wheel distance 2
    Clash               // wheel distance >= 3
};

// True iff `number` is a legal Camelot wheel position (1..12).
inline constexpr bool camelotKeyValid(int32_t number) {
    return number >= 1 && number <= 12;
}

// Chromatic pitch class (C=0 .. B=11) of a Camelot key; only meaningful for
// valid numbers (garbage in => garbage out; callers use camelotKeyValid).
uint32_t camelotPitchClass(int32_t number, bool isMinor);

// Classify the directed transition A -> B (see ladder above).
KeyRelation classifyKeyTransition(int32_t numberA, bool isMinorA,
                                  int32_t numberB, bool isMinorB);

// Camelot key compatibility score in [0,1] (neutral 0.5 for invalid keys).
float keyTransitionScore(int32_t numberA, bool isMinorA, int32_t numberB,
                         bool isMinorB);

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI for Rust consumers — key half (BPM half arrives with
// HarmonicTransitionEngine's BPM matrix; see streamify_transition_score).
// ---------------------------------------------------------------------------
extern "C" {

// Camelot key transition score in [0,1]; isMinor != 0 => Camelot A (minor).
STREAMIFY_MATH_API float streamify_key_transition_score(int32_t numberA,
                                                        int32_t isMinorA,
                                                        int32_t numberB,
                                                        int32_t isMinorB);

}  // extern "C"

#endif  // STREAMIFY_HARMONIC_TRANSITION_ENGINE_H
