use sha1::{Digest, Sha1};
use std::time::{SystemTime, UNIX_EPOCH};
use std::panic::catch_unwind;

/// Generates SAPISIDHASH safely across FFI.
/// Returns: Length of string written to out_buf, or negative error code on failure.
#[no_mangle]
pub unsafe extern "C" fn generate_sapisid_hash(
    sapisid_ptr: *const u8,
    sapisid_len: usize,
    origin_ptr: *const u8,
    origin_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        // Validate pointers
        if sapisid_ptr.is_null() || origin_ptr.is_null() || out_buf.is_null() || sapisid_len == 0 {
            return -1;
        }

        let sapisid = std::slice::from_raw_parts(sapisid_ptr, sapisid_len);
        let origin = std::slice::from_raw_parts(origin_ptr, origin_len);

        let timestamp = match SystemTime::now().duration_since(UNIX_EPOCH) {
            Ok(d) => d.as_secs(),
            Err(_) => return -1,
        };

        // SHA1(payload) = SHA1("{timestamp} {sapisid} {origin}")
        let mut hasher = Sha1::new();
        hasher.update(timestamp.to_string().as_bytes());
        hasher.update(b" ");
        hasher.update(sapisid);
        hasher.update(b" ");
        hasher.update(origin);

        let digest = hasher.finalize();
        let hash_hex = hex::encode(digest);
        
        // Format: "{timestamp}_{hash_hex}"
        let formatted = format!("{}_{}", timestamp, hash_hex);
        let bytes = formatted.as_bytes();

        if bytes.len() > out_buf_len {
            return -2; // Buffer too small
        }

        // Write result to Kotlin's pre-allocated buffer
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf, bytes.len());
        bytes.len() as i32
    });

    // If a panic occurred (e.g., hash failed), return -3. NEVER crash the JVM.
    result.unwrap_or(-3)
}
