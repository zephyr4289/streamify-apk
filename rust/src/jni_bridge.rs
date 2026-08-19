use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use crate::auth::generate_sapisid_hash;
use crate::repository::generate_cad_id;

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateSapisidHash(
    mut env: JNIEnv,
    _class: JClass,
    sapisid_bytes: JByteArray,
    sapisid_len: jint,
    origin_bytes: JByteArray,
    origin_len: jint,
    out_buf: JByteArray,
    out_buf_len: jint,
) -> jint {
    let sapisid_elements = match env.get_array_elements(&sapisid_bytes, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(elems) => elems,
        Err(_) => return -2,
    };
    let origin_elements = match env.get_array_elements(&origin_bytes, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(elems) => elems,
        Err(_) => return -2,
    };
    let mut out_elements = match env.get_array_elements(&out_buf, jni::objects::ReleaseMode::CopyBack) {
        Ok(elems) => elems,
        Err(_) => return -2,
    };
    generate_sapisid_hash(
        sapisid_elements.as_ptr() as *const u8,
        sapisid_len as usize,
        origin_elements.as_ptr() as *const u8,
        origin_len as usize,
        out_elements.as_mut_ptr() as *mut u8,
        out_buf_len as usize,
    ) as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateCadId(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    artist: JString,
    duration_sec: jint,
) -> jstring {
    let title_str: String = env.get_string(&title).map(|s| s.into()).unwrap_or_default();
    let artist_str: String = env.get_string(&artist).map(|s| s.into()).unwrap_or_default();
    let cad_id = generate_cad_id(&title_str, &artist_str, duration_sec as u32);
    env.new_string(cad_id).unwrap().into_raw()
}
