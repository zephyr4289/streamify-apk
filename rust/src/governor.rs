use rusqlite::Connection;
use std::ffi::CStr;
use std::panic::catch_unwind;
use std::sync::atomic::{AtomicI32, Ordering};

static THERMAL_STATUS: AtomicI32 = AtomicI32::new(0); // 0 = None, 1 = Light, 2 = Moderate, 3 = Severe, 4 = Critical, 5 = Emergency

#[no_mangle]
pub unsafe extern "C" fn update_thermal_status(status: i32) -> i32 {
    let result = catch_unwind(|| {
        THERMAL_STATUS.store(status, Ordering::Relaxed);
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn get_thermal_status() -> i32 {
    THERMAL_STATUS.load(Ordering::Relaxed)
}

#[no_mangle]
pub unsafe extern "C" fn flush_database_wal(db_path_ptr: *const std::os::raw::c_char) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() {
            return -1;
        }
        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        if db_path.is_empty() {
            return -1;
        }

        if let Ok(conn) = Connection::open(db_path) {
            let _ = conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE); PRAGMA shrink_memory;");
            0
        } else {
            -2
        }
    });
    result.unwrap_or(-3)
}
