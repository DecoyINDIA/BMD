//! The only JVM FFI surface. jni-rs owns local-reference lifetimes and catches panics;
//! a redacted policy converts all recoverable errors to bounded JSON.
use crate::{Fault, INPUT_LIMIT};
use jni::{
    Env, EnvUnowned,
    errors::{Error, ErrorPolicy},
    jni_sig, jni_str,
    objects::{JClass, JObject, JString},
    sys::{jlong, jstring},
};

struct Redacted;
fn as_java(env: &mut Env<'_>, text: &str) -> jni::errors::Result<jstring> {
    Ok(env.new_string(text)?.into_raw())
}
fn failure(env: &mut Env<'_>, code: &'static str) -> jni::errors::Result<jstring> {
    env.exception_clear();
    as_java(env, &crate::envelope(Err(Fault::new(code))))
}
impl ErrorPolicy<jstring, Error> for Redacted {
    type Captures<'a: 'b, 'b> = ();
    fn on_error<'a: 'b, 'b>(
        env: &mut Env<'a>,
        _: &mut (),
        _: Error,
    ) -> jni::errors::Result<jstring> {
        failure(env, "JNI_FAILED")
    }
    fn on_panic<'a: 'b, 'b>(
        env: &mut Env<'a>,
        _: &mut (),
        _: Box<dyn std::any::Any + Send + 'static>,
    ) -> jni::errors::Result<jstring> {
        failure(env, "NATIVE_PANIC")
    }
    fn on_internal_jni_error<'a: 'b, 'b>(_: &mut (), _: Error) -> jstring {
        std::ptr::null_mut()
    }
    fn on_internal_panic<'a: 'b, 'b>(
        _: &mut (),
        _: Box<dyn std::any::Any + Send + 'static>,
    ) -> jstring {
        std::ptr::null_mut()
    }
}

// SAFETY: exported signatures exactly match the three static Java declarations. All JVM
// objects are scoped to this native frame; only the verifier retains the application Context.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_blackmarlin_downloader_bridge_NativeEngine_nativeInitialize<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    context: JObject<'local>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            let app = env
                .call_method(
                    &context,
                    jni_str!("getApplicationContext"),
                    jni_sig!("()Landroid/content/Context;"),
                    &[],
                )?
                .l()?;
            if app.is_null() {
                return failure(env, "APPLICATION_CONTEXT_REQUIRED");
            }
            let folder = env
                .call_method(
                    &app,
                    jni_str!("getNoBackupFilesDir"),
                    jni_sig!("()Ljava/io/File;"),
                    &[],
                )?
                .l()?;
            let path = env
                .call_method(
                    &folder,
                    jni_str!("getAbsolutePath"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let path = env.cast_local::<JString>(path)?.try_to_string(env)?;
            if path.len() > 4096 {
                return failure(env, "PRIVATE_PATH_REFUSED");
            }
            if rustls_platform_verifier::android::init_with_env(env, app).is_err() {
                return failure(env, "TLS_INITIALIZATION_FAILED");
            }
            as_java(env, &crate::contained(|| crate::initialize(path.into())))
        })
        .resolve::<Redacted>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_blackmarlin_downloader_bridge_NativeEngine_nativeCommand<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            // Check Java's UTF-16 length before allocating/copying untrusted command text. The
            // resulting UTF-8 string has a second exact byte bound in dispatch().
            let length = env
                .call_method(&request, jni_str!("length"), jni_sig!("()I"), &[])?
                .i()?;
            if length < 0 || length as usize > INPUT_LIMIT {
                return failure(env, "REQUEST_TOO_LARGE");
            }
            let text = request.try_to_string(env)?;
            as_java(env, &crate::contained(|| crate::dispatch(&text)))
        })
        .resolve::<Redacted>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_blackmarlin_downloader_bridge_NativeEngine_nativeSnapshot<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    unowned
        .with_env(|env| -> jni::errors::Result<jstring> {
            as_java(
                env,
                &crate::contained(|| {
                    if handle <= 0 {
                        Err(Fault::new("HANDLE_NOT_FOUND"))
                    } else {
                        crate::snapshot(handle as u64)
                    }
                }),
            )
        })
        .resolve::<Redacted>()
}
