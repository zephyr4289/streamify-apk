use sha1::{Digest, Sha1};
use std::time::{SystemTime, UNIX_EPOCH};

#[no_mangle]
pub unsafe extern "C" fn generate_sapisid_hash(
    sapisid_ptr: *const u8,
    sapisid_len: usize,
    origin_ptr: *const u8,
    origin_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = std::panic::catch_unwind(|| {
        if sapisid_ptr.is_null() || origin_ptr.is_null() || out_buf.is_null() {
            return -1;
        }
        let sapisid = std::slice::from_raw_parts(sapisid_ptr, sapisid_len);
        let origin = std::slice::from_raw_parts(origin_ptr, origin_len);
        let timestamp = match SystemTime::now().duration_since(UNIX_EPOCH) {
            Ok(d) => d.as_secs(),
            Err(_) => return -2,
        };
        let timestamp_str = timestamp.to_string();
        let timestamp_bytes = timestamp_str.as_bytes();
        let mut hasher = Sha1::new();
        hasher.update(timestamp_bytes);
        hasher.update(b" ");
        hasher.update(sapisid);
        hasher.update(b" ");
        hasher.update(origin);
        let digest = hasher.finalize();
        let digest_hex = hex::encode(digest);
        let formatted = format!("SAPISIDHASH {}_{}", timestamp_str, digest_hex);
        let formatted_bytes = formatted.as_bytes();
        if formatted_bytes.len() > out_buf_len {
            return -1;
        }
        std::ptr::copy_nonoverlapping(formatted_bytes.as_ptr(), out_buf, formatted_bytes.len());
        formatted_bytes.len() as i32
    });
    result.unwrap_or(-3)
}
